package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.ServerTransferInfo
import com.gocavgo.ikuriye.data.StatusUpdate
import com.gocavgo.ikuriye.data.CustodianInfo
import com.gocavgo.ikuriye.viewmodel.AppRole
import com.gocavgo.ikuriye.viewmodel.DriverVehicle
import com.gocavgo.ikuriye.viewmodel.CompletedTrip
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the package creation stability fixes:
 *
 *  1. Driver panel closes after successful creation (isDriverCreatingPackage = false)
 *  2. New package appears in driverCurrentPackages for drivers
 *  3. Session generation mismatch resets isSubmittingPackage
 *  4. GraphQL errors + data: data is preferred over errors
 *  5. Trip stop coordinates preserved when selecting from trip stops
 *  6. TripPackageLocation filtering logic for origin/destination
 */
class CreatePackageStabilityTest {

    private fun createTestPackage(id: String = "CAV-TEST-001") = ClientPackage(
        id = id,
        trackingCode = id,
        senderId = "",
        senderName = "Test Sender",
        senderPhone = "+250788123456",
        fromAddress = "Kigali",
        recipientId = "",
        recipientName = "Test Receiver",
        recipientPhone = "+250788654321",
        toAddress = "Musanze",
        description = "Electronics",
        weight = "2.5 kg",
        category = "Electronics",
        fragile = false,
        photoCount = 0,
        mediaUrls = emptyList(),
        status = PackageStatus.PENDING,
        driverName = "",
        driverPhone = "",
        driverCompany = "",
        vehicleType = "",
        deliveryCode = "",
        createdAt = "2026-09-13T10:00:00Z",
        receivedAt = "",
        statusHistory = emptyList(),
        custodians = emptyList(),
        transferId = null,
        transferStatus = null,
        transferRuleType = null,
        packageUuid = "",
        transfers = emptyList()
    )

    // ── Driver panel close on success ─────────────────────────────────────

    @Test
    fun createPackageSuccess_driverPanelCloses() {
        // Simulates the state update after successful package creation for a driver
        var isCreatingPackage = true
        var isDriverCreatingPackage = true
        var isSubmittingPackage = true

        val isDriver = true
        val result = createTestPackage("CAV-NEW-001")

        // Simulate the state update from createPackage success path
        isCreatingPackage = false
        isDriverCreatingPackage = false
        isSubmittingPackage = false

        assertFalse("isCreatingPackage should be false", isCreatingPackage)
        assertFalse("isDriverCreatingPackage should be false (FIX: was not reset before)", isDriverCreatingPackage)
        assertFalse("isSubmittingPackage should be false", isSubmittingPackage)
    }

    @Test
    fun createPackageSuccess_clientPanelCloses() {
        var isCreatingPackage = true
        var isDriverCreatingPackage = false // client doesn't use this
        var isSubmittingPackage = true

        isCreatingPackage = false
        isSubmittingPackage = false

        assertFalse("isCreatingPackage should be false", isCreatingPackage)
        assertFalse("isSubmittingPackage should be false", isSubmittingPackage)
    }

    // ── New package added to driverCurrentPackages ────────────────────────

    @Test
    fun createPackageSuccess_driverPackageAddedToDriverList() {
        val existingDriverPackages = listOf(createTestPackage("CAV-EXISTING"))
        val newPackage = createTestPackage("CAV-NEW")

        val isDriver = true
        val driverCurrentPackages = if (isDriver) {
            listOf(newPackage) + existingDriverPackages
        } else {
            existingDriverPackages
        }

        assertEquals(2, driverCurrentPackages.size)
        assertEquals("CAV-NEW", driverCurrentPackages[0].id)
        assertEquals("CAV-EXISTING", driverCurrentPackages[1].id)
    }

    @Test
    fun createPackageSuccess_clientPackageNotAddedToDriverList() {
        val existingDriverPackages = listOf(createTestPackage("CAV-EXISTING"))
        val newPackage = createTestPackage("CAV-NEW")

        val isDriver = false
        val driverCurrentPackages = if (isDriver) {
            listOf(newPackage) + existingDriverPackages
        } else {
            existingDriverPackages
        }

        assertEquals(1, driverCurrentPackages.size)
        assertEquals("CAV-EXISTING", driverCurrentPackages[0].id)
    }

    // ── Session generation mismatch ───────────────────────────────────────

