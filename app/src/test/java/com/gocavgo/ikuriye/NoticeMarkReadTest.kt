package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.Notice
import com.gocavgo.ikuriye.data.applyMarkRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeMarkReadTest {

    private fun notice(viewerId: String, readAt: String? = null) = Notice(
        id = "n-$viewerId",
        resourceType = "PACKAGE",
        resourceId = "pkg-1",
        eventType = "PACKAGE_DELIVERED",
        actorId = null,
        title = "Package delivered",
        message = "Your package was delivered",
        payload = null,
        viewerId = viewerId,
        viewerNoticeId = "vn-$viewerId",
        viewerReadAt = readAt,
        createdAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun applyMarkRead_marksOnlyTheTargetNotice() {
        val notices = listOf(notice("v1"), notice("v2"))
        val updated = applyMarkRead(notices, "v1")

        assertEquals(2, updated.size)
        assertEquals("now", updated[0].viewerReadAt)
        assertNull(updated[1].viewerReadAt)
    }

    @Test
    fun applyMarkRead_alreadyReadNotice_staysRead() {
        val notices = listOf(notice("v1", readAt = "2026-01-01T00:00:00Z"))
        val updated = applyMarkRead(notices, "v1")
        assertEquals("now", updated[0].viewerReadAt)
    }

    @Test
    fun applyMarkRead_unknownViewer_isNoOp() {
        val notices = listOf(notice("v1"))
        val updated = applyMarkRead(notices, "missing")
        assertEquals("v1", updated[0].viewerId)
        assertNull(updated[0].viewerReadAt)
    }

    @Test
    fun applyMarkRead_emptyList_returnsEmpty() {
        assertEquals(emptyList<Notice>(), applyMarkRead(emptyList(), "v1"))
    }
}
