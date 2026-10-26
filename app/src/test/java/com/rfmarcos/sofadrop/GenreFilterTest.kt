package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreFilterTest {

    @Test
    fun onlyOrAllBut() {
        val comedy = 35
        val horror = 27
        assertTrue(GenreFilter().allows(listOf(horror)))
        assertTrue(GenreFilter(only = true).allows(listOf(horror)))

        val only = GenreFilter(setOf(comedy), only = true)
        assertTrue(only.allows(listOf(horror, comedy)))
        assertFalse(only.allows(listOf(horror)))
        assertFalse(only.allows(emptyList()))

        // The default: checked genres are skipped
        val allBut = GenreFilter(setOf(horror))
        assertFalse(allBut.allows(listOf(horror, comedy)))
        assertTrue(allBut.allows(listOf(comedy)))
        assertTrue(allBut.allows(emptyList()))
    }

    @Test
    fun listFiltersStoredBeforeGenresStillRead() {
        val old = Json.decodeFromString<Map<String, ListFilters>>("""{"tmdb:trending_movie":{"maxAge":12}}""")
        assertEquals(ListFilters(maxAge = 12), old["tmdb:trending_movie"])
        assertFalse(old.getValue("tmdb:trending_movie").isDefault)
        assertTrue(ListFilters(genres = null).isDefault)
        assertFalse(ListFilters(genres = GenreFilter()).isDefault)
    }
}