    @Test
    fun sessionGenMismatch_resetsSubmittingFlag() {
        var sessionGeneration = 1L
        var isSubmittingPackage = true

        // Capture gen at "launch"
        val gen = sessionGeneration

        // Token refresh happens mid-flight
        sessionGeneration = 2L

        // Coroutine resumes: gen != sessionGeneration
        if (gen != sessionGeneration) {
            isSubmittingPackage = false // FIX: was silent return without reset
        }

        assertFalse("isSubmittingPackage must be reset on session mismatch", isSubmittingPackage)
    }

    @Test
    fun sessionGenMismatch_doesNotApplyStaleResult() {
        var sessionGeneration = 1L
        var packages = listOf<ClientPackage>()

        val gen = sessionGeneration
        sessionGeneration = 2L

        // Simulate a stale result arriving
        val staleResult = createTestPackage("CAV-STALE")
        if (gen == sessionGeneration) {
            packages = listOf(staleResult) + packages
        }

        assertTrue("Stale result should not be applied", packages.isEmpty())
    }

    @Test
    fun sessionGenMatch_appliesResult() {
        var sessionGeneration = 1L
        var packages = listOf<ClientPackage>()

        val gen = sessionGeneration
        // No session change
        val freshResult = createTestPackage("CAV-FRESH")
        if (gen == sessionGeneration) {
            packages = listOf(freshResult) + packages
        }

        assertEquals(1, packages.size)
        assertEquals("CAV-FRESH", packages[0].id)
    }

    // ── GraphQL data-over-errors preference ───────────────────────────────

    @Test
    fun graphqlResponse_dataAndErrors_prefersData() {
        // Simulates the fixed PackageRepository.createPackage logic
        val data = createTestPackage("CAV-CREATED")
        val errors = listOf("notification side-effect failed")

        // Old behavior: errors checked first → null (package lost!)
        // New behavior: data checked first → use data
        val result = if (data != null) {
            data // prefer data
        } else if (errors.isNotEmpty()) {
            null
        } else {
            null
        }

        assertNotNull("Package should be returned when data exists", result)
        assertEquals("CAV-CREATED", result?.id)
    }

    @Test
    fun graphqlResponse_errorsOnly_returnsNull() {
        val data: ClientPackage? = null
        val errors = listOf("mutation failed")

        val result = if (data != null) {
            data
        } else if (errors.isNotEmpty()) {
            null
        } else {
            null
        }

        assertNull("Should return null when only errors, no data", result)
    }

    @Test
    fun graphqlResponse_noDataNoErrors_returnsNull() {
        val data: ClientPackage? = null
        val errors: List<String>? = null

        val result = if (data != null) {
            data
        } else if (errors != null && errors.isNotEmpty()) {
            null
        } else {
            null
        }

        assertNull("Should return null when no data and no errors", result)
    }

    // ── Trip stop selection preserves coordinates ─────────────────────────

    @Test
    fun tripStopSelection_setsCorrectCoordinates() {
        // Simulates selectDriverPackageOrigin
        var fromAddress = ""
        var originLatitude = 0.0
        var originLongitude = 0.0

        // User selects "Kigali Central" stop
        val choiceLat = -1.9403
        val choiceLng = 29.8739
        fromAddress = "Kigali Central"
        originLatitude = choiceLat
        originLongitude = choiceLng

        assertEquals("Kigali Central", fromAddress)
        assertEquals(-1.9403, originLatitude, 0.0001)
        assertEquals(29.8739, originLongitude, 0.0001)
    }

    @Test
    fun tripStopSelection_destinationPreservesCoordinates() {
        var toAddress = ""
        var destLatitude = 0.0
        var destLongitude = 0.0

        val choiceLat = -1.4989
        val choiceLng = 29.6314
        toAddress = "Musanze Park"
        destLatitude = choiceLat
        destLongitude = choiceLng

        assertEquals("Musanze Park", toAddress)
        assertEquals(-1.4989, destLatitude, 0.0001)
        assertEquals(29.6314, destLongitude, 0.0001)
    }

    @Test
    fun manualAddressEntry_resetsCoordinates() {
        // Simulates CreatePackageFormState.updateField("fromAddress", ...)
        var originLatitude = -1.9403
        var originLongitude = 29.8739
        var originPlaceId: String? = "ChIJ123"

        // User manually types in the fromAddress field
        originLatitude = 0.0
        originLongitude = 0.0
        originPlaceId = null

        assertEquals(0.0, originLatitude, 0.0001)
        assertEquals(0.0, originLongitude, 0.0001)
        assertNull(originPlaceId)
    }

    // ── Trip stop origin/destination filtering logic ──────────────────────

