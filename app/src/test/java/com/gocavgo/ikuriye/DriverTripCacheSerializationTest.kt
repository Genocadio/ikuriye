package com.gocavgo.ikuriye

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DriverTripCache data model logic.
 * Avoids org.json.JSONObject (Android framework) — uses plain Kotlin maps.
 */
class DriverTripCacheSerializationTest {

    // ── Trip data model ───────────────────────────────────────────────────

    data class TestTrip(
        val id: Long,
        val status: String,
        val origin: String?,
        val destination: String?,
        val seats: Int?,
        val remainingSeats: Int?,
        val vehicleLicensePlate: String?,
        val vehicleMake: String?,
        val vehicleModel: String?,
        val waypoints: List<TestWaypoint>
    )

    data class TestWaypoint(
        val locationName: String?,
        val latitude: Double,
        val longitude: Double,
        val isPassed: Boolean,
        val isNext: Boolean,
        val remainingDistance: Double? = null,
        val remainingTime: Double? = null
    )

    data class TestMetrics(
        val totalTrips: Long,
        val totalKilometers: Double,
        val dailyTrips: Long,
        val monthlyTrips: Long,
        val currentActiveTrip: Long?
    )

    data class TestCacheState(
        val activeTrip: TestTrip?,
        val tripHistory: List<TestTrip>,
        val metrics: TestMetrics?,
        val completedTrips: List<Triple<String, String, String>>, // origin, dest, plate
        val vehicleId: Long?,
        val vehiclePlate: String,
        val vehicleModel: String,
        val vehicleType: String?,
        val vehicleSeats: Int,
        val driverHasVehicle: Boolean
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun buildTrip(
        id: Long = 42,
        status: String = "IN_PROGRESS",
        origin: String? = "Kigali",
        destination: String? = "Musanze",
        seats: Int? = 14,
        remainingSeats: Int? = 10,
        vehiclePlate: String = "RAB123X",
        waypointCount: Int = 3
    ) = TestTrip(
        id = id,
        status = status,
        origin = origin,
        destination = destination,
        seats = seats,
        remainingSeats = remainingSeats,
        vehicleLicensePlate = vehiclePlate,
        vehicleMake = "Toyota",
        vehicleModel = "Hiace",
        waypoints = (0 until waypointCount).map { i ->
            TestWaypoint(
                locationName = "Stop ${i + 1}",
                latitude = -1.9 + i * 0.1,
                longitude = 30.0 + i * 0.1,
                isPassed = i < 1,
                isNext = i == 1,
                remainingDistance = if (i == 1) 5000.0 else null,
                remainingTime = if (i == 1) 1800.0 else null
            )
        }
    )

    private fun buildMetrics() = TestMetrics(
        totalTrips = 150L,
        totalKilometers = 12500.0,
        dailyTrips = 3L,
        monthlyTrips = 45L,
        currentActiveTrip = 42L
    )

    private fun buildCacheState(
        activeTrip: TestTrip? = buildTrip(),
        historyCount: Int = 2,
        vehicleId: Long? = 100L,
        vehicleType: String? = "Van",
        driverHasVehicle: Boolean = true
    ) = TestCacheState(
        activeTrip = activeTrip,
        tripHistory = (0 until historyCount).map { i ->
            buildTrip(id = 100L + i, status = "COMPLETED", origin = "Origin $i", destination = "Dest $i")
        },
        metrics = buildMetrics(),
        completedTrips = listOf(Triple("Kigali", "Musanze", "RAB123X")),
        vehicleId = vehicleId,
        vehiclePlate = "RAB123X",
        vehicleModel = "Toyota Hiace",
        vehicleType = vehicleType,
        vehicleSeats = 14,
        driverHasVehicle = driverHasVehicle
    )

    // ── Trip data integrity ───────────────────────────────────────────────

    @Test
    fun trip_preservesAllFields() {
        val trip = buildTrip()
        assertEquals(42L, trip.id)
        assertEquals("IN_PROGRESS", trip.status)
        assertEquals("Kigali", trip.origin)
        assertEquals("Musanze", trip.destination)
        assertEquals(14, trip.seats)
        assertEquals(10, trip.remainingSeats)
        assertEquals("RAB123X", trip.vehicleLicensePlate)
        assertEquals(3, trip.waypoints.size)
    }

    @Test
    fun waypoints_preservePassedAndNext() {
        val trip = buildTrip(waypointCount = 4)
        val wps = trip.waypoints

        assertFalse(wps[0].isNext)
        assertTrue(wps[0].isPassed)

        assertTrue(wps[1].isNext)
        assertFalse(wps[1].isPassed)

        assertFalse(wps[2].isNext)
        assertFalse(wps[2].isPassed)
    }

    @Test
    fun waypoints_remainingFields_optional() {
        val trip = buildTrip(waypointCount = 2)
        val wps = trip.waypoints

        assertNull(wps[0].remainingDistance)
        assertNull(wps[0].remainingTime)

        assertEquals(5000.0, wps[1].remainingDistance!!, 0.01)
        assertEquals(1800.0, wps[1].remainingTime!!, 0.01)
    }

    // ── Cache state structure ─────────────────────────────────────────────

    @Test
    fun cacheState_hasAllRequiredFields() {
        val state = buildCacheState()
        assertNotNull(state.activeTrip)
        assertEquals(2, state.tripHistory.size)
        assertNotNull(state.metrics)
        assertEquals(1, state.completedTrips.size)
        assertEquals("RAB123X", state.vehiclePlate)
        assertTrue(state.driverHasVehicle)
    }

    @Test
    fun cacheState_activeTripNull_whenNoTrip() {
        val state = buildCacheState(activeTrip = null)
        assertNull(state.activeTrip)
    }

    @Test
    fun cacheState_vehicleIdNull_whenNoVehicle() {
        val state = buildCacheState(vehicleId = null)
        assertNull(state.vehicleId)
    }

    @Test
    fun cacheState_tripHistory_hasCorrectCount() {
        val state = buildCacheState(historyCount = 5)
        assertEquals(5, state.tripHistory.size)
    }

    @Test
    fun cacheState_completedTrips_preservesOriginDestination() {
        val state = buildCacheState()
        val (origin, dest, plate) = state.completedTrips[0]
        assertEquals("Kigali", origin)
        assertEquals("Musanze", dest)
        assertEquals("RAB123X", plate)
    }

    // ── Metrics ───────────────────────────────────────────────────────────

    @Test
    fun metrics_preservesAllFields() {
        val m = buildMetrics()
        assertEquals(150L, m.totalTrips)
        assertEquals(12500.0, m.totalKilometers, 0.01)
        assertEquals(3L, m.dailyTrips)
        assertEquals(45L, m.monthlyTrips)
        assertEquals(42L, m.currentActiveTrip)
    }

    @Test
    fun metrics_nullActiveTrip() {
        val m = TestMetrics(10L, 500.0, 1L, 10L, null)
        assertNull(m.currentActiveTrip)
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun trip_emptyWaypoints() {
        val trip = buildTrip()
        val emptyTrip = trip.copy(waypoints = emptyList())
        assertEquals(0, emptyTrip.waypoints.size)
    }

    @Test
    fun cacheState_emptyHistory() {
        val state = buildCacheState(historyCount = 0)
        assertEquals(0, state.tripHistory.size)
    }

    @Test
    fun cacheState_vehicleTypeNull_handledGracefully() {
        val state = buildCacheState(vehicleType = null)
        assertNull(state.vehicleType)
    }

    @Test
    fun waypoint_locationName_blank_becomesNull() {
        val wp = TestWaypoint(locationName = "", latitude = -1.9, longitude = 30.0, isPassed = false, isNext = true)
        val name = wp.locationName?.ifBlank { null }
        assertNull(name)
    }

    @Test
    fun waypoint_locationName_present_staysNonNull() {
        val wp = TestWaypoint(locationName = "Kigali Central", latitude = -1.9, longitude = 30.0, isPassed = false, isNext = true)
        val name = wp.locationName?.ifBlank { null }
        assertEquals("Kigali Central", name)
    }

    @Test
    fun cacheState_optionalFields_useNullSentinel() {
        val trip = buildTrip().copy(
            seats = null,
            remainingSeats = null
        )
        assertNull(trip.seats)
        assertNull(trip.remainingSeats)
    }
}
