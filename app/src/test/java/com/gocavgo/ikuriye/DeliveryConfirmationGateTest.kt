package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.Notice
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.shouldAutoShowDeliveryConfirmation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the "confirm delivery → kill app → reopen" bug at the logic layer:
 * the auto delivery-confirmation popup must show exactly once per notice and
 * never reappear after a restart — regardless of which persistence layer
 * survives (SharedPreferences, server-side read_at, or the package cache).
 */
class DeliveryConfirmationGateTest {

    private fun deliveryNotice(
        viewerNoticeId: String = "vn-1",
        viewerReadAt: String? = null,
        eventType: String = "PACKAGE_DELIVERY_INITIATED",
    ) = Notice(
        id = "n-1",
        resourceType = "PACKAGE",
        resourceId = "pkg-1",
        eventType = eventType,
        actorId = null,
        title = "Delivery initiated",
        message = "Enter the code to confirm",
        payload = """{"deliveryCode":"123456","trackingCode":"CAV-001"}""",
        viewerId = "v-1",
        viewerNoticeId = viewerNoticeId,
        viewerReadAt = viewerReadAt,
        createdAt = "2026-08-12T10:00:00Z"
    )

    @Test
    fun freshUnreadNotice_packagePendingConfirmation_shows() {
        // Baseline — the popup must still work when a delivery notice first arrives.
        assertTrue(
            shouldAutoShowDeliveryConfirmation(
                notice = deliveryNotice(),
                autoShownNoticeIds = emptySet(),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )
    }

    @Test
    fun afterConfirm_restartWithPersistedAutoShownId_suppressed() {
        // Auto-shown ids are persisted to SharedPreferences and re-seeded on
        // launch, so the popup must not reappear after an app restart.
        val notice = deliveryNotice()
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice = notice,
                autoShownNoticeIds = setOf(notice.viewerNoticeId),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )
    }

    @Test
    fun afterConfirm_restartWithServerReadAt_suppressed() {
        // Even if SharedPreferences is wiped, markNoticeRead persists read_at
        // server-side; the next fetch returns viewerReadAt and suppresses the popup.
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice = deliveryNotice(viewerReadAt = "2026-08-12T10:05:00Z"),
                autoShownNoticeIds = emptySet(),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )
    }

    @Test
    fun readNoticeAndPersistedAutoShownId_suppressed() {
        // The realistic post-confirm state: the notice was both marked read
        // server-side (viewerReadAt) AND recorded in the persisted auto-shown
        // set. Either signal alone suppresses; both together must too.
        val notice = deliveryNotice(viewerReadAt = "2026-08-12T10:05:00Z")
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice = notice,
                autoShownNoticeIds = setOf(notice.viewerNoticeId),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )

        // Per-notice scoping: a different notice for another package, still
        // unread and not in the auto-shown set, must still pop — suppression
        // must never leak from one notice onto another.
        val otherNotice = deliveryNotice(viewerNoticeId = "vn-2")
        assertTrue(
            shouldAutoShowDeliveryConfirmation(
                notice = otherNotice,
                autoShownNoticeIds = setOf(notice.viewerNoticeId),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )
    }

    @Test
    fun afterConfirm_restartWithDeliveredCache_suppressed() {
        // Even if neither the pref nor the read-state survives, confirmDeliveryFromCode
        // now saves DELIVERED to PackageCache, so the warm-up cache guard holds.
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice = deliveryNotice(),
                autoShownNoticeIds = emptySet(),
                packageStatus = PackageStatus.DELIVERED
            )
        )
    }

    @Test
    fun fullConfirmKillReopenFlow_showsOnceThenNeverAgain() {
        // 1. Notice arrives fresh → popup shows.
        val notice = deliveryNotice()
        assertTrue(
            shouldAutoShowDeliveryConfirmation(
                notice, emptySet(), PackageStatus.PENDING_CONFIRMATION
            )
        )

        // 2. User confirms delivery → auto-shown id persisted, read_at set
        //    server-side, and DELIVERED saved to the package cache.
        val afterConfirm = setOf(notice.viewerNoticeId)
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice.copy(viewerReadAt = "2026-08-12T10:05:00Z"),
                afterConfirm,
                PackageStatus.DELIVERED
            )
        )

        // 3. "Restart": process memory is gone; each source is re-read
        //    independently (prefs, GraphQL fetch, cache). Every single guard
        //    alone must be sufficient to keep the popup suppressed.
        assertFalse(
            shouldAutoShowDeliveryConfirmation(notice, afterConfirm, PackageStatus.PENDING_CONFIRMATION)
        )
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice.copy(viewerReadAt = "2026-08-12T10:05:00Z"),
                emptySet(),
                PackageStatus.PENDING_CONFIRMATION
            )
        )
        assertFalse(
            shouldAutoShowDeliveryConfirmation(notice, emptySet(), PackageStatus.DELIVERED)
        )
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice.copy(viewerReadAt = "2026-08-12T10:05:00Z"),
                afterConfirm,
                PackageStatus.DELIVERED
            )
        )
    }

    @Test
    fun nonDeliveryNotice_neverShows() {
        // Only PACKAGE_DELIVERY_INITIATED notices can ever trigger the popup.
        assertFalse(
            shouldAutoShowDeliveryConfirmation(
                notice = deliveryNotice(eventType = "PACKAGE_DELIVERED"),
                autoShownNoticeIds = emptySet(),
                packageStatus = PackageStatus.PENDING_CONFIRMATION
            )
        )
    }
}
