package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.ui.common.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class FormatTimeTest {

    @Test
    fun blankOrJustNow_returnsInputUnchanged() {
        assertEquals("", formatTime(""))
        assertEquals("Just now", formatTime("Just now"))
    }

    @Test
    fun invalidIso_returnsInputUnchanged() {
        assertEquals("not-a-date", formatTime("not-a-date"))
        assertEquals("2026-13-45T99:99:99Z", formatTime("2026-13-45T99:99:99Z"))
    }

    @Test
    fun minutesAgo_returnsMinutesSuffix() {
        val iso = Instant.now().minusSeconds(5 * 60).toString()
        assertEquals("5m ago", formatTime(iso))
    }

    @Test
    fun hoursAgo_returnsHoursSuffix() {
        val iso = Instant.now().minusSeconds(3 * 3600).toString()
        assertEquals("3h ago", formatTime(iso))
    }

    @Test
    fun daysAgo_returnsDaysSuffix() {
        val iso = Instant.now().minusSeconds(4 * 86_400).toString()
        assertEquals("4d ago", formatTime(iso))
    }

    @Test
    fun futureTimestamp_returnsJustNow() {
        val iso = Instant.now().plusSeconds(60).toString()
        assertEquals("Just now", formatTime(iso))
    }

    @Test
    fun olderThanAWeek_returnsMonthDayWithoutRawIso() {
        val iso = Instant.parse("2026-01-05T10:00:00Z")
        val formatted = formatTime(iso.toString())
        // Month name may vary by locale, but the day must match the local-zone rendering
        // and the output must never be a raw ISO timestamp.
        val expectedDay = java.time.format.DateTimeFormatter.ofPattern("dd")
            .format(iso.atZone(java.time.ZoneId.systemDefault()))
        assertFalse(formatted.endsWith("Z"))
        assertFalse(formatted.contains("T10:00:00"))
        assertEquals(expectedDay, formatted.takeLast(2))
    }

    @Test
    fun formattedOutput_neverEndsInRawIsoZ() {
        val iso = Instant.now().minusSeconds(2 * 3600).toString()
        assertFalse(formatTime(iso).endsWith("Z"))
    }
}
