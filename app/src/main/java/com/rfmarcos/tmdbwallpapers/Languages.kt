package com.rfmarcos.tmdbwallpapers

import java.util.Locale

/** Texts drawn on the wallpaper, in the wallpaper language (not the device one). */
data class WallpaperTexts(
    val availableOn: String,
    val nowTrendingOn: String,
    val season: String,
    val seasons: String,
)

object Languages {
    /** TMDB language codes offered in the settings, with their native names. */
    val OPTIONS = linkedMapOf(
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "es-ES" to "Español (España)",
        "es-MX" to "Español (México)",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "fr-FR" to "Français",
        "it-IT" to "Italiano",
        "de-DE" to "Deutsch",
    )

    /** Countries offered for streaming services. */
    val REGIONS = listOf("US", "GB", "CA", "ES", "MX", "AR", "CO", "CL", "BR", "PT", "FR", "IT", "DE")

    /** Original languages that never need the "global hit" vote threshold. */
    val MAINSTREAM = setOf("en", "es", "fr", "it", "de", "pt", "ca", "nl", "sv", "da", "no", "pl")

    private val TEXTS = mapOf(
        "en" to WallpaperTexts("Available on", "Now trending on", "Season", "Seasons"),
        "es" to WallpaperTexts("Disponible en", "En tendencia en", "Temporada", "Temporadas"),
        "pt" to WallpaperTexts("Disponível em", "Em alta em", "Temporada", "Temporadas"),
        "fr" to WallpaperTexts("Disponible sur", "Tendance sur", "Saison", "Saisons"),
        "it" to WallpaperTexts("Disponibile su", "Di tendenza su", "Stagione", "Stagioni"),
        "de" to WallpaperTexts("Verfügbar auf", "Im Trend auf", "Staffel", "Staffeln"),
    )

    // TMDB returns some TV-only genres untranslated
    private val TV_GENRE_TRANSLATIONS = mapOf(
        "es" to mapOf(
            "Action & Adventure" to "Acción y aventura",
            "Sci-Fi & Fantasy" to "Ciencia ficción y fantasía",
            "War & Politics" to "Bélica y política",
            "Kids" to "Infantil",
            "Soap" to "Telenovela",
            "Talk" to "Entrevistas",
        ),
        "pt" to mapOf(
            "Action & Adventure" to "Ação e aventura",
            "Sci-Fi & Fantasy" to "Ficção científica e fantasia",
            "War & Politics" to "Guerra e política",
            "Kids" to "Infantil",
            "Soap" to "Novela",
            "Talk" to "Entrevistas",
        ),
    )

    fun code(language: String) = language.substringBefore('-').lowercase()

    fun regionOf(language: String) = language.substringAfter('-', "US").uppercase()

    fun texts(language: String) = TEXTS[code(language)] ?: TEXTS.getValue("en")

    fun translateTvGenre(language: String, genre: String) =
        TV_GENRE_TRANSLATIONS[code(language)]?.get(genre) ?: genre

    fun defaultFor(locale: Locale): String {
        val tag = "${locale.language}-${locale.country}"
        return when {
            tag in OPTIONS -> tag
            locale.language == "es" -> "es-ES"
            locale.language == "pt" -> "pt-BR"
            else -> OPTIONS.keys.firstOrNull { it.startsWith(locale.language + "-") } ?: "en-US"
        }
    }

    fun displayName(language: String) = OPTIONS[language] ?: language

    fun countryName(region: String): String =
        Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.getDefault()).ifEmpty { region }
}