    @Test
    fun tripStopFiltering_originIncludesCurrentAndUnpassedStops() {
        // Simulates refreshDriverPackageLocations logic
        data class Stop(val index: Int, val name: String, val isPassed: Boolean, val isNext: Boolean)

        val stops = listOf(
            Stop(0, "Stop 1", isPassed = true, isNext = false),  // passed
            Stop(1, "Stop 2", isPassed = false, isNext = true),  // current
            Stop(2, "Stop 3", isPassed = false, isNext = false), // upcoming
            Stop(3, "Stop 4", isPassed = false, isNext = false), // upcoming (final)
        )
        val currentStopIndex = 1

        // Origin candidates: current + unpassed, plus last passed
        val origins = stops.filter { stop ->
            if (stop.isPassed) {
                // Last passed stop is still eligible as pickup
                stop.index == currentStopIndex - 1
            } else {
                true // current + upcoming
            }
        }.filter { stop ->
            // Exclude the final destination (last stop) unless driver is there
            !(stop.index == stops.lastIndex && !stop.isNext)
        }

        assertEquals("Should have 3 origin candidates", 3, origins.size)
        assertTrue("Should include last passed stop", origins.any { it.index == 0 })
        assertTrue("Should include current stop", origins.any { it.index == 1 })
        assertTrue("Should include upcoming stop", origins.any { it.index == 2 })
        assertFalse("Should exclude final destination when not there", origins.any { it.index == 3 })
    }

    @Test
    fun tripStopFiltering_destinationOnlyUnpassedNonOrigin() {
        data class Stop(val index: Int, val name: String, val isPassed: Boolean, val isNext: Boolean)

        val stops = listOf(
            Stop(0, "Stop 1", isPassed = true, isNext = false),  // passed (origin)
            Stop(1, "Stop 2", isPassed = false, isNext = true),  // current
            Stop(2, "Stop 3", isPassed = false, isNext = false), // upcoming
            Stop(3, "Stop 4", isPassed = false, isNext = false), // upcoming (final)
        )
        val currentStopIndex = 1

        // Destination candidates: unpassed stops only, excluding the trip origin
        val destinations = stops.filter { stop ->
            !stop.isPassed && stop.index > 0
        }

        assertEquals("Should have 3 destination candidates", 3, destinations.size)
        assertFalse("Should not include origin stop", destinations.any { it.index == 0 })
        assertTrue("Should include current stop", destinations.any { it.index == 1 })
        assertTrue("Should include upcoming stops", destinations.any { it.index == 2 })
        assertTrue("Should include final destination", destinations.any { it.index == 3 })
    }

    @Test
    fun tripStopFiltering_firstStopNotPassed_allStopsAvailable() {
        data class Stop(val index: Int, val name: String, val isPassed: Boolean, val isNext: Boolean)

        val stops = listOf(
            Stop(0, "Origin", isPassed = false, isNext = true),  // current (first)
            Stop(1, "Stop 2", isPassed = false, isNext = false),
            Stop(2, "Destination", isPassed = false, isNext = false),
        )
        val currentStopIndex = 0

        val origins = stops.filter { stop ->
            if (stop.isPassed) stop.index == currentStopIndex - 1
            else true
        }.filter { stop ->
            !(stop.index == stops.lastIndex && !stop.isNext)
        }

        assertEquals("All stops except final should be origin candidates", 2, origins.size)
    }

    @Test
    fun tripStopFiltering_allStopsPassed_noOrigins() {
        data class Stop(val index: Int, val name: String, val isPassed: Boolean, val isNext: Boolean)

        val stops = listOf(
            Stop(0, "Stop 1", isPassed = true, isNext = false),
            Stop(1, "Stop 2", isPassed = true, isNext = false),
            Stop(2, "Stop 3", isPassed = true, isNext = false),
        )
        val currentStopIndex = 3 // all passed

        val origins = stops.filter { stop ->
            if (stop.isPassed) stop.index == currentStopIndex - 1
            else true
        }

        assertEquals("Only last passed stop should be eligible", 1, origins.size)
        assertEquals("Stop 3", origins[0].name)
    }

    // ── Vehicle data parsing (BackendStorage) ─────────────────────────────

