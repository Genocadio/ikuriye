package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.MyNoticesQuery
import com.gocavgo.ikuriye.UnreadCountQuery
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.supa.SupaAuth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * 2. Background polling at 30s intervals for new/updated notices
 * 3. Mark-as-read is written directly to the Supabase `notice_viewer`
 *    table via Postgrest (the GraphQL `markNoticeRead` mutation does not
 *    exist on the backend — it fails with `FieldUndefined`), with an
 *    optimistic local update first.
 *
 * Polling is used because the supabase-kt library's Realtime API has
 * `PostgresActionData` marked `internal`, preventing typed access to
 * the inserted row data from the public API.
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

        // Initial fetch
        scope.launch {
            fetchNotices()
            fetchUnreadCount()
        }

        // Background polling for new notices
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                fetchNotices()
                fetchUnreadCount()
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "started with ${POLL_INTERVAL_MS}ms polling")
    }

    /**
     * Stop polling and reset state.
     * Safe to call even if not started.
     */
    fun stop() {
        started = false
        pollJob?.cancel()
        pollJob = null
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
     * by directly updating the Supabase `notice_viewer` row via Postgrest
     * (the GraphQL `markNoticeRead` mutation does not exist on the backend).
     *
     * The row is matched by its natural key (`notice_id` + `user_id`), not by
     * `id`: the GraphQL `NoticeViewer.id` is the backend's own identifier and
     * does not reliably equal the Supabase `notice_viewers.id` primary key.
     */
    suspend fun markRead(notice: Notice) {
        val viewerId = notice.viewerId
        val noticeId = notice.viewerNoticeId
        val userId = SupaAuth.getCurrentUserId()
        _notices.update { list ->
            applyMarkRead(list, viewerId).sortedBy { it.viewerReadAt != null }
        }
        _unreadCount.value = (_unreadCount.value - 1).coerceAtLeast(0)

        val readAt = java.time.Instant.now().toString()
        try {
            // `select()` makes PostgREST return the updated rows (representation),
            // so `rows` reveals whether any row actually matched the filter.
            val result = SupaAuth.client.postgrest
                .from(NOTICE_VIEWER_TABLE)
                .update(
                    { set("read_at", readAt) }
                ) {
                    select()
                    filter { eq("notice_id", noticeId) }
                    if (userId != null) filter { eq("user_id", userId) }
                }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "markRead: postgrest update notice_id=$noticeId user_id=$userId read_at=$readAt | rows=${result.data}")
                if (result.data.trim() == "[]") {
                    // No row matched. Distinguish a bad filter from an RLS block:
                    // a plain SELECT that finds the row means the UPDATE is blocked
                    // by the table's policies.
                    try {
                        val rows = SupaAuth.client.postgrest
                            .from(NOTICE_VIEWER_TABLE)
                            .select {
                                filter { eq("notice_id", noticeId) }
                                limit(5)
                            }
                        Log.d(TAG, "markRead: diagnostic select by notice_id=$noticeId -> ${rows.data}")
                    } catch (e: Exception) {
                        Log.e(TAG, "markRead: diagnostic select failed — ${e.message}", e)
                    }
                }
            }
            _notices.update { list ->
                list.map { n ->
                    if (n.viewerId == viewerId) n.copy(viewerReadAt = readAt) else n
                }.sortedBy { it.viewerReadAt != null }
            }
        } catch (e: Exception) {
            // Persist failed — the optimistic update above stays, so the UI will
            // appear read until the next fetch returns the server state (unread).
            Log.e(TAG, "markRead: postgrest update failed for viewerId=$viewerId — ${e.message}", e)
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
