package com.gocavgo.ikuriye.data

/**
 * Pure decision for the auto delivery-confirmation popup: given a candidate
 * `PACKAGE_DELIVERY_INITIATED` notice, should the popup auto-show?
 *
 * Extracted from `TripViewModel.maybeShowDeliveryConfirmation` so the
 * confirm → kill → reopen scenario can be unit-tested (see
 * `DeliveryConfirmationGateTest`). Callers are responsible for the surrounding
 * ViewModel checks (CLIENT role, dialog not already open, latest-notice
 * selection, and presence of a delivery code in the payload).
 */
internal fun shouldAutoShowDeliveryConfirmation(
    notice: Notice,
    autoShownNoticeIds: Set<String>,
    packageStatus: PackageStatus?,
): Boolean {
    if (notice.eventType != "PACKAGE_DELIVERY_INITIATED") return false
    // Already auto-shown (and confirmed or dismissed) in this install — the id
    // is persisted to SharedPreferences, so this guard survives app restarts.
    if (notice.viewerNoticeId in autoShownNoticeIds) return false
    // Already marked read server-side (confirmed on this device or another).
    if (notice.viewerReadAt != null) return false
    // The package is already DELIVERED (e.g. confirmed from the tracking
    // screen) — never re-pop for a completed delivery.
    if (packageStatus == PackageStatus.DELIVERED) return false
    return true
}
