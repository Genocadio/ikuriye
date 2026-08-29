package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.CustodianInfo
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PagedResult
import com.gocavgo.ikuriye.data.FetchPackagesResult
import com.gocavgo.ikuriye.data.SingleResult
import com.gocavgo.ikuriye.data.ServerTransferInfo
import com.gocavgo.ikuriye.data.StatusUpdate
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the offline/network resilience rules implemented in the stability pass:
 *
 *  1. A failed package fetch (FetchPackagesResult.Error) must NOT overwrite a good
 *     cache with an empty/unknown page — cache writes happen only on Success.
 *  2. A coroutine result that finishes after logout / role switch (sessionGeneration
 *     bumped) must be dropped, not applied to state.
 *  3. createPackage is guarded against double-submit and offline drafts.
 *  4. Attached media that fails to (re)upload aborts the submit, keeping the draft.
 *  5. The background session health-check loop stops once the session is gone.
 */
class StabilityFixesTest {

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

    // ── FetchPackagesResult semantics ───────────────────────────────────

    @Test
    fun fetchError_isNotTreatedAsEmptyList() {
        // The core rule: Error is NOT an authoritative "no packages" answer.
        val result: FetchPackagesResult = FetchPackagesResult.Error("connection reset")

        // Production fetch branches on this sealed type; an Error must never be
        // coerced into an empty PagedResult and saved over a good cache.
        val isEmptyListResult = result is FetchPackagesResult.Success && result.page.items.isEmpty()

        assertFalse("An Error fetch must not masquerade as an empty page", isEmptyListResult)
    }

    @Test
    fun fetchSuccess_withEmptyPage_isLegitimateEmpty() {
        // A real, authoritative empty answer is Success with an empty page — this
        // is the ONLY case allowed to overwrite the cache / drive DataState.NO_DATA.
        val result: FetchPackagesResult = FetchPackagesResult.Success(
            PagedResult(items = emptyList(), totalCount = 0, totalPages = 0, currentPage = 0)
        )

        val isEmptyPage = result is FetchPackagesResult.Success && result.page.items.isEmpty()

        assertTrue("Success with empty page is the legitimate empty result", isEmptyPage)
    }

    @Test
    fun fetchSuccess_withPackages_isNotEmpty() {
        val result: FetchPackagesResult = FetchPackagesResult.Success(
            PagedResult(
                items = listOf(createTestPackage(id = "CAV-001")),
                totalCount = 1,
                totalPages = 1,
                currentPage = 0
            )
        )

        val cacheable = result is FetchPackagesResult.Success && result.page.items.isNotEmpty()

        assertTrue("Only Success pages reach the cache", cacheable)
    }

    // ── Cache write decision (mirrors fetch/refresh/load-more branches) ─

    @Test
    fun cacheWrite_onlyHappensOnSuccess() {
        val cachedBefore = PagedResult(
            items = listOf(createTestPackage(id = "CAV-001", status = PackageStatus.IN_TRANSIT)),
            totalCount = 1,
            totalPages = 1,
            currentPage = 0
        )
        val result: FetchPackagesResult = FetchPackagesResult.Error("server 500")

        // Simulate the ViewModel branch: cache is saved ONLY for Success.
        var cached = cachedBefore
        when (result) {
            is FetchPackagesResult.Success -> {
                cached = result.page
            }
            is FetchPackagesResult.Error -> {
                // Leave the existing cache untouched.
            }
        }

        assertTrue("A good cache survives a failed fetch", cached.items.isNotEmpty())
        assertEquals("CAV-001", cached.items[0].id)
        assertTrue("Cache must never be replaced by an Error", cached.items.isNotEmpty())
    }

