package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.NewPackageTransferSubscription
import com.gocavgo.ikuriye.network.ApolloClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Subscribes to the `newPackageTransfer` GraphQL subscription and emits
 * [ClientPackage]s in real-time whenever a new package+transfer is created.
 *
 * Features:
 * - **Reconnection**: automatically reconnects with exponential backoff
 *   (1s → 2s → 4s → 8s → … capped at 60s) when the WebSocket drops.
 * - **Polling fallback**: continues 30-second polling as a safety net so
 *   packages are never missed even during reconnection windows.
 * - **Deduplication**: events are deduplicated across subscription and poll.
 *
 * Usage:
 * ```
 * PackageTransferSubscription.start(viewModelScope)
 * PackageTransferSubscription.events.collect { pkg -> ... }
 * ```
 */
object PackageTransferSubscription {

    private const val TAG = "PackageTransferSub"
    private const val POLL_FALLBACK_MS = 30_000L
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val BACKOFF_FACTOR = 2

    private val _events = MutableSharedFlow<ClientPackage>(extraBufferCapacity = 8)
    val events: SharedFlow<ClientPackage> = _events.asSharedFlow()

    private var subscriptionJob: Job? = null
    private var pollJob: Job? = null
    private var started = false

    /** IDs we've already emitted so we don't duplicate across subscription + poll. */
    private val seenIds = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        // ── Primary: real-time GraphQL subscription (with auto-reconnect) ──
        subscriptionJob = scope.launch { runSubscriptionWithRetry() }

        // ── Fallback: polling every 30 s (safety net for missed subscription events) ──
        pollJob = scope.launch {
            delay(POLL_FALLBACK_MS) // initial delay — subscription should deliver first
            while (isActive) {
                try {
                    // 1. Fetch available packages (now includes transfer-targeted packages)
                    val result = PackageRepository.fetchAvailablePackages(
                        page = 0, size = 20,
                        order = com.gocavgo.ikuriye.type.SortOrder.DESC
                    )
                    var newCount = 0
                    for (pkg in result.items) {
                        if (pkg.id !in seenIds) {
                            seenIds.add(pkg.id)
                            _events.emit(pkg)
                            newCount++
                        }
                    }

                    // 2. Recovery: also check myTransfers for packages that
                    //    may have been missed due to WebSocket drops.
                    try {
                        val transfers = PackageRepository.fetchMyTransfers()
                        val missedPackageIds = transfers
                            .filter { it.status == "PENDING" || it.status == "REQUESTED" }
                            .flatMap { it.packageIds }
                            .filter { it !in seenIds }

                        for (packageId in missedPackageIds) {
                            try {
                                val pkg = PackageRepository.fetchPackageById(packageId)
                                if (pkg != null && pkg.id !in seenIds) {
                                    seenIds.add(pkg.id)
                                    _events.emit(pkg)
                                    newCount++
                                }
                            } catch (_: Exception) {
                                // Individual package fetch failed — skip
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "poll transfer recovery: ${e.message}")
                    }

                    if (newCount > 0) {
                        Log.d(TAG, "poll fallback: emitted $newCount new packages")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "poll fallback: ${e.message}")
                }
                delay(POLL_FALLBACK_MS)
            }
        }

        Log.d(TAG, "started (subscription + ${POLL_FALLBACK_MS}ms poll fallback)")
    }

    fun stop() {
        started = false
        subscriptionJob?.cancel()
        subscriptionJob = null
        pollJob?.cancel()
        pollJob = null
        seenIds.clear()
    }

    /**
     * Pre-seed the seen-ids set so the first poll/subscription batch doesn't
     * re-emit packages already loaded in the UI.
     */
    fun seedSeenIds(ids: Set<String>) {
        seenIds.addAll(ids)
    }

    // ── Subscription with exponential-backoff reconnection ──────────────────

    private suspend fun runSubscriptionWithRetry() {
        var backoffMs = INITIAL_BACKOFF_MS

        while (started) {
            try {
                Log.d(TAG, "connecting subscription...")
                ApolloClientProvider.client
                    .subscription(NewPackageTransferSubscription())
                    .toFlow()
                    .collect { response ->
                        // ── Successful connection: reset backoff ──
                        backoffMs = INITIAL_BACKOFF_MS

                        if (!started) return@collect
                        if (response.errors != null && response.errors!!.isNotEmpty()) {
                            Log.e(TAG, "subscription errors: ${response.errors!!.joinToString { it.message }}")
                            return@collect
                        }
                        val gql = response.data?.newPackageTransfer ?: return@collect
                        val pkg = mapSubscriptionPackage(gql) ?: return@collect
                        if (pkg.id in seenIds) return@collect // deduplicate
                        seenIds.add(pkg.id)
                        _events.emit(pkg)
                        Log.d(TAG, "subscription: new package ${pkg.id}")
                    }
                // Flow completed normally (server closed the stream) — reconnect
                Log.w(TAG, "subscription stream ended normally, reconnecting in ${backoffMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "subscription error: ${e.message}, reconnecting in ${backoffMs}ms")
            }

            if (!started) break
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_FACTOR).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Maps a [NewPackageTransferSubscription.NewPackageTransfer] to a [ClientPackage].
     */
    private fun mapSubscriptionPackage(
        data: NewPackageTransferSubscription.NewPackageTransfer
    ): ClientPackage? {
        val pkg = data.deliveryPackage
        val sender = pkg.people.find { it.role.name == "SENDER" }
        val receiver = pkg.people.find { it.role.name == "RECEIVER" }
        val origin = pkg.locations.find { it.type.name == "ORIGIN" }
        val destination = pkg.locations.find { it.type.name == "DESTINATION" }

        val status = PackageRepository.mapStatus(pkg.status)

        val history = pkg.events.mapNotNull { event ->
            StatusUpdate(
                status = PackageRepository.mapStatusFromEventType(event.eventType),
                timestamp = event.createdAt,
                location = "",
                message = event.description ?: ""
            )
        }

        // Parse transfers
        val serverTransfers = pkg.transfers.mapNotNull { t ->
            ServerTransferInfo(id = t.id, ruleType = t.ruleType.name, status = t.status.name)
        }
        val firstActive = serverTransfers.firstOrNull { it.status == "PENDING" || it.status == "REQUESTED" }

        return ClientPackage(
            id = pkg.trackingCode,
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = status,
            driverName = "",
            driverPhone = "",
            createdAt = pkg.createdAt,
            receivedAt = if (status == PackageStatus.DELIVERED) pkg.updatedAt else "",
            statusHistory = history,
            custodians = pkg.custodians.map { c ->
                CustodianInfo(c.id, c.userId, c.name ?: "", c.phone ?: "", c.role.name, c.assignedAt)
            },
            transfers = serverTransfers,
            transferId = firstActive?.id,
            transferStatus = firstActive?.status,
            transferRuleType = firstActive?.ruleType
        )
    }
}
