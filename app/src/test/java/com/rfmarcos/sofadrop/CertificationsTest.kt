package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Age certifications from trimmed TMDB details (shapes as of October 2026). */
class CertificationsTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun movie(vararg es: String) = obj(
        """{"release_dates":{"results":[{"iso_3166_1":"ES","release_dates":[${es.joinToString(",")}]},
           {"iso_3166_1":"US","release_dates":[{"type":3,"release_date":"2026-11-01T00:00:00.000Z","certification":"PG-13"}]}]}}"""
    )
    private fun release(type: Int, date: String, certification: String = "") =
        """{"type":$type,"release_date":"${date}T00:00:00.000Z","certification":"$certification"}"""

    @Test
    fun certificationOfTheCountryElseUsForTheFilter() {
        val withSpain = movie(release(1, "2024-09-21"), release(3, "2024-10-11", "A"), release(4, "2025-01-10", "7"))
        // Theatrical first; Spain's "A" is shown as its official "TP"
        assertEquals("A", Certifications.of(MediaType.MOVIE, withSpain, "ES"))
        assertEquals("TP", Certifications.label("ES", "A"))
        assertEquals(0, Certifications.minimumAge(MediaType.MOVIE, withSpain, "ES"))
        val usOnly = movie(release(3, "2026-11-14"))
        assertNull(Certifications.of(MediaType.MOVIE, usOnly, "ES"))
        assertEquals(13, Certifications.minimumAge(MediaType.MOVIE, usOnly, "ES"))
        assertNull(Certifications.minimumAge(MediaType.MOVIE, obj("""{"status":"Released"}"""), "ES"))

        val series = obj("""{"content_ratings":{"results":[{"iso_3166_1":"ES","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"},{"iso_3166_1":"IT","rating":"NR"}]}}""")
        assertEquals("16", Certifications.of(MediaType.TV, series, "ES"))
        assertNull(Certifications.of(MediaType.TV, series, "IT"))
        assertEquals(17, Certifications.minimumAge(MediaType.TV, series, "IT"))
    }

    @Test
    fun agesOfEachSystem() {
        val cases = listOf(
            "US" to "G" to 0, "US" to "PG" to 10, "US" to "PG-13" to 13, "US" to "R" to 17, "US" to "NC-17" to 18,
            "US" to "TV-Y7" to 7, "US" to "TV-14" to 14, "US" to "TV-MA" to 17,
            "GB" to "12A" to 12, "GB" to "PG" to 8, "CA" to "14A" to 14, "CA" to "18+" to 18,
            "ES" to "A" to 0, "ES" to "7i" to 7, "ES" to "TP" to 0, "ES" to "X" to 18,
            "MX" to "A" to 0, "MX" to "B" to 12, "MX" to "B-15" to 15, "MX" to "C" to 18,
            "AR" to "SAM 13" to 13, "CL" to "TE+7" to 7, "CL" to "I10" to 10, "BR" to "L" to 0, "PT" to "M/16" to 16,
            "FR" to "TP" to 0, "IT" to "VM14" to 14, "IT" to "T" to 0, "DE" to "16" to 16,
            "SE" to "Btl" to 0, "SE" to "11" to 11, "SE" to "Från 15 år" to 15, "NO" to "A" to 0, "NO" to "9" to 9,
            "DK" to "A" to 0, "DK" to "11" to 11, "FI" to "S" to 0, "FI" to "K-12" to 12, "FI" to "K16" to 16, "FI" to "KK" to 18,
        )
        cases.forEach { (mark, age) -> assertEquals("${mark.first} ${mark.second}", age, Certifications.age(mark.first, mark.second)) }
        assertNull(Certifications.age("US", "NR"))
        assertNull(Certifications.age("CA", "Exempt"))
        // "R" means 15, 17 or 18 depending on the country: no generic age
        assertNull(Certifications.age("NZ", "R"))
        assertEquals("TP", Certifications.label("ES", "ERI"))
        assertEquals("11", Certifications.label("SE", "Från 11 år"))
        assertEquals("Btl", Certifications.label("SE", "Btl"))
        // Denmark's "F" is exempt, not an age
        val danish = obj("""{"release_dates":{"results":[{"iso_3166_1":"DK","release_dates":[{"type":3,"release_date":"2024-01-01T00:00:00.000Z","certification":"F"}]}]}}""")
        assertNull(Certifications.of(MediaType.MOVIE, danish, "DK"))
    }
}