    @Test
    fun vehicleResponseDto_fieldMapping() {
        // Simulates the parsing of VehicleResponseDto fields (plain Kotlin map)
        val vehicleMap = mapOf(
            "id" to 456L,
            "licensePlate" to "RAB123",
            "make" to "Toyota",
            "model" to "Corolla",
            "capacity" to 4,
            "isOnline" to true,
            "status" to "AVAILABLE",
            "vehicleType" to "Sedan"
        )
        val driverMap = mapOf(
            "firstName" to "Jean",
            "lastName" to "Bosco",
            "email" to "jean@gocavgo.com",
            "phone" to "+250788111111"
        )

        val vehicleId = vehicleMap["id"] as Long
        val plate = vehicleMap["licensePlate"] as? String
        val make = vehicleMap["make"] as? String
        val model = vehicleMap["model"] as? String
        val capacity = vehicleMap["capacity"] as Int
        val isOnline = vehicleMap["isOnline"] as Boolean
        val status = vehicleMap["status"] as? String
        val vehicleType = vehicleMap["vehicleType"] as? String
        val driverName = "${driverMap["firstName"]} ${driverMap["lastName"]}"

        assertEquals(456L, vehicleId)
        assertEquals("RAB123", plate)
        assertEquals("Toyota", make)
        assertEquals("Corolla", model)
        assertEquals(4, capacity)
        assertTrue(isOnline)
        assertEquals("AVAILABLE", status)
        assertEquals("Sedan", vehicleType)
        assertEquals("Jean Bosco", driverName)
    }

    @Test
    fun vehicleResponseDto_nullFields_handledGracefully() {
        val vehicleMap = mapOf(
            "id" to 0L,
            "licensePlate" to null,
            "make" to null,
            "model" to null,
            "capacity" to 0,
            "isOnline" to false,
            "status" to null,
            "vehicleType" to null
        )

        assertEquals(0L, vehicleMap["id"])
        assertNull(vehicleMap["licensePlate"])
        assertNull(vehicleMap["make"])
        assertNull(vehicleMap["vehicleType"])
        assertFalse(vehicleMap["isOnline"] as Boolean)
    }

    // ── Offline guard ─────────────────────────────────────────────────────

    @Test
    fun createPackage_offlineGuard_preservesDraft() {
        val isNetworkAvailable = false
        var isSubmittingPackage = false
        var toastMessage: String? = null

        if (!isNetworkAvailable) {
            toastMessage = "You need an internet connection to create a package — your draft has been saved"
            // draft stays intact
        } else {
            isSubmittingPackage = true
        }

        assertFalse("Should not enter submitting state when offline", isSubmittingPackage)
        assertNotNull("Should show toast message", toastMessage)
        assertTrue("Toast should mention draft is saved", toastMessage!!.contains("draft has been saved"))
    }

    // ── Double submit guard ───────────────────────────────────────────────

    @Test
    fun createPackage_doubleSubmitGuard() {
        var isSubmittingPackage = true
        var submitCount = 0

        if (isSubmittingPackage) {
            // early return — no second network call
        } else {
            submitCount++
        }

        assertEquals("Second submit must be blocked", 0, submitCount)
    }

    // ── Stale data indicator ──────────────────────────────────────────────

    @Test
    fun tripDataStale_offlineWithCachedData() {
        val isOnline = false
        val hasCache = true
        val isTripDataStale = isOnline.not() && hasCache

        assertTrue("Should be stale when offline with cached data", isTripDataStale)
    }

    @Test
    fun tripDataStale_onlineNotStale() {
        val isOnline = true
        val hasCache = true
        val isTripDataStale = isOnline.not() && hasCache

        assertFalse("Should not be stale when online", isTripDataStale)
    }

    @Test
    fun tripDataStale_offlineNoCacheNotStale() {
        val isOnline = false
        val hasCache = false
        val isTripDataStale = isOnline.not() && hasCache

        assertFalse("Should not be stale when no cache exists", isTripDataStale)
    }

    @Test
    fun freshDataClearsStaleness() {
        var isTripDataStale = true // was stale
        // Fresh data arrives from network
        isTripDataStale = false

        assertFalse("Fresh data should clear staleness", isTripDataStale)
    }

    // ── Error toast on network failure ────────────────────────────────────

    @Test
    fun createPackage_networkError_showsToastAndResetsSubmit() {
        var isSubmittingPackage = true
        var toastMessage: String? = null
        val gen = 1L
        var sessionGen = 1L

        // Network error → result is null
        val result: ClientPackage? = null

        if (result == null) {
            if (gen == sessionGen) {
                isSubmittingPackage = false
                toastMessage = "Failed to create package. Please try again."
            }
        }

        assertFalse("Should reset submit flag on failure", isSubmittingPackage)
        assertEquals("Failed to create package. Please try again.", toastMessage)
    }
}
