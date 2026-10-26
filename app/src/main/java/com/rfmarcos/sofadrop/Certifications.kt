package com.rfmarcos.sofadrop

import kotlinx.serialization.json.JsonObject

/**
 * Age certifications from the TMDB details (`release_dates` for movies, `content_ratings` for TV,
 * appended to the details request). Shown for the streaming country only, since mixing systems
 * ("TV-MA" next to "16") confuses; the age filter falls back to the US one, which TMDB has far more often.
 */
object Certifications {
    /** Release types whose certification is preferred: theatrical, limited, digital, physical, TV, premiere. */
    private val RELEASE_TYPES = listOf(3, 2, 4, 5, 6, 1)

    /** Marks that rate nothing (not rated, exempt). */
    private val UNRATED = setOf("NR", "UR", "E", "EXEMPT", "UNRATED")
    /** Marks that rate nothing in their country only (Denmark's "F" is exempt; Chile's is 10+; Romania's "IC" bans it). */
    private val COUNTRY_UNRATED = setOf("DK:F", "RO:IC")

    /** Minimum ages of the marks without their age in them, by "country:mark" or, for any country, by mark. */
    private val AGES = mapOf(
        "US:PG" to 10, "US:NC-17" to 18, "US:TV-PG" to 10, "US:TV-MA" to 17,
        "GB:PG" to 8, "CA:PG" to 8, "CA:R" to 18, "CA:A" to 18, "CA:C" to 0,
        "ES:A" to 0, "ES:AI" to 0, "ES:X" to 18, "ES:ERI" to 0,
        "MX:A" to 0, "MX:B" to 12, "MX:C" to 18, "MX:D" to 18,
        "AR:C" to 18, "CL:F" to 10, "CL:R" to 15, "CL:A" to 18,
        "PT:P" to 18, "PT:PÚBLICOS" to 0, "IT:BA" to 6, "US:R" to 17, "MX:AA" to 0,
        "SE:BTL" to 0, "NO:A" to 0, "DK:A" to 0, "FI:S" to 0, "FI:KK" to 18,
        "HU:KN" to 0, "HU:CHILDREN" to 0, "HU:X" to 18, "RO:AP" to 0, "GR:K" to 0,
        // Uppercased with the root locale, so the dotted İ stays and i becomes I
        "TR:GENEL İZLEYICI" to 0, "TR:GENEL İZLEYICI KITLESI" to 0,
        // Marks that mean the same wherever they're used; "R" and "AA" don't, so they only count per country
        "G" to 0, "U" to 0, "TV-Y" to 0, "TV-G" to 0, "TP" to 0, "T" to 0, "L" to 0, "ATP" to 0, "TE" to 0, "I" to 0,
        "AL" to 0,
    )

    /** The certification of [country] as TMDB writes it, or null when it has none. */
    fun of(type: MediaType, item: JsonObject, country: String): String? {
        val mark = if (type == MediaType.MOVIE) {
            item.obj("release_dates")?.objects("results")?.firstOrNull { it.str("iso_3166_1") == country }
                ?.objects("release_dates").orEmpty()
                .filter { !it.str("certification").isNullOrBlank() }
                .minByOrNull { RELEASE_TYPES.indexOf(it.int("type")).let { index -> if (index < 0) RELEASE_TYPES.size else index } }
                ?.str("certification")
        } else {
            item.obj("content_ratings")?.objects("results")?.firstOrNull { it.str("iso_3166_1") == country }?.str("rating")
        }
        return mark?.trim()?.takeIf { it.isNotEmpty() && it.uppercase() !in UNRATED && "$country:${it.uppercase()}" !in COUNTRY_UNRATED }
    }

    /**
     * The mark drawn on the wallpaper: Spain's "A" and "ERI" (all ages) are shown as its official "TP",
     * Sweden's TV "Från 11 år" as its cinema "11", Turkey's "Genel İzleyici (Kitlesi)" (all ages) as "Genel".
     */
    fun label(country: String, mark: String): String = when {
        country == "ES" && mark.uppercase() in setOf("A", "AI", "ERI") -> "TP"
        country == "TR" && mark.startsWith("Genel", ignoreCase = true) -> "Genel"
        country == "SE" -> Regex("^Från (\\d+) år$", RegexOption.IGNORE_CASE).find(mark)?.groupValues?.get(1) ?: mark
        else -> mark
    }

    /** Minimum age of a mark ("PG-13" -> 13, "M/16" -> 16, "TP" -> 0), or null when it is unknown. */
    fun age(country: String, mark: String): Int? {
        val key = mark.trim().uppercase()
        if (key in UNRATED) return null
        return AGES["$country:$key"] ?: AGES[key] ?: Regex("\\d{1,2}").find(key)?.value?.toInt()
    }

    /** Minimum age for the age filter: [country]'s certification, else the US one; null when neither is known. */
    fun minimumAge(type: MediaType, item: JsonObject, country: String): Int? =
        listOf(country, "US").distinct().firstNotNullOfOrNull { c -> of(type, item, c)?.let { age(c, it) } }
}
