package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.MarkNoticeReadMutation
import android.content.Context
import com.gocavgo.ikuriye.NoticeCreatedSubscription
import com.gocavgo.ikuriye.MyNoticesQuery
import com.gocavgo.ikuriye.UnreadCountQuery
import com.gocavgo.ikuriye.network.ApolloClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Domain model for a single notice in the notification feed.
 */
data class Notice(
    val id: String,
    val resourceType: String,
    val resourceId: String,
    val eventType: String,
    val actorId: String?,
    val title: String,
    val message: String,
    val payload: String?,
    val viewerId: String,
    val viewerNoticeId: String,
    val viewerReadAt: String?,
    val createdAt: String
)

/**
 * Manages the notice notification feed.
 *
 * Architecture:
 * 1. Initial fetch via GraphQL (MyNoticesQuery, UnreadCountQuery)
 * 2. Real-time push via GraphQL subscription (NoticeCreatedSubscription) —
 *    replaces Supabase Realtime; no Supabase dependency for notifications.
 * 3. Background polling at 30s intervals as a safety-net fallback.
 * 4. Mark-as-read goes through the backend `markNoticeRead` GraphQL mutation
 *    (writes `read_at` on the `notice_viewers` row server-side), with an
 *    optimistic local update first.
 *
 * Usage: Call [start] after login/session restore with the ViewModel's scope.
 * The caller collects [notices] and [unreadCount] flows to drive the UI.
 */
object NoticeRepository {

    private const val TAG = "NoticeRepository"
    private const val POLL_INTERVAL_MS = 30_000L // 30 seconds
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val BACKOFF_FACTOR = 2

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var pollJob: Job? = null
    private var subscriptionJob: Job? = null
    private var started = false
    private var appContext: Context? = null

    // Server-confirmed "read" viewer-row ids from the last fetch, used to detect
    // when a previously-read notice regresses to unread (polling/cache or backend).
    private val previouslyReadViewerIds = mutableSetOf<String>()

