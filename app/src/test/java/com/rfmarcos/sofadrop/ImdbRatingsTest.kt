package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The title.ratings.tsv layout of IMDb's dataset, out of order and with a broken line. */
class ImdbRatingsTest {

    private val index = ImdbRatings.parse(
        """
        tconst	averageRating	numVotes
        tt0903747	9.5	2694802
        tt0000001	5.7	2100
        tt10872600	7.9	1050000
        not a line
        tt0137523	8.8	2675558
        """.trimIndent().byteInputStream()
    )

    @Test
    fun findsRatingsById() {
        assertEquals(4, index.size)
        assertEquals(8.8, ImdbRatings.find(index, 137523)!!, 0.0)
        assertEquals(9.5, ImdbRatings.find(index, 903747)!!, 0.0)
        assertEquals(7.9, ImdbRatings.find(index, 10872600)!!, 0.0)
        assertEquals(5.7, ImdbRatings.find(index, 1)!!, 0.0)
        assertNull(ImdbRatings.find(index, 1234567))
    }
}