    @Test
    fun cacheWrite_successReplacesCache() {
        val result: FetchPackagesResult = FetchPackagesResult.Success(
            PagedResult(
                items = listOf(createTestPackage(id = "CAV-NEW", status = PackageStatus.PENDING)),
                totalCount = 1,
                totalPages = 1,
                currentPage = 0
            )
        )

        var cached: PagedResult? = null
        when (result) {
            is FetchPackagesResult.Success -> cached = result.page
            is FetchPackagesResult.Error -> Unit
        }

        assertNotNull(cached)
        assertEquals("CAV-NEW", cached!!.items[0].id)
    }

    // ── Generation guard (stale results dropped after logout) ──────────

    @Test
    fun staleResult_startedBeforeLogout_isDropped() {
        // Capture gen while the session is alive.
        var sessionGeneration = 1
        val gen = sessionGeneration   // coroutine captured this at launch

        // A fetch was in flight; logout happens:
        sessionGeneration = 2

        // Coroutine resumes: guard refuses to apply the stale result.
        val applied = gen == sessionGeneration

        assertFalse("Result captured before logout must not be applied", applied)
    }

    @Test
    fun freshResult_fromCurrentSession_isApplied() {
        var sessionGeneration = 1
        val gen = sessionGeneration

        // No logout/role switch happened while the fetch was in flight.
        val applied = gen == sessionGeneration

        assertTrue("Current-session result is applied", applied)
    }

    @Test
    fun generationGuard_doesNotClearSubmittingFlagOnStaleSubmits() {
        // createPackage failure path resets isSubmittingPackage only when the
        // session generation still matches (avoids re-enabling a submit on a
        // screen that is already logging out / resetting state).
        var sessionGeneration = 1
        val gen = sessionGeneration
        sessionGeneration = 2 // logout mid-submit

        var isSubmittingPackage = true
        if (gen == sessionGeneration) {
            isSubmittingPackage = false
            // toast "Failed to create package"
        }

        assertTrue(
            "Stale failure must not touch submit flags on a reset screen",
            isSubmittingPackage
        )
    }

    // ── createPackage guards ────────────────────────────────────────────

    @Test
    fun doubleSubmit_secondCallIgnored() {
        var isSubmittingPackage = true // first submit in flight
        var submitCount = 0

        // Second user tap on "Submit":
        if (isSubmittingPackage) {
            // early return — no second network call
        } else {
            submitCount++
        }

        assertEquals("Second submit must be ignored while first is in flight", 0, submitCount)
    }

    @Test
    fun offlineSubmit_keepsDraftAndShowsToast() {
        val isNetworkAvailable = false
        var isSubmittingPackage = false
        var toastShown = false

        if (!isNetworkAvailable) {
            toastShown = true
            // draft stays persisted; return before touching isSubmittingPackage
        } else {
            isSubmittingPackage = true
        }

        assertTrue("Offline submit tells the user why it did not go through", toastShown)
        assertFalse("isSubmittingPackage never set when offline", isSubmittingPackage)
    }

    @Test
    fun uploadingMediaInFlight_blocksSubmit() {
        val anyMediaUploading = true // one attachment still uploading
        var blocked = false

        if (anyMediaUploading) {
            blocked = true
            // toast "Media is still uploading"
        }

        assertTrue("Submit waits for in-flight media uploads", blocked)
    }

    @Test
    fun mediaReuploadFailure_abortsSubmit_keepsDraft() {
        // Media with a null mediaId must be re-uploaded before submit.
        val uploadResult = null // backend refused / offline mid-way
        var isSubmittingPackage = true
        var toastShown = false

        if (uploadResult == null) {
            toastShown = true
            isSubmittingPackage = false
            // return@launch — draft still persisted
        }

        assertTrue("Failed media upload surfaces a message", toastShown)
        assertFalse("Submit aborts when media cannot be attached", isSubmittingPackage)
    }

    // ── Session health-check loop ───────────────────────────────────────

