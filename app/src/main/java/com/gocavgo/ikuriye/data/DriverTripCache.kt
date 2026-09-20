package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.network.BackendStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-first cache for driver trip + vehicle data.
 *
 * Caches the active trip, trip history, vehicle info, and driver metrics so
 * the driver screen never shows a loading spinner on re-entry. Data is
 * refreshed silently in the background.
 */
object DriverTripCache {

    private const val TAG = "DriverTripCache"
    private const val CACHE_FILE = "driver_trip_state.json"
    private const val EXPIRY_MS = 30 * 60 * 1000L // 30 minutes
    private const val TOUCH_THROTTLE_MS = 2 * 60 * 1000L

    private var cacheDir: File? = null

    fun init(context: android.content.Context) {
        cacheDir = File(context.cacheDir, "driver_trip_cache")
        cacheDir?.mkdirs()
        evictExpired()
    }

    data class CachedDriverTripState(
        val activeTrip: BackendStorage.DriverTrip?,
        val tripHistory: List<BackendStorage.DriverTrip>,
        val metrics: BackendStorage.DriverMetrics?,
        val driverCompletedTrips: List<com.gocavgo.ikuriye.viewmodel.CompletedTrip>,
        val vehicleId: Long?,
        val vehiclePlate: String,
        val vehicleModel: String,
        val vehicleType: String?,
        val vehicleSeats: Int,
        val driverHasVehicle: Boolean,
        val cachedAt: Long = System.currentTimeMillis()
    )

