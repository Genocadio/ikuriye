package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.MarkNoticeReadMutation
import com.gocavgo.ikuriye.MyNoticesQuery
import com.gocavgo.ikuriye.UnreadCountQuery
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.supa.SupaAuth
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
 * 2. Realtime push for new notices (INSERTs on the Supabase `notice_viewers`
 *    table) for near-instant delivery
 * 3. Background polling at 30s intervals as a safety net (missed events,
 *    updates, reconnect gaps)
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

    /**
     * Supabase table backing the GraphQL `NoticeViewer` type.
     * Columns follow the GraphQL fields: `id`, `notice_id`, `user_id`,
     * `delivered_at`, `read_at`.
     */
    private const val NOTICE_VIEWER_TABLE = "notice_viewers"

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var pollJob: Job? = null
    private var realtimeJob: Job? = null
    private var realtimeChannel: RealtimeChannel? = null
    private var scopeRef: CoroutineScope? = null
    private var started = false

    // Server-confirmed "read" viewer-row ids from the last fetch, used to detect
    // when a previously-read notice regresses to unread (polling/cache or backend).
    private val previouslyReadViewerIds = mutableSetOf<String>()

    /**
     * Start the notice feed: initial fetch + background polling.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scopeRef = scope

        // Initial fetch
        scope.launch {
            fetchNotices()
            fetchUnreadCount()
        }

        // Realtime push for new notices — near-instant delivery.
        startRealtimeSubscription(scope)

        // Background polling as a safety net (missed events, updates, reconnect gaps)
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                fetchNotices()
                fetchUnreadCount()
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "started with realtime + ${POLL_INTERVAL_MS}ms polling fallback")
    }

    /**
     * Stop polling and reset state.
     * Safe to call even if not started.
     */
    fun stop() {
        started = false
        pollJob?.cancel()
        pollJob = null
        realtimeJob?.cancel()
        realtimeJob = null
        // Best-effort channel cleanup; fire-and-forget in the stored scope.
        // removeChannel() already unsubscribes and removes the channel.
        realtimeChannel?.let { channel ->
            scopeRef?.launch {
                try {
                    SupaAuth.client.realtime.removeChannel(channel)
                } catch (e: Exception) {
                    Log.e(TAG, "realtime: removeChannel failed — ${e.message}", e)
                }
            }
        }
        realtimeChannel = null
    }

    /**
     * Subscribes to realtime INSERT events on the `notice_viewers` table so new
     * notices reach the app almost instantly, instead of waiting for the polling
     * interval. Filtered server-side to the current user. On failure (e.g. the
     * table isn't enabled for Realtime in the Supabase dashboard, or RLS blocks
     * the subscription) we silently fall back to polling.
     */
    private fun startRealtimeSubscription(scope: CoroutineScope) {
        // start() is only called after login/session restore, but be defensive.
        val userId = SupaAuth.getCurrentUserId()
        if (userId.isNullOrBlank()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "realtime: no user session — skipping subscription")
            return
        }
        realtimeJob = scope.launch {
            var collector: Job? = null
            try {
                val channel = SupaAuth.client.realtime.channel("realtime:notice_viewers:$userId")
                realtimeChannel = channel
                // IMPORTANT: postgresChangeFlow must be registered before
                // subscribe() — the join payload is built from the registered
                // postgres change configs (it throws if called after joining).
                val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = NOTICE_VIEWER_TABLE
                    filter("user_id", FilterOperator.EQ, userId)
                }
                // Register the listener before subscribing so no INSERT event is missed.
                collector = launch {
                    changeFlow.collect { action ->
                        if (BuildConfig.DEBUG) {
                            val noticeId = action.record["notice_id"]?.jsonPrimitive?.contentOrNull
                            Log.d(TAG, "realtime: INSERT on notice_viewers (notice_id=$noticeId) — refreshing feed")
                        }
                        fetchNotices()
                        fetchUnreadCount()
                    }
                }
                channel.subscribe()
                if (BuildConfig.DEBUG) {
                    // Debug-only dashboard health check: verifies the notice_viewers
                    // table is actually enabled for Realtime. If the channel never
                    // reaches SUBSCRIBED (table not added to the supabase_realtime
                    // publication, or RLS blocks subscription), notifications will
                    // silently rely on polling — this surfaces that in logcat.
                    launch {
                        val healthTimeoutMs = 10_000L
                        val subscribed = withTimeoutOrNull(healthTimeoutMs) {
                            channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                            true
                        } ?: false
                        if (subscribed) {
                            Log.i(TAG, "realtime HEALTH: OK — notice_viewers is enabled for Realtime (channel SUBSCRIBED)")
                        } else {
                            Log.w(TAG, "realtime HEALTH: FAILED — notice_viewers may not be enabled for Realtime (final status: ${channel.status.value}). " +
                                "Enable it in the Supabase dashboard (Database → Realtime → add table) and check RLS. Notifications fall back to polling.")
                        }
                    }
                }
                collector.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // normal teardown on stop()/logout — not an error
            } catch (e: Exception) {
                collector?.cancel()
                Log.e(TAG, "realtime: subscription unavailable — polling fallback active — ${e.message}", e)
            }
        }
    }

    // ── GraphQL operations ─────────────────────────────────────────────────────

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
