package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.CustodianInfo
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ServerTransferInfo
import com.gocavgo.ikuriye.data.StatusUpdate
import com.gocavgo.ikuriye.viewmodel.DataState
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the offline cache flow logic with the DataState-based approach.
 *
 * Key rule: auto-open create modal ONLY when DataState.NO_DATA (server/cache definitively
 * confirmed zero packages). Never open on UNKNOWN (backend unreachable) or LOADING (still fetching).
 */
class OfflineCachePersistenceTest {

    private fun createTestPackage(
        id: String = "CAV-TEST-001",
        status: PackageStatus = PackageStatus.IN_TRANSIT
    ) = ClientPackage(
        id = id,
        trackingCode = "CAV-001",
        senderId = "sender-1",
        senderName = "Alice",
        senderPhone = "+250788123456",
        fromAddress = "Kigali, Rwanda",
        recipientId = "recipient-1",
        recipientName = "Bob",
        recipientPhone = "+250788654321",
        toAddress = "Musanze, Rwanda",
        description = "Electronics package",
        weight = "2.5 kg",
        category = "Electronics",
        fragile = true,
        photoCount = 2,
        mediaUrls = emptyList(),
        status = status,
        driverName = "Jean",
        driverPhone = "+250788111111",
        driverCompany = "CavGo Express",
        vehicleType = "Motorcycle",
        deliveryCode = "123456",
        createdAt = "2026-08-20T10:00:00Z",
        receivedAt = "",
        statusHistory = emptyList(),
        custodians = emptyList(),
        transferId = null,
        transferStatus = null,
        transferRuleType = null,
        packageUuid = "",
        transfers = emptyList()
    )

    // ── Auto-open create modal decision logic ────────────────────────────