    /**
     * Start the notice feed: initial fetch + GraphQL subscription + fallback polling.
     * Safe to call multiple times — subsequent calls will refresh notices.
     */
    fun start(scope: CoroutineScope, context: Context? = null) {
        if (context != null) appContext = context
        if (started) {
            scope.launch {
                fetchNotices()
                fetchUnreadCount()
            }
            return
        }
        started = true

        // Initial fetch
        scope.launch {
            fetchNotices()
            fetchUnreadCount()
        }

        // GraphQL subscription — real-time push for new notices.
        subscriptionJob = scope.launch {
            startSubscription()
        }

        // Background polling — safety-net fallback for missed subscription events.
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                fetchNotices()
                fetchUnreadCount()
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "started with GraphQL subscription + ${POLL_INTERVAL_MS}ms polling fallback")
    }

    /**
     * Restart subscription and polling with fresh client connection.
     */
    fun restart(scope: CoroutineScope, context: Context? = null) {
        stop()
        start(scope, context)
    }

    /**
     * Stop subscription, polling, and reset state.
     * Safe to call even if not started.
     */
    fun stop() {
        started = false
        subscriptionJob?.cancel()
        subscriptionJob = null
        pollJob?.cancel()
        pollJob = null
        previouslyReadViewerIds.clear()
    }

    // ── GraphQL subscription with auto-reconnect ────────────────────────────

    private suspend fun startSubscription() {
        var backoffMs = INITIAL_BACKOFF_MS

        while (started) {
            try {
                if (BuildConfig.DEBUG) Log.d(TAG, "subscription: connecting...")
                // Immediately catch up on any notices missed while disconnected
                fetchNotices()
                fetchUnreadCount()
                ApolloClientProvider.client
                    .subscription(NoticeCreatedSubscription())
                    .toFlow()
                    .collect { response ->
                        // Successful connection — reset backoff
                        backoffMs = INITIAL_BACKOFF_MS

                        if (!started) return@collect
                        if (response.errors != null && response.errors!!.isNotEmpty()) {
                            Log.e(TAG, "subscription: ${response.errors!!.joinToString { it.message ?: "" }}")
                            return@collect
                        }
                        val gql = response.data?.noticeCreated ?: return@collect
                        val notice = Notice(
                            id = gql.id,
                            resourceType = gql.resourceType,
                            resourceId = gql.resourceId,
                            eventType = gql.eventType,
                            actorId = gql.actorId,
                            title = gql.title,
                            message = gql.message,
                            payload = gql.payload,
                            viewerId = gql.viewer.id,
                            viewerNoticeId = gql.viewer.noticeId,
                            viewerReadAt = gql.viewer.readAt,
                            createdAt = gql.createdAt
                        )
                        // Deduplicate — the 30s poll may have already fetched this notice.
                        val existing = _notices.value.find { it.viewerId == notice.viewerId }
                        if (existing == null) {
                            _notices.update { list ->
                                (list + notice).sortedBy { it.viewerReadAt != null }
                            }
                            _unreadCount.value = _unreadCount.value + 1
                            if (BuildConfig.DEBUG) Log.d(TAG, "subscription: new notice ${notice.id} (${notice.eventType})")
                            // Show local Android notification for important events
                            showLocalNotification(notice)
                        }
                    }
                // Flow completed — server closed the stream, reconnect
                if (BuildConfig.DEBUG) Log.w(TAG, "subscription stream ended, reconnecting in ${backoffMs}ms")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "subscription error: ${e.message}, reconnecting in ${backoffMs}ms")
            }

            if (!started) break
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_FACTOR).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    // ── Local Android notification for delivery code ───────────────────────

    private fun showLocalNotification(notice: Notice) {
        val context = appContext ?: return

        try {
            val channelId: String
            val title: String
            val text: String
            val bigText: String
            val importance: Int

            when (notice.eventType) {
                "PACKAGE_DELIVERY_INITIATED" -> {
                    channelId = "delivery_codes"
                    title = "Delivery Code Available"
                    val payload = notice.payload ?: return
                    val deliveryCode = try {
                        JSONObject(payload).optString("deliveryCode").takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null } ?: return
                    val trackingCode = try {
                        JSONObject(payload).optString("trackingCode")
                    } catch (_: Exception) { "" }
                    text = "Code: $deliveryCode for package $trackingCode"
                    bigText = "Delivery code: $deliveryCode\nPackage: $trackingCode\nShow this code to the driver to confirm delivery."
                    importance = android.app.NotificationManager.IMPORTANCE_HIGH
                }
                "PACKAGE_ACCEPTED" -> {
                    channelId = "package_updates"
                    title = "Package Accepted"
                    text = notice.message
                    bigText = "Your package ${notice.resourceId} has been accepted by a driver and is being picked up."
                    importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
                }
                "PACKAGE_DELIVERED" -> {
                    channelId = "package_updates"
                    title = "Package Delivered"
                    text = notice.message
                    bigText = "Your package ${notice.resourceId} has been delivered successfully."
                    importance = android.app.NotificationManager.IMPORTANCE_HIGH
                }
                "PACKAGE_PICKED_UP" -> {
                    channelId = "package_updates"
                    title = "Package Picked Up"
                    text = notice.message
                    bigText = "Your package ${notice.resourceId} has been picked up and is on its way."
                    importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
                }
                else -> return // Don't show local notification for other event types
            }

            val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            // Create channel if needed (Android 8+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    when (channelId) {
                        "delivery_codes" -> "Delivery Codes"
                        else -> "Package Updates"
                    },
                    importance
                ).apply {
                    description = when (channelId) {
                        "delivery_codes" -> "Notifications when a delivery code is issued"
                        else -> "Package status update notifications"
                    }
                }
                manager.createNotificationChannel(channel)
            }

            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle().bigText(bigText)
                )
                .setPriority(importance)
                .setAutoCancel(true)
                .build()

            manager.notify(notice.id.hashCode(), notification)
            if (BuildConfig.DEBUG) Log.d(TAG, "local notification shown: ${notice.eventType}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "showLocalNotification failed: ${e.message}", e)
        }
    }

    // ── GraphQL queries ─────────────────────────────────────────────────────────

    private suspend fun fetchNotices() {
        try {
            val response = ApolloClientProvider.client
                .query(MyNoticesQuery())
                .execute()
            if (response.errors != null && response.errors!!.isNotEmpty()) {
                Log.e(TAG, "fetchNotices: ${response.errors!!.joinToString { it.message ?: "" }}")
                return
            }
            val items = response.data?.myNotices?.filterNotNull() ?: emptyList()
            _notices.value = items.map { gql ->
                Notice(
                    id = gql.id,
                    resourceType = gql.resourceType,
                    resourceId = gql.resourceId,
                    eventType = gql.eventType,
                    actorId = gql.actorId,
                    title = gql.title,
                    message = gql.message,
                    payload = gql.payload,
                    viewerId = gql.viewer.id,
                    viewerNoticeId = gql.viewer.noticeId,
                    viewerReadAt = gql.viewer.readAt,
                    createdAt = gql.createdAt
                )
            }.sortedBy { it.viewerReadAt != null } // unread first, read at the end (stable)
            if (BuildConfig.DEBUG) {
                val unread = _notices.value.count { it.viewerReadAt == null }
                Log.d(TAG, "fetchNotices: ${_notices.value.size} notices from server, $unread unread")
                val regressed = previouslyReadViewerIds.filter { id ->
                    _notices.value.none { it.viewerId == id && it.viewerReadAt != null }
                }
                if (regressed.isNotEmpty()) {
                    Log.w(TAG, "fetchNotices: previously server-read notices now unread again: $regressed")
                }
                val optimisticRead = _notices.value.filter { it.viewerReadAt != null }.map { it.viewerId }
                val optimisticButUnreadOnServer = optimisticRead.filter { id -> items.none { it.viewer.id == id && it.viewer.readAt != null } }
                if (optimisticButUnreadOnServer.isNotEmpty()) {
                    Log.w(TAG, "fetchNotices: locally shown as read but server still unread: $optimisticButUnreadOnServer")
                }
            }
            previouslyReadViewerIds.clear()
            _notices.value.forEach { if (it.viewerReadAt != null) previouslyReadViewerIds.add(it.viewerId) }
            if (BuildConfig.DEBUG) Log.d(TAG, "fetched ${_notices.value.size} notices")
        } catch (e: Exception) {
            Log.e(TAG, "fetchNotices: ${e.message}", e)
        }
    }

    private suspend fun fetchUnreadCount() {
        try {
            val response = ApolloClientProvider.client
                .query(UnreadCountQuery())
                .execute()
            if (response.errors != null && response.errors!!.isNotEmpty()) {
                Log.e(TAG, "unreadCount: ${response.errors!!.joinToString { it.message ?: "" }}")
                return
            }
            _unreadCount.value = response.data?.unreadNoticeCount ?: 0
            if (BuildConfig.DEBUG) Log.d(TAG, "unreadCount: ${_unreadCount.value} from server")
        } catch (e: Exception) {
            Log.e(TAG, "unreadCount: ${e.message}", e)
        }
    }

    /**
     * Mark a single notice as read — optimistic local update, then persisted
     * through the backend `markNoticeRead` GraphQL mutation (which writes
     * `read_at` on the `notice_viewers` row server-side).
     */
    suspend fun markRead(notice: Notice) {
        val viewerId = notice.viewerId
        _notices.update { list ->
            applyMarkRead(list, viewerId).sortedBy { it.viewerReadAt != null }
        }
        _unreadCount.value = (_unreadCount.value - 1).coerceAtLeast(0)

        try {
            val response = ApolloClientProvider.client
                .mutation(MarkNoticeReadMutation(viewerId = viewerId))
                .execute()
            if (response.errors != null && response.errors!!.isNotEmpty()) {
                Log.e(TAG, "markRead: GraphQL errors — ${response.errors!!.joinToString { it.message ?: "" }}")
                return
            }
            val readAt = response.data?.markNoticeRead?.readAt
            if (readAt != null) {
                _notices.update { list ->
                    list.map { n ->
                        if (n.viewerId == viewerId) n.copy(viewerReadAt = readAt) else n
                    }.sortedBy { it.viewerReadAt != null }
                }
            }
        } catch (e: Exception) {
            // Persist failed — the optimistic update above stays, so the UI will
            // appear read until the next fetch returns the server state (unread).
            Log.e(TAG, "markRead: mutation failed for viewerId=$viewerId — ${e.message}", e)
        }
    }
}

/**
 * Pure helper: marks the notice owned by [viewerId] as read.
 * Extracted from [NoticeRepository.markRead] so the logic can be unit-tested.
 */
internal fun applyMarkRead(notices: List<Notice>, viewerId: String): List<Notice> =
    notices.map { n ->
        if (n.viewerId == viewerId) n.copy(viewerReadAt = "now") else n
    }
