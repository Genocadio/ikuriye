package com.gocavgo.ikuriye.network

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.nexx.NexxAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

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
     * Derived at build time from GRAPHQL_URL — strips /ikuriye/graphql to get
     * the gateway root, e.g. https://api.med.rw/gocavgo.
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
            val tripsArray = json.optJSONArray("trips") ?: return@withContext DriverTripsResponse(emptyList(), 0, null)
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
                        waypoints.add(
                            DriverTripWaypoint(
                                locationName = wp.optString("location_name", null),
                                latitude = wp.optDouble("latitude", 0.0),
                                longitude = wp.optDouble("longitude", 0.0),
                                isPassed = wp.optBoolean("is_passed", false),
                                isNext = wp.optBoolean("is_next", false),
                                remainingDistance = if (wp.has("remaining_distance")) wp.optDouble("remaining_distance") else null,
                                remainingTime = if (wp.has("remaining_time")) wp.optDouble("remaining_time") else null
                            )
                        )
                    }
                }
                trips.add(
                    DriverTrip(
                        id = t.optLong("id", 0),
                        status = t.optString("status", null),
                        origin = route?.optString("origin", null),
                        destination = route?.optString("destination", null),
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
                    .url("${BuildConfig.GRAPHQL_URL.replace("/graphql", "")}/api/files/upload")
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
