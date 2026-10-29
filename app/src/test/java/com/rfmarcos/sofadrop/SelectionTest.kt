package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which titles of a list are tried, see [TmdbCatalog.select]. */
class SelectionTest {

    private val refs = (1..10).map { TitleRef(MediaType.MOVIE, it) }
    private fun ids(candidates: List<Candidate>) = candidates.map { it.ref.tmdbId }

    @Test
    fun stopsOnceThePoolIsFull() {
        val selected = TmdbCatalog.select(refs, 3, null, null)
        assertEquals(listOf(1, 2, 3), ids(selected))
        assertEquals(listOf(null, null, null), selected.map { it.exclusion })
    }

    @Test
    fun runExclusionsCountAgainstThePool() {
        // Titles taken by an earlier list don't use up the pool: later titles take their place
        val taken = setOf(refs[0], refs[2])
        val selected = TmdbCatalog.select(refs, 3, null, { if (it in taken) Exclusion.DUPLICATE else null })
        assertEquals(listOf(1, 2, 3, 4, 5), ids(selected))
        assertEquals(listOf(2, 4, 5), ids(selected.filter { it.exclusion == null }))
    }

    @Test
    fun listFiltersComeFirst() {
        val selected = TmdbCatalog.select(refs.take(1), 1, { Exclusion.TOO_OLD }, { Exclusion.DUPLICATE })
        assertEquals(Exclusion.TOO_OLD, selected.single().exclusion)
    }

    @Test
    fun nothingIsCheckedAfterThePoolIsFull() {
        // The filter fetches TMDB details per title: none for titles that are never used
        val checked = mutableListOf<Int>()
        TmdbCatalog.select(refs, 2, { checked += it.tmdbId; null }, { checked += -it.tmdbId; null }, batch = 1)
        assertEquals(listOf(1, -1, 2, -2), checked)
    }

    @Test
    fun keepsTheOrderWithParallelBatches() {
        val selected = TmdbCatalog.select(refs, 10, { if (it.tmdbId % 3 == 0) Exclusion.GENRE else null }, null, batch = 4)
        assertEquals((1..10).toList(), ids(selected))
        assertEquals(listOf(1, 2, 4, 5, 7, 8, 10), ids(selected.filter { it.exclusion == null }))
    }

    @Test
    fun shortListsAreReadFurtherUpToALimit() {
        assertEquals(80, TmdbCatalog.grow(40))
        val steps = generateSequence(40) { TmdbCatalog.grow(it) }.toList()
        assertEquals(200, steps.last())
        assertTrue(steps.size < 10)
    }

    @Test
    fun marginOnlyGrowsWithExclusions() {
        assertTrue(TmdbCatalog.fetchCount(10, shuffle = false, strict = true) > TmdbCatalog.fetchCount(10, shuffle = false, strict = false))
        assertEquals(TmdbCatalog.fetchCount(10, shuffle = true, strict = false), TmdbCatalog.fetchCount(10, shuffle = true, strict = true))
    }
}
