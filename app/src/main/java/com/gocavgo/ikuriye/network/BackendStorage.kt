package com.gocavgo.ikuriye.network

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.nexx.NexxAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reads a route endpoint ("origin"/"destination") which may be a plain string
 * or a nested location object. Returns (displayName, (latitude?, longitude?)).
 */
private fun routeLocation(route: JSONObject?, key: String): Pair<String?, Pair<Double?, Double?>>? {
    if (route == null || !route.has(key)) return null
    val value = route.opt(key)
    if (value is JSONObject) {
        val name = value.optString("custom_name", null)
            ?: value.optString("google_place_name", null)
            ?: value.optString("code", null)
        val lat = if (value.has("latitude")) value.optDouble("latitude") else null
        val lng = if (value.has("longitude")) value.optDouble("longitude") else null
        return Pair(name, Pair(lat, lng))
    }
    if (value is String) {
        return Pair(if (value.isNotBlank()) value else null, Pair(null, null))
    }
    return null
}

/**
 * Uploads files through the CavGo backend. Returns only what clients need:
 * [UploadResult.mediaId], [UploadResult.url], [UploadResult.mimeType].
 *
 * Clients never see storage paths, buckets, or backend storage details.
 */
object BackendStorage {

    private const val TAG = "BackendStorage"
    private const val MAX_UPLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_000L
    private const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB — must match server limit

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Base URL for REST endpoints served through the gateway.
     * Set via CAVGO_BASE_URL in secrets.properties, e.g. https://api.med.rw/gocavgo.
     */
    private val restBaseUrl: String = BuildConfig.REST_BASE_URL

    // ── Driver profile (cavgomain via gateway /main/internal/api) ────────

    data class DriverWorkerResponse(
        val id: String,
        val name: String,
        val phone: String?,
        val email: String?,
        val licenseNumber: String?,
        val status: String?,
        val role: String?,
        val vehicle: DriverVehicleResponse?
    )

    data class DriverVehicleResponse(
        val id: Long,
        val make: String?,
        val model: String?,
        val capacity: Int,
        val licensePlate: String?,
        val vehicleType: String?,
        val status: String?,
        val isOnline: Boolean?,
        val lastOnlineAt: String?
    )

    /**
     * Fetch the driver's worker profile (including assigned vehicle) from cavgomain.
     * Uses the internal API which requires no auth header (service-to-service).
     */
    suspend fun fetchDriverWorker(driverId: Long): DriverWorkerResponse? = withContext(Dispatchers.IO) {
        try {
            val url = "$restBaseUrl/main/internal/api/workers/$driverId"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchDriverWorker failed: HTTP ${response.code}")
                return@withContext null
            }
            val json = JSONObject(body)
            val vehicleJson = json.optJSONObject("vehicle")
            DriverWorkerResponse(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                phone = json.optString("phone", null),
                email = json.optString("email", null),
                licenseNumber = json.optString("licenseNumber", null),
                status = json.optString("status", null),
                role = json.optString("role", null),
                vehicle = if (vehicleJson != null) DriverVehicleResponse(
                    id = vehicleJson.optLong("id", 0),
                    make = vehicleJson.optString("make", null),
                    model = vehicleJson.optString("model", null),
                    capacity = vehicleJson.optInt("capacity", 0),
                    licensePlate = vehicleJson.optString("licensePlate", null),
                    vehicleType = vehicleJson.optString("vehicleType", null),
                    status = vehicleJson.optString("status", null),
                    isOnline = if (vehicleJson.has("isOnline")) vehicleJson.optBoolean("isOnline") else null,
                    lastOnlineAt = vehicleJson.optString("lastOnlineAt", null)
                ) else null
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchDriverWorker failed: ${e.message}")
            null
        }
    }

    // ── Driver request ──────────────────────────────────────────────────────