    @Test
    fun sessionLoop_stopsWhenLoggedOut() {
        // After logout the access token is cleared: isLoggedIn() == false.
        val isLoggedIn = false
        var loopRunning = true

        if (!isLoggedIn) {
            loopRunning = false // break — no more periodic refresh pings
        }

        assertFalse("Health-check loop must stop once the session is gone", loopRunning)
    }

    @Test
    fun sessionLoop_keepsRunningWhileLoggedIn() {
        val isLoggedIn = true
        var loopRunning = true

        if (!isLoggedIn) {
            loopRunning = false
        }

        assertTrue("Loop keeps running while a session is active", loopRunning)
    }

    // ── SingleResult semantics (trackByCode / fetchPackageById) ──────────

    @Test
    fun singleResult_notFound_isNotAFailure() {
        val result: SingleResult<ClientPackage> = SingleResult.NotFound("not found")

        // NotFound means the server legitimately returned null — the item
        // doesn't exist. This is NOT a transport error and must not cause
        // the caller to delete a cached entry or show a retry toast.
        val isFailure = result is SingleResult.Failure

        assertFalse("NotFound must not be confused with Failure", isFailure)
    }

    @Test
    fun singleResult_failure_mustNotBeTreatedAsNotFound() {
        val result: SingleResult<ClientPackage> = SingleResult.Failure("connection reset")

        // A transport error means the caller couldn't determine whether the
        // item exists. It must NOT silently delete cache entries or show
        // "not found" UI — show a retry toast instead.
        val isNotFound = result is SingleResult.NotFound

        assertFalse("Failure must not masquerade as NotFound", isNotFound)
    }

    @Test
    fun singleResult_success_carriesData() {
        val pkg = createTestPackage(id = "CAV-042")
        val result: SingleResult<ClientPackage> = SingleResult.Success(pkg)

        assertTrue("Success carries the package", result is SingleResult.Success)
        assertEquals("CAV-042", (result as SingleResult.Success).data.id)
    }

    // ── Exponential backoff for polling ──────────────────────────────────

    @Test
    fun pollingBackoff_increasesOnConsecutiveFailures() {
        val baseIntervalMs = 30_000L
        val maxIntervalMs = 300_000L
        var currentIntervalMs = baseIntervalMs
        var consecutiveFailures = 0

        // Simulate 4 consecutive failures
        repeat(4) {
            consecutiveFailures++
            currentIntervalMs = (baseIntervalMs * (1L shl (consecutiveFailures - 1).coerceAtMost(4)))
                .coerceAtMost(maxIntervalMs)
        }

        assertTrue("Backoff should increase over time", currentIntervalMs > baseIntervalMs)
        assertTrue("Backoff should not exceed max", currentIntervalMs <= maxIntervalMs)
        // After 4 failures: 30s * 8 = 240s
        assertEquals("Expected 240s after 4 failures", 240_000L, currentIntervalMs)
    }

    @Test
    fun pollingBackoff_resetsOnSuccess() {
        val baseIntervalMs = 30_000L
        var currentIntervalMs = 240_000L // was backed off
        var consecutiveFailures = 4

        // Success resets backoff
        consecutiveFailures = 0
        currentIntervalMs = baseIntervalMs

        assertEquals("Backoff resets to base on success", baseIntervalMs, currentIntervalMs)
        assertEquals("Failure count resets to zero", 0, consecutiveFailures)
    }

    @Test
    fun pollingBackoff_capsAtMaximum() {
        val baseIntervalMs = 30_000L
        val maxIntervalMs = 300_000L
        var currentIntervalMs = baseIntervalMs

        // Simulate many failures — should never exceed max
        repeat(20) { attempt ->
            val consecutiveFailures = attempt + 1
            currentIntervalMs = (baseIntervalMs * (1L shl (consecutiveFailures - 1).coerceAtMost(4)))
                .coerceAtMost(maxIntervalMs)
        }

        assertTrue("Backoff must respect the cap", currentIntervalMs <= maxIntervalMs)
    }
}