package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** [WallpaperFactory.parallel] keeps the order of the items and throws what the task threw. */
class ParallelTest {

    @Test
    fun keepsOrder() {
        // The first items finish last
        val result = WallpaperFactory.parallel((1..8).toList()) { Thread.sleep((9 - it) * 20L); it * 10 }.toList()
        assertEquals((1..8).map { it * 10 }, result)
    }

    @Test(expected = TmdbException::class)
    fun throwsTheTaskError() {
        WallpaperFactory.parallel(listOf(1, 2)) { if (it == 2) throw TmdbException("401") else it }.toList()
    }

    @Test
    fun returnsResultsBeforeAFailure() {
        val seen = mutableListOf<Int>()
        try {
            WallpaperFactory.parallel(listOf(1, 2, 3)) { if (it == 3) throw IOException() else it }.forEach { seen += it }
        } catch (_: IOException) {
        }
        assertEquals(listOf(1, 2), seen)
    }
}