    /**
     * Submit a driver request for the authenticated user.
     * Calls POST /main/driver-requests with the company code.
     * Uses the user's access token from NexxAuth.
     * Returns null on success, or an error message on failure.
     */
    suspend fun submitDriverRequest(companyCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val accessToken = com.gocavgo.ikuriye.nexx.NexxAuth.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                return@withContext "Not authenticated — please sign in again"
            }
            val jsonBody = JSONObject().put("companyCode", companyCode).toString()
            val request = Request.Builder()
                .url("$restBaseUrl/main/driver-requests")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful) {
                Log.d(TAG, "submitDriverRequest OK: companyCode=$companyCode")
                null
            } else {
                Log.w(TAG, "submitDriverRequest failed: HTTP ${response.code}: $responseBody")
                val message = try {
                    val json = JSONObject(responseBody ?: "")
                    json.optString("message").ifBlank { json.optString("error") }
                } catch (e: Exception) { "" }
                message.ifBlank { "Failed to submit driver request (HTTP ${response.code})" }
            }
        } catch (e: Exception) {
            Log.w(TAG, "submitDriverRequest failed: ${e.message}")
            "Failed to submit request: ${e.message}"
        }
    }

    // ── Driver request status ──────────────────────────────────────────────

    data class DriverRequestStatusResponse(
        val id: Long?,
        val status: String?,  // PENDING, APPROVED, REJECTED
        val companyCode: String?,
        val companyName: String?,
        val rejectionReason: String?
    )

    /**
     * Check if the authenticated user has an existing driver request.
     * Returns the latest request status, or null if no request exists.
     */
    suspend fun getMyDriverRequestStatus(): DriverRequestStatusResponse? = withContext(Dispatchers.IO) {
        try {
            val accessToken = com.gocavgo.ikuriye.nexx.NexxAuth.getAccessToken()
            if (accessToken.isNullOrBlank()) return@withContext null
            val request = Request.Builder()
                .url("$restBaseUrl/main/driver-requests/my-status")
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (response.code == 204) return@withContext null  // No content = no request
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(body ?: return@withContext null)
            DriverRequestStatusResponse(
                id = json.optLong("id").takeIf { it > 0 },
                status = json.optString("status", null),
                companyCode = json.optString("companyCode", null),
                companyName = json.optString("companyName", null),
                rejectionReason = json.optString("rejectionReason", null)
            )
        } catch (e: Exception) {
            Log.w(TAG, "getMyDriverRequestStatus failed: ${e.message}")
            null
        }
    }

    // ── Driver trips (cavgotrips via gateway /navig/trips/driver/{id}) ────

    data class DriverTripWaypoint(
        val locationName: String?,
        val latitude: Double,
        val longitude: Double,
        val isPassed: Boolean,
        val isNext: Boolean,
        val remainingDistance: Double?,
        val remainingTime: Double?
    )

    data class DriverTrip(
        val id: Long,
        val status: String?,
        val origin: String?,
        val destination: String?,
        val originLatitude: Double? = null,
        val originLongitude: Double? = null,
        val destinationLatitude: Double? = null,
        val destinationLongitude: Double? = null,
        val routeName: String?,
        val departureTime: Long?,
        val currentLatitude: Double?,
        val currentLongitude: Double?,
        val currentSpeed: Double?,
        val seats: Int?,
        val remainingSeats: Int?,
        val waypoints: List<DriverTripWaypoint>,
        val vehicleLicensePlate: String?,
        val vehicleMake: String?,
        val vehicleModel: String?
    )

    data class DriverTripsResponse(
        val trips: List<DriverTrip>,
        val total: Long,
        val metrics: DriverMetrics?
    )

    data class DriverMetrics(
        val totalTrips: Long,
        val totalKilometers: Double,
        val dailyTrips: Long,
        val monthlyTrips: Long,
        val currentActiveTrip: Long?
    )

    /**
     * Fetch trips for a driver from cavgotrips via the gateway.
     * @param status Optional filter: SCHEDULED, IN_PROGRESS, COMPLETED, etc.
     */
    suspend fun fetchDriverTrips(driverId: Long, status: String? = null, limit: Int = 50): DriverTripsResponse = withContext(Dispatchers.IO) {
        try {
            val statusParam = if (!status.isNullOrBlank()) "&status=$status" else ""
            val url = "$restBaseUrl/navig/trips/driver/$driverId?limit=$limit&offset=0$statusParam"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext DriverTripsResponse(emptyList(), 0, null)
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchDriverTrips failed: HTTP ${response.code}: $body")
                return@withContext DriverTripsResponse(emptyList(), 0, null)
            }
            val json = JSONObject(body)
            val tripsArray = json.optJSONArray("trips")
            Log.d(TAG, "fetchDriverTrips OK: driver=$driverId status=$status → ${tripsArray?.length() ?: 0} trips (total=${json.optLong("total", 0)})")
            if (tripsArray == null) return@withContext DriverTripsResponse(emptyList(), 0, null)
            val trips = mutableListOf<DriverTrip>()
            for (i in 0 until tripsArray.length()) {
                val t = tripsArray.getJSONObject(i)
                val route = t.optJSONObject("route")
                val vehicle = t.optJSONObject("vehicle")
                val wpsArray = t.optJSONArray("waypoints")
                val waypoints = mutableListOf<DriverTripWaypoint>()
                if (wpsArray != null) {
                    for (j in 0 until wpsArray.length()) {
                        val wp = wpsArray.getJSONObject(j)
                        // Waypoints may be flattened (location_name/latitude/...) or
                        // nested under a "location" object — tolerate both shapes.
                        val wpLocation = wp.optJSONObject("location")
                        val locationName = when {
                            wp.has("location_name") -> wp.optString("location_name", null)
                            wpLocation != null -> wpLocation.optString("custom_name", null)
                                ?: wpLocation.optString("google_place_name", null)
                            else -> null
                        }
                        val lat = if (wp.has("latitude")) wp.optDouble("latitude", 0.0)
                            else wpLocation?.optDouble("latitude", 0.0) ?: 0.0
                        val lng = if (wp.has("longitude")) wp.optDouble("longitude", 0.0)
                            else wpLocation?.optDouble("longitude", 0.0) ?: 0.0
                        waypoints.add(
                            DriverTripWaypoint(
                                locationName = locationName,
                                latitude = lat,
                                longitude = lng,
                                isPassed = wp.optBoolean("is_passed", false),
                                isNext = wp.optBoolean("is_next", false),
                                remainingDistance = if (wp.has("remaining_distance")) wp.optDouble("remaining_distance") else null,
                                remainingTime = if (wp.has("remaining_time")) wp.optDouble("remaining_time") else null
                            )
                        )
                    }
                }
                // Route endpoints may be strings or nested location objects.
                val routeOrigin = routeLocation(route, "origin")
                val routeDestination = routeLocation(route, "destination")
                trips.add(
                    DriverTrip(
                        id = t.optLong("id", 0),
                        status = t.optString("status", null),
                        origin = routeOrigin?.first,
                        destination = routeDestination?.first,
                        originLatitude = routeOrigin?.second?.first,
                        originLongitude = routeOrigin?.second?.second,
                        destinationLatitude = routeDestination?.second?.first,
                        destinationLongitude = routeDestination?.second?.second,
                        routeName = route?.optString("name", null),
                        departureTime = if (t.has("departure_time")) t.optLong("departure_time") else null,
                        currentLatitude = if (t.has("current_latitude")) t.optDouble("current_latitude") else null,
                        currentLongitude = if (t.has("current_longitude")) t.optDouble("current_longitude") else null,
                        currentSpeed = if (t.has("current_speed")) t.optDouble("current_speed") else null,
                        seats = if (t.has("seats")) t.optInt("seats") else null,
                        remainingSeats = if (t.has("remaining_seats")) t.optInt("remaining_seats") else null,
                        waypoints = waypoints,
                        vehicleLicensePlate = vehicle?.optString("licensePlate", null),
                        vehicleMake = vehicle?.optString("make", null),
                        vehicleModel = vehicle?.optString("model", null)
                    )
                )
            }
            val metricsJson = json.optJSONObject("metrics")
            val metrics = if (metricsJson != null) DriverMetrics(
                totalTrips = metricsJson.optLong("total_trips", 0),
                totalKilometers = metricsJson.optDouble("total_kilometers", 0.0),
                dailyTrips = metricsJson.optLong("daily_trips", 0),
                monthlyTrips = metricsJson.optLong("monthly_trips", 0),
                currentActiveTrip = if (metricsJson.has("current_active_trip")) metricsJson.optLong("current_active_trip") else null
            ) else null
            DriverTripsResponse(trips, json.optLong("total", 0), metrics)
        } catch (e: Exception) {
            Log.w(TAG, "fetchDriverTrips failed: ${e.message}")
            DriverTripsResponse(emptyList(), 0, null)
        }
    }

    // ── Route search + trip creation (cavgotrips via gateway /navig) ─────

    data class DriverRoute(
        val id: Long,
        val name: String?,
        val distanceMeters: Long?,
        val estimatedDurationSeconds: Long?,
        val routePrice: Double?,
        val cityRoute: Boolean,
        val originName: String?,
        val destinationName: String?
    ) {
        /** Display label like "Kigali → Musanze". */
        fun displayLabel(): String = listOfNotNull(originName, destinationName)
            .joinToString(" → ")
            .ifBlank { name ?: "Route #$id" }
    }

    /**
     * Search routes from cavgotrips through the gateway. Filters are fuzzy
     * name matches on the route origin/destination locations.
     * Returns an empty list on failure so the UI degrades gracefully.
     */
    suspend fun searchRoutes(origin: String? = null, destination: String? = null, limit: Int = 50): List<DriverRoute> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$restBaseUrl/navig/routes".toHttpUrl().newBuilder()
            origin?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("origin", it) }
            destination?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("destination", it) }
            urlBuilder.addQueryParameter("page", "1")
            urlBuilder.addQueryParameter("limit", limit.toString())
            val request = Request.Builder().url(urlBuilder.build()).get().addHeader("Accept", "application/json").build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) {
                Log.w(TAG, "Route search failed: HTTP ${response.code}")
                return@withContext emptyList()
            }
            val json = JSONObject(body)
            val dataArray = json.optJSONArray("data") ?: return@withContext emptyList()
            val results = mutableListOf<DriverRoute>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                results.add(
                    DriverRoute(
                        id = obj.optLong("id", 0),
                        name = obj.optString("name", null),
                        distanceMeters = if (obj.has("distance_meters")) obj.optLong("distance_meters") else null,
                        estimatedDurationSeconds = if (obj.has("estimated_duration_seconds")) obj.optLong("estimated_duration_seconds") else null,
                        routePrice = if (obj.has("route_price") && !obj.isNull("route_price")) obj.optDouble("route_price") else null,
                        cityRoute = obj.optBoolean("city_route", false),
                        originName = obj.optJSONObject("origin")?.let { loc ->
                            loc.optString("custom_name", null) ?: loc.optString("google_place_name", null)
                        },
                        destinationName = obj.optJSONObject("destination")?.let { loc ->
                            loc.optString("custom_name", null) ?: loc.optString("google_place_name", null)
                        }
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Route search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Create a trip on cavgotrips via the gateway.
     * Minimal flow mirrors fleetman: route + vehicle + departure time + reverse flag.
     * Returns null on success, or an error message on failure.
     */
    suspend fun createTrip(
        routeId: Long,
        vehicleId: Long,
        departureTimeSeconds: Long,
        isReversed: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("route_id", routeId)
                put("vehicle_id", vehicleId)
                put("departure_time", departureTimeSeconds)
                put("connection_mode", "ONLINE")
                put("is_reversed", isReversed)
            }.toString()
            val request = Request.Builder()
                .url("$restBaseUrl/navig/trips")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful) {
                Log.d(TAG, "createTrip OK: route=$routeId vehicle=$vehicleId reversed=$isReversed")
                null
            } else {
                Log.w(TAG, "createTrip failed: HTTP ${response.code}: $responseBody")
                val message = try {
                    JSONObject(responseBody ?: "").optString("error")
                } catch (e: Exception) {
                    ""
                }
                message.ifBlank { "Failed to create trip (HTTP ${response.code})" }
            }
        } catch (e: Exception) {
            Log.w(TAG, "createTrip failed: ${e.message}")
            "Failed to create trip: ${e.message}"
        }
    }

    // ── Location search (cavgotrips via gateway /navig/locations) ────────

    data class TripLocation(
        val id: Long,
        val latitude: Double,
        val longitude: Double,
        val customName: String?,
        val googlePlaceName: String?,
        val province: String?,
        val district: String?,
        val placeId: String?,
        val code: String?
    ) {
        /** Display name: prefer custom_name, fall back to google_place_name, then code. */
        fun displayName(): String = customName ?: googlePlaceName ?: code ?: "Location #$id"
        /** Subtitle: district and/or province. */
        fun subtitle(): String = listOfNotNull(district, province).joinToString(", ")
    }

    private data class LocationSearchResponse(
        val data: List<TripLocation>?,
        val pagination: Any? // not needed client-side
    )

    /**
     * Search locations from cavgotrips through the gateway.
     * Returns an empty list on failure so the UI degrades gracefully.
     */
    suspend fun searchLocations(query: String, limit: Int = 20): List<TripLocation> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val url = "$restBaseUrl/navig/locations?search=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit&page=1"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            if (!response.isSuccessful) {
                Log.w(TAG, "Location search failed: HTTP ${response.code}")
                return@withContext emptyList()
            }
            val json = JSONObject(body)
            val dataArray = json.optJSONArray("data") ?: return@withContext emptyList()
            val results = mutableListOf<TripLocation>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                results.add(
                    TripLocation(
                        id = obj.optLong("id", 0),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0),
                        customName = obj.optString("custom_name", null),
                        googlePlaceName = obj.optString("google_place_name", null),
                        province = obj.optString("province", null),
                        district = obj.optString("district", null),
                        placeId = obj.optString("place_id", null),
                        code = obj.optString("code", null)
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Location search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Upload a file. Returns [UploadResult] with mediaId + url + mimeType, or null on failure.
     */
    suspend fun uploadFile(
        byteArray: ByteArray,
        mimeType: String,
        purpose: String = "package-media",
        onProgress: ((Double) -> Unit)? = null
    ): UploadResult? = withContext(Dispatchers.IO) {
        val token = NexxAuth.getAccessToken()
        if (token == null) {
            Log.e(TAG, "No access token available for upload")
            return@withContext null
        }

        val extension = mimeType.substringAfter("/", "jpeg")
        val fileName = "${UUID.randomUUID()}.$extension"

        if (byteArray.size > MAX_FILE_SIZE_BYTES) {
            Log.e(TAG, "File too large: ${byteArray.size} bytes exceeds ${MAX_FILE_SIZE_BYTES} byte limit")
            return@withContext null
        }

        onProgress?.invoke(0.0)
        onProgress?.invoke(5.0)

        var lastError: Exception? = null
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            // Re-fetch token on each attempt — it may have been refreshed by a previous 401
            val currentToken = NexxAuth.getAccessToken()
            if (currentToken == null) {
                Log.e(TAG, "No access token available for upload (attempt $attempt)")
                return@withContext null
            }
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        fileName,
                        byteArray.toRequestBody(mimeType.toMediaType())
                    )
                    .addFormDataPart("purpose", purpose)
                    .build()

                val request = Request.Builder()
                    .url("${BuildConfig.REST_BASE_URL}/ikuriye/api/files/upload")
                    .addHeader("Authorization", "Bearer $currentToken")
                    .post(body)
                    .build()

                onProgress?.invoke(50.0)

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    // If HTTP 401 — try refreshing the token before the next attempt
                    if (response.code == 401) {
                        Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS got 401 — refreshing token")
                        NexxAuth.refreshSession()
                    } else {
                        Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS failed: HTTP ${response.code}")
                    }
                    if (attempt < MAX_UPLOAD_ATTEMPTS) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    }
                    continue
                }

                val json = JSONObject(responseBody)
                if (json.has("error")) {
                    Log.e(TAG, "Upload error: ${json.getString("error")}")
                    lastError = Exception(json.getString("error"))
                    continue
                }

                onProgress?.invoke(95.0)

                val result = UploadResult(
                    mediaId = json.getString("mediaId"),
                    url = json.getString("url"),
                    mimeType = json.getString("mimeType")
                )

                Log.d(TAG, "Upload succeeded: mediaId=${result.mediaId}")
                onProgress?.invoke(100.0)
                return@withContext result

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_UPLOAD_ATTEMPTS) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                }
            }
        }

        Log.e(TAG, "Upload failed after $MAX_UPLOAD_ATTEMPTS attempts: ${lastError?.message}")
        null
    }

    data class UploadResult(
        val mediaId: String,
        val url: String,
        val mimeType: String
    )
}