    fun get(): CachedDriverTripState? {
        val dir = cacheDir ?: return null
        val file = File(dir, CACHE_FILE)
        if (!file.exists()) return null
        val isOnline = AuthRepository.isNetworkAvailable()
        return try {
            val json = JSONObject(file.readText())
            val cachedAt = json.optLong("cachedAt", 0L)
            val isExpired = System.currentTimeMillis() - cachedAt > EXPIRY_MS
            if (isExpired) {
                if (isOnline) {
                    Log.d(TAG, "Cache expired (online) — will refresh silently")
                    file.delete()
                    return null
                } else {
                    Log.w(TAG, "Cache expired but offline — preserving stale cache")
                }
            }
            if (isOnline) touch(file, json, cachedAt)
            parseCache(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache", e)
            if (isOnline) file.delete()
            null
        }
    }

    fun save(state: CachedDriverTripState) {
        val dir = cacheDir ?: return
        try {
            val json = JSONObject().apply {
                put("cachedAt", System.currentTimeMillis())
                put("vehicleId", state.vehicleId ?: JSONObject.NULL)
                put("vehiclePlate", state.vehiclePlate)
                put("vehicleModel", state.vehicleModel)
                put("vehicleType", state.vehicleType ?: JSONObject.NULL)
                put("vehicleSeats", state.vehicleSeats)
                put("driverHasVehicle", state.driverHasVehicle)
                put("activeTrip", state.activeTrip?.let { tripToJson(it) } ?: JSONObject.NULL)
                val histArr = JSONArray()
                state.tripHistory.take(20).forEach { histArr.put(tripToJson(it)) }
                put("tripHistory", histArr)
                put("metrics", state.metrics?.let { metricsToJson(it) } ?: JSONObject.NULL)
                val completedArr = JSONArray()
                state.driverCompletedTrips.forEach { ct ->
                    completedArr.put(JSONObject().apply {
                        put("origin", ct.origin)
                        put("destination", ct.destination)
                        put("plateNumber", ct.plateNumber)
                    })
                }
                put("driverCompletedTrips", completedArr)
            }
            File(dir, CACHE_FILE).writeText(json.toString())
            Log.d(TAG, "Cache saved: activeTrip=${state.activeTrip != null}, history=${state.tripHistory.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    fun clear() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    private fun putSafeDouble(json: JSONObject, key: String, value: Double?) {
        if (value != null && !value.isNaN() && !value.isInfinite()) {
            json.put(key, value)
        } else {
            json.put(key, JSONObject.NULL)
        }
    }

    private fun tripToJson(trip: BackendStorage.DriverTrip): JSONObject {
        val wpArr = JSONArray()
        trip.waypoints.forEach { wp ->
            wpArr.put(JSONObject().apply {
                put("locationName", wp.locationName ?: "")
                putSafeDouble(this, "latitude", wp.latitude)
                putSafeDouble(this, "longitude", wp.longitude)
                put("isPassed", wp.isPassed)
                put("isNext", wp.isNext)
                wp.order?.let { put("order", it) }
                wp.remainingDistance?.let { putSafeDouble(this, "remainingDistance", it) }
                wp.remainingTime?.let { putSafeDouble(this, "remainingTime", it) }
            })
        }
        return JSONObject().apply {
            put("id", trip.id)
            put("status", trip.status ?: "")
            put("origin", trip.origin ?: "")
            put("destination", trip.destination ?: "")
            putSafeDouble(this, "originLatitude", trip.originLatitude)
            putSafeDouble(this, "originLongitude", trip.originLongitude)
            putSafeDouble(this, "destinationLatitude", trip.destinationLatitude)
            putSafeDouble(this, "destinationLongitude", trip.destinationLongitude)
            put("routeName", trip.routeName ?: "")
            trip.departureTime?.let { put("departureTime", it) } ?: put("departureTime", JSONObject.NULL)
            putSafeDouble(this, "currentLatitude", trip.currentLatitude)
            putSafeDouble(this, "currentLongitude", trip.currentLongitude)
            putSafeDouble(this, "currentSpeed", trip.currentSpeed)
            trip.seats?.let { put("seats", it) } ?: put("seats", JSONObject.NULL)
            trip.remainingSeats?.let { put("remainingSeats", it) } ?: put("remainingSeats", JSONObject.NULL)
            put("vehicleLicensePlate", trip.vehicleLicensePlate ?: "")
            put("vehicleMake", trip.vehicleMake ?: "")
            put("vehicleModel", trip.vehicleModel ?: "")
            put("waypoints", wpArr)
        }
    }

    private fun metricsToJson(m: BackendStorage.DriverMetrics): JSONObject = JSONObject().apply {
        put("totalTrips", m.totalTrips)
        putSafeDouble(this, "totalKilometers", m.totalKilometers)
        put("dailyTrips", m.dailyTrips)
        put("monthlyTrips", m.monthlyTrips)
        m.currentActiveTrip?.let { put("currentActiveTrip", it) } ?: put("currentActiveTrip", JSONObject.NULL)
    }

    // ── Deserialization ────────────────────────────────────────────────────────

    private fun parseCache(json: JSONObject): CachedDriverTripState {
        val activeTripJson = json.optJSONObject("activeTrip")
        val activeTrip = activeTripJson?.let { parseTrip(it) }

        val histArr = json.optJSONArray("tripHistory")
        val history = if (histArr != null) {
            (0 until histArr.length()).mapNotNull { parseTrip(histArr.optJSONObject(it)) }
        } else emptyList()

        val metricsJson = json.optJSONObject("metrics")
        val metrics = metricsJson?.let { parseMetrics(it) }

        val completedArr = json.optJSONArray("driverCompletedTrips")
        val completedTrips = if (completedArr != null) {
            (0 until completedArr.length()).mapNotNull { i ->
                val ct = completedArr.optJSONObject(i) ?: return@mapNotNull null
                com.gocavgo.ikuriye.viewmodel.CompletedTrip(
                    origin = ct.optString("origin", "Unknown"),
                    destination = ct.optString("destination", "Unknown"),
                    plateNumber = ct.optString("plateNumber", "")
                )
            }
        } else emptyList()

        return CachedDriverTripState(
            activeTrip = activeTrip,
            tripHistory = history,
            metrics = metrics,
            driverCompletedTrips = completedTrips,
            vehicleId = json.optLong("vehicleId").takeIf { !json.isNull("vehicleId") },
            vehiclePlate = json.optString("vehiclePlate", ""),
            vehicleModel = json.optString("vehicleModel", ""),
            vehicleType = json.optString("vehicleType", "").ifBlank { null },
            vehicleSeats = json.optInt("vehicleSeats", 0),
            driverHasVehicle = json.optBoolean("driverHasVehicle", false)
        )
    }

    private fun parseTrip(json: JSONObject): BackendStorage.DriverTrip? {
        return try {
            val wpArr = json.optJSONArray("waypoints")
            val waypoints = if (wpArr != null) {
                (0 until wpArr.length()).mapNotNull { i ->
                    val wp = wpArr.optJSONObject(i) ?: return@mapNotNull null
                    BackendStorage.DriverTripWaypoint(
                        locationName = wp.optString("locationName", "").ifBlank { null },
                        latitude = wp.optDouble("latitude", 0.0),
                        longitude = wp.optDouble("longitude", 0.0),
                        isPassed = wp.optBoolean("isPassed", false),
                        isNext = wp.optBoolean("isNext", false),
                        order = if (wp.has("order") && !wp.isNull("order")) wp.optInt("order", -1).takeIf { it >= 0 } else null,
                        remainingDistance = if (wp.has("remainingDistance") && !wp.isNull("remainingDistance")) wp.optDouble("remainingDistance") else null,
                        remainingTime = if (wp.has("remainingTime") && !wp.isNull("remainingTime")) wp.optDouble("remainingTime") else null
                    )
                }
            } else emptyList()

            BackendStorage.DriverTrip(
                id = json.optLong("id", 0),
                status = json.optString("status", "").ifBlank { null },
                origin = json.optString("origin", "").ifBlank { null },
                destination = json.optString("destination", "").ifBlank { null },
                originLatitude = if (json.has("originLatitude") && !json.isNull("originLatitude")) json.optDouble("originLatitude") else null,
                originLongitude = if (json.has("originLongitude") && !json.isNull("originLongitude")) json.optDouble("originLongitude") else null,
                destinationLatitude = if (json.has("destinationLatitude") && !json.isNull("destinationLatitude")) json.optDouble("destinationLatitude") else null,
                destinationLongitude = if (json.has("destinationLongitude") && !json.isNull("destinationLongitude")) json.optDouble("destinationLongitude") else null,
                routeName = json.optString("routeName", "").ifBlank { null },
                departureTime = if (json.has("departureTime") && !json.isNull("departureTime")) json.optLong("departureTime") else null,
                currentLatitude = if (json.has("currentLatitude") && !json.isNull("currentLatitude")) json.optDouble("currentLatitude") else null,
                currentLongitude = if (json.has("currentLongitude") && !json.isNull("currentLongitude")) json.optDouble("currentLongitude") else null,
                currentSpeed = if (json.has("currentSpeed") && !json.isNull("currentSpeed")) json.optDouble("currentSpeed") else null,
                seats = if (json.has("seats") && !json.isNull("seats")) json.optInt("seats") else null,
                remainingSeats = if (json.has("remainingSeats") && !json.isNull("remainingSeats")) json.optInt("remainingSeats") else null,
                waypoints = waypoints,
                vehicleLicensePlate = json.optString("vehicleLicensePlate", "").ifBlank { null },
                vehicleMake = json.optString("vehicleMake", "").ifBlank { null },
                vehicleModel = json.optString("vehicleModel", "").ifBlank { null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse trip", e)
            null
        }
    }

    private fun parseMetrics(json: JSONObject): BackendStorage.DriverMetrics = BackendStorage.DriverMetrics(
        totalTrips = json.optLong("totalTrips", 0),
        totalKilometers = json.optDouble("totalKilometers", 0.0),
        dailyTrips = json.optLong("dailyTrips", 0),
        monthlyTrips = json.optLong("monthlyTrips", 0),
        currentActiveTrip = if (json.has("currentActiveTrip") && !json.isNull("currentActiveTrip")) json.optLong("currentActiveTrip") else null
    )

    private fun touch(file: File, json: JSONObject, cachedAt: Long) {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TOUCH_THROTTLE_MS) return
        try {
            json.put("cachedAt", now)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to touch cache", e)
        }
    }

    private fun evictExpired() {
        cacheDir?.listFiles()?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val cachedAt = json.optLong("cachedAt", 0L)
                if (System.currentTimeMillis() - cachedAt > EXPIRY_MS * 2) {
                    file.delete()
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
    }
}
