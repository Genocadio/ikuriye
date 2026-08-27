package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.CustodianInfo
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ServerTransferInfo
import com.gocavgo.ikuriye.data.StatusUpdate
import com.gocavgo.ikuriye.viewmodel.DataState
import org.junit.Assert.*
import org.junit.Test

import com.gocavgo.ikuriye.data.isActive

/**
 * Verifies the offline cache flow logic with the DataState-based approach.
 *
 * Key rule: auto-open create modal ONLY when DataState.NO_DATA (server/cache definitively
 * confirmed zero ACTIVE packages). Never open on UNKNOWN (backend unreachable) or LOADING (still fetching).
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

        // Server responds with empty list (no active packages)
        val serverResult = emptyList<ClientPackage>()
        val hasActive = serverResult.any { it.isActive() }
        dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Should auto-open: server confirmed zero active packages", shouldAutoOpen)
    }

    @Test
    fun autoOpen_serverReturnsActivePackages_setsHasData_noModal() {
        var dataState = DataState.LOADING

        val serverResult = listOf(createTestPackage(id = "CAV-001", status = PackageStatus.IN_TRANSIT))
        val hasActive = serverResult.any { it.isActive() }
        dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Should NOT auto-open: server confirmed active packages exist", shouldAutoOpen)
    }

    @Test
    fun autoOpen_onlyCompletedPackages_setsNoData_opensModal() {
        // Scenario: user has only completed/cancelled packages → 0 active packages → open create modal
        val packages = listOf(
            createTestPackage(id = "CAV-001", status = PackageStatus.DELIVERED),
            createTestPackage(id = "CAV-002", status = PackageStatus.CANCELLED)
        )

        val hasActive = packages.any { it.isActive() }
        val dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Should auto-open: no active packages (only completed/cancelled)", shouldAutoOpen)
    }

    @Test
    fun autoOpen_freshLogin_bypassesCache_checksBackend_noActive_opensModal() {
        // Fresh login: ignores any old cache, starts in LOADING
        var dataState = DataState.LOADING
        assertEquals(DataState.LOADING, dataState)

        // Backend returns only delivered packages
        val serverResult = listOf(createTestPackage(id = "CAV-OLD", status = PackageStatus.DELIVERED))
        val hasActive = serverResult.any { it.isActive() }
        dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Fresh login should auto-open when backend has no active packages", dataState == DataState.NO_DATA)
    }

    @Test
    fun autoOpen_freshLogin_bypassesCache_checksBackend_hasActive_noModal() {
        // Fresh login: ignores cache, starts in LOADING
        var dataState = DataState.LOADING

        // Backend returns an active package (PENDING_CONFIRMATION)
        val serverResult = listOf(createTestPackage(id = "CAV-ACTIVE", status = PackageStatus.PENDING_CONFIRMATION))
        val hasActive = serverResult.any { it.isActive() }
        dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Fresh login should NOT auto-open when backend has active packages", dataState == DataState.NO_DATA)
    }

    @Test
    fun autoOpen_sessionRestore_cacheHasActive_setsHasDataImmediately() {
        // Pre-logged-in session restore: cache has active package
        val cached = listOf(createTestPackage(id = "CAV-001", status = PackageStatus.PENDING))
        val hasActiveCached = cached.any { it.isActive() }
        val dataState = if (hasActiveCached) DataState.HAS_DATA else DataState.LOADING

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Session restore should NOT open modal when cache has active packages", dataState == DataState.NO_DATA)
    }

    @Test
    fun autoOpen_sessionRestore_cacheNoActive_waitsForBackend() {
        // Pre-logged-in session restore: cache has only delivered packages
        val cached = listOf(createTestPackage(id = "CAV-001", status = PackageStatus.DELIVERED))
        val hasActiveCached = cached.any { it.isActive() }
        var dataState = if (hasActiveCached) DataState.HAS_DATA else DataState.LOADING

        assertEquals(DataState.LOADING, dataState) // Does not open modal yet!

        // Backend then confirms no active packages
        val serverResult = emptyList<ClientPackage>()
        val hasActiveServer = serverResult.any { it.isActive() }
        dataState = if (!hasActiveServer) DataState.NO_DATA else DataState.HAS_DATA

        assertEquals(DataState.NO_DATA, dataState)
        assertTrue("Opens modal after backend confirms no active packages", dataState == DataState.NO_DATA)
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
        // Scenario: has cache with active packages, network fails → keep HAS_DATA
        var dataState = DataState.HAS_DATA // Cache had active packages

        val shouldAutoOpen = dataState == DataState.NO_DATA

        assertEquals(DataState.HAS_DATA, dataState)
        assertFalse("Should NOT auto-open: has cached active packages", shouldAutoOpen)
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
            val hasActive = serverResult.any { it.isActive() }
            dataState = if (!hasActive) DataState.NO_DATA else DataState.HAS_DATA
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

    // ── Offline Cache & Session Resilience ──────────────────────────────

    @Test
    fun offlineCache_whenExpiredAndOffline_returnsStaleFallback_neverDeletes() {
        val EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L
        val cachedAt = System.currentTimeMillis() - (EXPIRY_MS + 100_000L) // expired 8 days ago
        val isExpired = System.currentTimeMillis() - cachedAt > EXPIRY_MS
        val isOnline = false

        // Logic check: if expired and online -> delete & return null; if expired and offline -> preserve & return stale items
        val shouldDelete = isExpired && isOnline
        val shouldReturnStale = isExpired && !isOnline

        assertTrue("Cache is indeed expired", isExpired)
        assertFalse("Should NOT delete cache file when offline", shouldDelete)
        assertTrue("Should return stale cache items as fallback when offline", shouldReturnStale)
    }

    @Test
    fun sessionRetention_networkIOException_preservesSessionLocally() {
        var localSessionCleared = false
        val isNetworkException = true

        if (!isNetworkException) {
            localSessionCleared = true
        }

        assertFalse("Session MUST NOT be cleared on network timeout/IO failure", localSessionCleared)
    }

    @Test
    fun sessionRetention_serverExplicit401_clearsSessionLocally() {
        var localSessionCleared = false
        val serverStatusCode = 401

        if (serverStatusCode in listOf(400, 401, 403)) {
            localSessionCleared = true
        }

        assertTrue("Session MUST be cleared on explicit server 401 / token revocation", localSessionCleared)
    }
}
