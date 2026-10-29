package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GenerationLogTest {

    private val header = listOf(
        "Sofadrop 1.2.0 (5) · 2026-10-29 18:40",
        "Google Chromecast · Android 14 (API 34)",
        "Memory: class 192 MB, large 512 MB, RAM 1966 MB",
        "3840 px · es-ES · 10 per list",
    )

    @Test
    fun shortLogIsTheSummary() {
        val log = (header + "tmdb:trending_movies: 20 read, 2 filtered, 10 drawn" + "Generated 10 wallpapers · 40 s").joinToString("\n")
        assertEquals(log, GenerationLog.summary(log))
    }

    @Test
    fun longLogKeepsHeaderAndResult() {
        val failures = (1..100).map { "  movie/$it at 3840 px: OutOfMemoryError: Failed to allocate a 33177612 byte allocation" }
        val log = (header + failures + "Generated 50 wallpapers · 300 s").joinToString("\n")
        val summary = GenerationLog.summary(log)
        assertTrue(summary.toByteArray().size <= GenerationLog.SUMMARY_LIMIT)
        assertTrue(summary.startsWith(header.joinToString("\n")))
        assertTrue(summary.endsWith("Generated 50 wallpapers · 300 s"))
        assertTrue(summary.contains("  movie/1 at 3840 px"))
        assertTrue(summary.contains(" more lines\n"))
    }

    @Test
    fun describeLeavesOutQueries() {
        val error = IOException("HTTP 500 for https://api.mdblist.com/lists/user/top?apikey=secret123&limit=100")
        val text = GenerationLog.describe(error)
        assertFalse(text, text.contains("secret123"))
        assertEquals("IOException: HTTP 500 for https://api.mdblist.com/lists/user/top?…", text)
    }

    @Test
    fun describeWithoutMessage() {
        assertEquals("OutOfMemoryError", GenerationLog.describe(OutOfMemoryError()))
    }
}
