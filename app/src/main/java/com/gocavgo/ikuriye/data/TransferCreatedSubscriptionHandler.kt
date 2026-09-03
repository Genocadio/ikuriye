package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.TransferCreatedSubscription
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.nexx.NexxAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Subscribes to the `transferCreated` GraphQL subscription and emits
 * [PackageRepository.MatchedTransfer]s in real-time whenever a transfer
 * is created targeting the authenticated driver.
 *
 * Features:
 * - Reconnection with exponential backoff
 * - Polling fallback (every 30s) for missed events
 * - Deduplication by transfer ID
 */
object TransferCreatedSubscriptionHandler {

    private const val TAG = "TransferCreatedSub"
    private const val POLL_FALLBACK_MS = 30_000L
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val BACKOFF_FACTOR = 2

    data class TransferCreatedEvent(
        val transferId: String,
        val matched: PackageRepository.MatchedTransfer
    )

    private val _events = MutableSharedFlow<TransferCreatedEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TransferCreatedEvent> = _events.asSharedFlow()

    private var subscriptionJob: Job? = null
    private var pollJob: Job? = null
    private var started = false
    private val seenTransferIds = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        subscriptionJob = scope.launch { runSubscriptionWithRetry() }

        pollJob = scope.launch {
            delay(POLL_FALLBACK_MS)
            while (isActive) {
                try {
                    val transfers = PackageRepository.fetchTransfersForMe()
                    for (mt in transfers) {
                        if (mt.transferId !in seenTransferIds) {
                            seenTransferIds.add(mt.transferId)
                            _events.emit(TransferCreatedEvent(mt.transferId, mt))
                        }
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
        seenTransferIds.clear()
    }

    fun seedSeenIds(ids: Set<String>) {
        seenTransferIds.addAll(ids)
    }

    private suspend fun runSubscriptionWithRetry() {
        var backoffMs = INITIAL_BACKOFF_MS

        while (started) {
            try {
                // Proactive token refresh
                try {
                    val exp = getJwtExpirySeconds()
                    val now = System.currentTimeMillis() / 1000
                    if (exp == null || exp - now < 5 * 60) {
                        Log.d(TAG, "proactive refresh before reconnect (exp=$exp, now=$now)")
                        NexxAuth.refreshSession()
                        ApolloClientProvider.resetClient()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "proactive refresh failed: ${e.message}")
                }

                Log.d(TAG, "connecting subscription...")
                ApolloClientProvider.client
                    .subscription(TransferCreatedSubscription())
                    .toFlow()
                    .collect { response ->
                        backoffMs = INITIAL_BACKOFF_MS
                        if (!started) return@collect
                        if (response.errors != null && response.errors!!.isNotEmpty()) {
                            Log.e(TAG, "subscription errors: ${response.errors!!.joinToString { it.message }}")
                            return@collect
                        }
                        val gql = response.data?.transferCreated ?: return@collect
                        val transfer = gql.transfer
                        val transferId = transfer.id

                        if (transferId in seenTransferIds) return@collect
                        seenTransferIds.add(transferId)

                        // Fetch full package data for each package in the transfer
                        val packages = mutableListOf<com.gocavgo.ikuriye.data.ClientPackage>()
                        for (tp in transfer.packages) {
                            try {
                                when (val result = PackageRepository.fetchPackageById(tp.packageId.toString())) {
                                    is com.gocavgo.ikuriye.data.SingleResult.Success -> packages.add(result.data)
                                    else -> Log.w(TAG, "Could not fetch package ${tp.packageId} for transfer $transferId")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error fetching package ${tp.packageId}: ${e.message}")
                            }
                        }

                        val matched = PackageRepository.MatchedTransfer(
                            transferId = transferId,
                            creatorId = transfer.creatorId.toString(),
                            ruleType = transfer.ruleType.name,
                            acceptorType = transfer.acceptorType.name,
                            status = transfer.status.name,
                            packages = packages
                        )

                        _events.emit(TransferCreatedEvent(transferId, matched))
                        Log.d(TAG, "subscription: new transfer $transferId")
                    }
                Log.w(TAG, "subscription stream ended normally, reconnecting in ${backoffMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "subscription error: ${e.message}, reconnecting in ${backoffMs}ms")
            }

            if (!started) break
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_FACTOR).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun getJwtExpirySeconds(): Long? {
        val token = NexxAuth.getAccessToken() ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            val payload = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )
            val json = org.json.JSONObject(payload)
            if (json.has("exp")) json.getLong("exp") else null
        } catch (e: Exception) {
            null
        }
    }
}