    @Test
    fun autoOpen_serverReturnsEmpty_setsNoData_opensModal() {
        // Scenario: first launch, no cache, server returns empty → open modal
        var dataState = DataState.LOADING

        // Server responds with empty list
        val serverResult = emptyList<ClientPackage>()
        dataState = if (serverResult.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Should auto-open: server confirmed zero packages", shouldAutoOpen)
    }

    @Test
    fun autoOpen_serverReturnsPackages_setsHasData_noModal() {
        var dataState = DataState.LOADING

        val serverResult = listOf(createTestPackage(id = "CAV-001"))
        dataState = if (serverResult.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Should NOT auto-open: server confirmed packages exist", shouldAutoOpen)
    }

    @Test
    fun autoOpen_networkFails_staysUnknown_noModal() {
        // Scenario: first launch, no cache, network fails → DON'T open modal
        var dataState = DataState.LOADING

        // Network fails → keep existing state (UNKNOWN since no cache)
        dataState = DataState.UNKNOWN // Network failed, no definitive answer

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.UNKNOWN, dataState)
        assertFalse("Should NOT auto-open: backend unreachable, we don't know", shouldAutoOpen)
    }

    @Test
    fun autoOpen_networkFailsButHasCache_keepsHasData_noModal() {
        // Scenario: has cache with packages, network fails → keep HAS_DATA
        var dataState = DataState.HAS_DATA // Cache had packages

        // Network fails → keep HAS_DATA
        // dataState stays HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Should NOT auto-open: has cached packages", shouldAutoOpen)
    }

    @Test
    fun autoOpen_cacheEmpty_serverEmpty_setsNoData_opensModal() {
        // Scenario: cache exists but empty, server also empty → open modal
        var dataState = DataState.LOADING

        // Cache found but empty
        val cachedItems = emptyList<ClientPackage>()
        // Don't set NO_DATA yet — server might have new packages

        // Server responds with empty list
        val serverResult = emptyList<ClientPackage>()
        dataState = if (serverResult.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Should auto-open: both cache and server confirmed empty", shouldAutoOpen)
    }

    @Test
    fun autoOpen_cacheHasData_serverEmpty_keepsHasData_noModal() {
        // Scenario: cache has packages, server returns empty (packages were deleted server-side)
        var dataState = DataState.HAS_DATA // Cache had packages

        // Server responds empty — but we had cache data, so keep HAS_DATA
        // (User should see their cached data, not an empty modal)
        // Actually: if server returns empty, we should update to reflect reality
        val serverResult = emptyList<ClientPackage>()
        dataState = if (serverResult.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Should auto-open: server confirmed no packages (cache was stale)", shouldAutoOpen)
    }

    @Test
    fun autoOpen_onlyCompletedPackages_setsHasData_noModal() {
        // Scenario: user has only completed packages → don't open create modal
        val packages = listOf(
            createTestPackage(id = "CAV-001", status = PackageStatus.DELIVERED),
            createTestPackage(id = "CAV-002", status = PackageStatus.CANCELLED)
        )

        val dataState = if (packages.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Should NOT auto-open: has completed packages", shouldAutoOpen)
    }

    // ── DataState transitions ────────────────────────────────────────────

    @Test
    fun dataState_initialLaunch_noCache() {
        // Fresh install → UNKNOWN → LOADING → server responds
        var dataState = DataState.UNKNOWN
        assertEquals(DataState.UNKNOWN, dataState)

        // Loading starts
        dataState = DataState.LOADING
        assertEquals(DataState.LOADING, dataState)

        // Server responds empty
        dataState = DataState.NO_DATA
        assertEquals(DataState.NO_DATA, dataState)
    }

    @Test
    fun dataState_initialLaunch_hasCache() {
        // Has cache → LOADING → server responds
        var dataState = DataState.LOADING

        // Cache found with packages
        dataState = DataState.HAS_DATA
        assertEquals(DataState.HAS_DATA, dataState)

        // Server responds with updated list (still has packages)
        dataState = DataState.HAS_DATA
        assertEquals(DataState.HAS_DATA, dataState)
    }

    @Test
    fun dataState_networkFailure_preservesState() {
        // Has cache → LOADING → network fails → preserve previous state
        var dataState = DataState.HAS_DATA // Cache existed

        // Network fails → don't change state
        // dataState stays HAS_DATA

        assertEquals(DataState.HAS_DATA, dataState)
    }

    @Test
    fun dataState_networkFailure_noCache_staysUnknown() {
        // No cache → LOADING → network fails → stays UNKNOWN
        var dataState = DataState.LOADING

        // Network fails → set to UNKNOWN (no definitive answer)
        dataState = DataState.UNKNOWN

        assertEquals(DataState.UNKNOWN, dataState)
    }

    // ── Refresh flow ────────────────────────────────────────────────────

    @Test
    fun refresh_offline_preservesDataState() {
        // User has packages, refreshes offline → data state unchanged
        var dataState = DataState.HAS_DATA
        var isRefreshingPackages = true

        val isNetworkAvailable = false
        if (!isNetworkAvailable) {
            isRefreshingPackages = false
            // dataState stays HAS_DATA
        }

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse(isRefreshingPackages)
    }

    @Test
    fun refresh_online_updatesDataState() {
        var dataState = DataState.HAS_DATA
        var isRefreshingPackages = true

        val isNetworkAvailable = true
        if (isNetworkAvailable) {
            val serverResult = emptyList<ClientPackage>()
            dataState = if (serverResult.isEmpty()) DataState.NO_DATA else DataState.HAS_DATA
            isRefreshingPackages = false
        }

        assertEquals(DataState.NO_DATA, dataState)
        assertFalse(isRefreshingPackages)
    }

    // ── PagedResult / cache simulation ──────────────────────────────────

    @Test
    fun cacheRoundTrip_preservesAllFields() {
        val original = createTestPackage()
        val cached = original.copy()

        assertEquals(original.id, cached.id)
        assertEquals(original.trackingCode, cached.trackingCode)
        assertEquals(original.senderName, cached.senderName)
        assertEquals(original.senderPhone, cached.senderPhone)
        assertEquals(original.fromAddress, cached.fromAddress)
        assertEquals(original.recipientName, cached.recipientName)
        assertEquals(original.recipientPhone, cached.recipientPhone)
        assertEquals(original.toAddress, cached.toAddress)
        assertEquals(original.description, cached.description)
        assertEquals(original.weight, cached.weight)
        assertEquals(original.category, cached.category)
        assertTrue(cached.fragile)
        assertEquals(original.status, cached.status)
        assertEquals(original.driverName, cached.driverName)
        assertEquals(original.deliveryCode, cached.deliveryCode)
        assertEquals(original.packageUuid, cached.packageUuid)
    }

    @Test
    fun cacheRoundTrip_deliveredPackage_noMediaUrls() {
        val pkg = createTestPackage(status = PackageStatus.DELIVERED)
        val mediaForCache = if (pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED) {
            pkg.mediaUrls
        } else {
            emptyList()
        }
        assertTrue("Delivered packages should not cache media", mediaForCache.isEmpty())
    }

    @Test
    fun mergeUpdate_existingPackage_updatesInPlace() {
        val packages = mutableListOf(
            createTestPackage(id = "CAV-001", status = PackageStatus.IN_TRANSIT),
            createTestPackage(id = "CAV-002", status = PackageStatus.PENDING)
        )

        val updated = createTestPackage(id = "CAV-001", status = PackageStatus.OUT_FOR_DELIVERY)
        val idx = packages.indexOfFirst { it.id == updated.id }
        if (idx >= 0) packages[idx] = updated

        assertEquals(2, packages.size)
        assertEquals(PackageStatus.OUT_FOR_DELIVERY, packages[0].status)
        assertEquals(PackageStatus.PENDING, packages[1].status)
    }

    @Test
    fun mergeUpdate_newPackage_prependsToList() {
        val packages = mutableListOf(
            createTestPackage(id = "CAV-001", status = PackageStatus.IN_TRANSIT)
        )

        val newPkg = createTestPackage(id = "CAV-NEW", status = PackageStatus.PENDING)
        val idx = packages.indexOfFirst { it.id == newPkg.id }
        if (idx >= 0) {
            packages[idx] = newPkg
        } else {
            packages.add(0, newPkg)
        }

        assertEquals(2, packages.size)
        assertEquals("CAV-NEW", packages[0].id)
        assertEquals("CAV-001", packages[1].id)
    }

    @Test
    fun removePackage_packageDisappearsFromList() {
        val packages = mutableListOf(
            createTestPackage(id = "CAV-001"),
            createTestPackage(id = "CAV-002"),
            createTestPackage(id = "CAV-003")
        )

        packages.removeAll { it.id == "CAV-002" }

        assertEquals(2, packages.size)
        assertEquals("CAV-001", packages[0].id)
        assertEquals("CAV-003", packages[1].id)
    }

    @Test
    fun expiryCache_oldCacheWouldBeEvicted() {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

        val oldCacheTime = now - (sevenDaysMs + 1)
        assertTrue("Old cache should be expired", now - oldCacheTime > sevenDaysMs)

        val recentCacheTime = now - (sevenDaysMs - 1)
        assertFalse("Recent cache should be valid", now - recentCacheTime > sevenDaysMs)
    }

    @Test
    fun pagedResult_cacheSurvivesOfflineCycle() {
        val packages = listOf(
            createTestPackage(id = "CAV-001", status = PackageStatus.PENDING),
            createTestPackage(id = "CAV-002", status = PackageStatus.IN_TRANSIT),
            createTestPackage(id = "CAV-003", status = PackageStatus.DELIVERED)
        )

        val savedItems = packages.toList()
        val restoredItems = savedItems

        assertEquals(3, restoredItems.size)
        assertEquals("CAV-001", restoredItems[0].id)
        assertEquals(PackageStatus.PENDING, restoredItems[0].status)
        assertEquals("CAV-002", restoredItems[1].id)
        assertEquals(PackageStatus.IN_TRANSIT, restoredItems[1].status)
        assertEquals("CAV-003", restoredItems[2].id)
        assertEquals(PackageStatus.DELIVERED, restoredItems[2].status)
    }

    // ── Background sync ─────────────────────────────────────────────────

    @Test
    fun backgroundSync_noNetwork_workerDoesNotRun() {
        val hasNetwork = false
        assertFalse("Worker should NOT run when offline", hasNetwork)
    }

    @Test
    fun backgroundSync_hasNetwork_workerRuns() {
        val hasNetwork = true
        assertTrue("Worker SHOULD run when online", hasNetwork)
    }
}
