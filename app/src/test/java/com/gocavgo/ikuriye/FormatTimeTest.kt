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
        assertEquals("1hr ago", formatTime(Instant.now().minusSeconds(3600).toString()))
        val iso = Instant.now().minusSeconds(3 * 3600).toString()
        assertEquals("3hr ago", formatTime(iso))
    }

    @Test
    fun daysAgo_returnsNaturalDayPhrases() {
        assertEquals("yesterday", formatTime(Instant.now().minusSeconds(86_400).toString()))
        val iso = Instant.now().minusSeconds(4 * 86_400).toString()
        assertEquals("4 days ago", formatTime(iso))
        assertEquals("1 week ago", formatTime(Instant.now().minusSeconds(10 * 86_400).toString()))
    }

    @Test
    fun futureTimestamp_returnsJustNow() {
        val iso = Instant.now().plusSeconds(60).toString()
        assertEquals("Just now", formatTime(iso))
    }

    @Test
    fun olderThanAWeek_neverReturnsRawIsoTimestamp() {
        val iso = Instant.now().minusSeconds(45 * 86_400L).toString()
        val formatted = formatTime(iso)
        assertFalse(formatted.endsWith("Z"))
        assertFalse(formatted.contains("T10:00:00"))
        assertEquals("last month", formatted)
    }

    @Test
    fun formattedOutput_neverEndsInRawIsoZ() {
        val iso = Instant.now().minusSeconds(2 * 3600).toString()
        assertFalse(formatTime(iso).endsWith("Z"))
    }
}
