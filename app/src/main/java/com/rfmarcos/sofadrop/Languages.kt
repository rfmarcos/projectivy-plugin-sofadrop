package com.rfmarcos.sofadrop

import java.util.Locale

/** Texts drawn on the wallpaper, in the wallpaper language (not the device one). */
data class WallpaperTexts(
    val availableOn: String,
    val season: String,
    val seasons: String,
    val watched: String,
    /** Format with completed and aired episodes, e.g. "Watching · 3/8". */
    val watching: String,
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
        "en" to WallpaperTexts("Available on", "Season", "Seasons", "Watched", "Watching · %d/%d"),
        "es" to WallpaperTexts("Disponible en", "Temporada", "Temporadas", "Visto", "Viendo · %d/%d"),
        "pt" to WallpaperTexts("Disponível em", "Temporada", "Temporadas", "Assistido", "Assistindo · %d/%d"),
        "fr" to WallpaperTexts("Disponible sur", "Saison", "Saisons", "Vu", "En cours · %d/%d"),
        "it" to WallpaperTexts("Disponibile su", "Stagione", "Stagioni", "Visto", "In corso · %d/%d"),
        "de" to WallpaperTexts("Verfügbar auf", "Staffel", "Staffeln", "Gesehen", "Angefangen · %d/%d"),
    )

    /** Short list names for the wallpaper footer ("Trending · TMDB"), by kind of list. */
    private val SOURCE_LABELS = mapOf(
        "en" to listOf("Trending", "Popular", "Top rated", "In cinemas", "Coming soon", "On the air", "Most anticipated",
            "Box office", "Picked for you", "Your watchlist", "Your favourites", "Trending anime", "Plan to watch", "Watching"),
        "es" to listOf("Tendencias", "Populares", "Mejor valoradas", "En cines", "Próximamente", "En emisión", "Más esperadas",
            "Taquilla", "Elegidas para ti", "Tu lista", "Tus favoritas", "Anime en tendencia", "Para ver", "Viendo"),
        "pt" to listOf("Em alta", "Populares", "Mais bem avaliados", "Nos cinemas", "Em breve", "No ar", "Mais aguardados",
            "Bilheteria", "Escolhidos para você", "Sua lista", "Seus favoritos", "Animes em alta", "Para assistir", "Assistindo"),
        "fr" to listOf("Tendances", "Populaires", "Les mieux notés", "Au cinéma", "Prochainement", "En diffusion", "Les plus attendus",
            "Box-office", "Pour vous", "Votre liste", "Vos favoris", "Animés tendance", "À voir", "En cours"),
        "it" to listOf("Di tendenza", "Popolari", "I più votati", "Al cinema", "Prossimamente", "In onda", "I più attesi",
            "Botteghino", "Scelti per te", "La tua lista", "I tuoi preferiti", "Anime di tendenza", "Da vedere", "In corso"),
        "de" to listOf("Im Trend", "Beliebt", "Bestbewertet", "Im Kino", "Demnächst", "Läuft gerade", "Meisterwartet",
            "Kinokassen", "Für dich", "Deine Merkliste", "Deine Favoriten", "Anime im Trend", "Geplant", "Angefangen"),
    )

    /** Same order as each [SOURCE_LABELS] list; matched against the list key without its provider. */
    private val SOURCE_KINDS = listOf(
        "trending", "popular", "top_rated", "now_playing", "upcoming", "on_the_air", "anticipated",
        "boxoffice", "recommended", "watchlist", "favorite", "trending_anime", "plantowatch", "watching",
    )

    /** "Trending", "Your watchlist", or an MDBList list's own name, in the wallpaper language. */
    fun sourceLabel(language: String, source: ListSource): String {
        if (source.provider == SourceProvider.MDBLIST) {
            val name = source.label.orEmpty()
            return if (name.length > 28) name.take(27).trimEnd() + "…" else name
        }
        val key = source.key.substringAfter(':')
        // Longest match first, so "trending_anime" wins over "trending"
        val kind = SOURCE_KINDS.withIndex().filter { key.startsWith(it.value) }.maxByOrNull { it.value.length }?.index ?: 0
        return (SOURCE_LABELS[code(language)] ?: SOURCE_LABELS.getValue("en"))[kind]
    }

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
