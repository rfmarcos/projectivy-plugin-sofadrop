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

/** Language of the title (logo or text) and of the synopsis: a TMDB locale, or null for the title's original language. */
data class PartLanguages(val title: String?, val synopsis: String?)

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
        "sv-SE" to "Svenska",
        // TMDB keeps Norwegian under "no" (its "nb-NO" has no synopses)
        "no-NO" to "Norsk",
        "da-DK" to "Dansk",
        "fi-FI" to "Suomi",
    )

    /** Countries offered for streaming services. */
    val REGIONS = listOf("US", "GB", "CA", "ES", "MX", "AR", "CO", "CL", "BR", "PT", "FR", "IT", "DE", "SE", "NO", "DK", "FI")

    /** Original languages that never need the "global hit" vote threshold. */
    val MAINSTREAM = setOf("en", "es", "fr", "it", "de", "pt", "ca", "nl", "sv", "da", "no", "pl")

    private val TEXTS = mapOf(
        "en" to WallpaperTexts("Available on", "Season", "Seasons", "Watched", "Watching · %d/%d"),
        "es" to WallpaperTexts("Disponible en", "Temporada", "Temporadas", "Visto", "Viendo · %d/%d"),
        "pt" to WallpaperTexts("Disponível em", "Temporada", "Temporadas", "Assistido", "Assistindo · %d/%d"),
        "fr" to WallpaperTexts("Disponible sur", "Saison", "Saisons", "Vu", "En cours · %d/%d"),
        "it" to WallpaperTexts("Disponibile su", "Stagione", "Stagioni", "Visto", "In corso · %d/%d"),
        "de" to WallpaperTexts("Verfügbar auf", "Staffel", "Staffeln", "Gesehen", "Angefangen · %d/%d"),
        "sv" to WallpaperTexts("Finns på", "Säsong", "Säsonger", "Sedd", "Påbörjad · %d/%d"),
        "no" to WallpaperTexts("Tilgjengelig på", "Sesong", "Sesonger", "Sett", "Påbegynt · %d/%d"),
        "da" to WallpaperTexts("Tilgængelig på", "Sæson", "Sæsoner", "Set", "I gang · %d/%d"),
        "fi" to WallpaperTexts("Saatavilla", "Kausi", "Kautta", "Katsottu", "Kesken · %d/%d"),
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
        "sv" to listOf("Trendar nu", "Populära", "Högst betyg", "På bio", "Kommer snart", "Sänds nu", "Mest efterlängtade",
            "Biotoppen", "Utvalda för dig", "Din bevakningslista", "Dina favoriter", "Trendande anime", "Vill se", "Påbörjade"),
        "no" to listOf("Trender nå", "Populære", "Best vurdert", "På kino", "Kommer snart", "Sendes nå", "Mest etterlengtet",
            "Kinotoppen", "Valgt for deg", "Din liste", "Dine favoritter", "Trendende anime", "Vil se", "Påbegynt"),
        "da" to listOf("Trender nu", "Populære", "Bedst bedømte", "I biografen", "Kommer snart", "Sendes nu", "Mest ventede",
            "Biograftoppen", "Valgt til dig", "Din liste", "Dine favoritter", "Trendende anime", "Vil se", "I gang"),
        "fi" to listOf("Nousussa", "Suositut", "Parhaiksi arvioidut", "Elokuvateattereissa", "Tulossa", "Nyt esityksessä", "Odotetuimmat",
            "Lipputulot", "Valittu sinulle", "Katselulistasi", "Suosikkisi", "Nouseva anime", "Aion katsoa", "Kesken"),
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

    // TMDB returns some genres untranslated (mostly the TV-only ones) or, in Norwegian, blank; by genre id
    private val GENRE_NAMES = mapOf(
        "es" to mapOf(
            10759 to "Acción y aventura", 10765 to "Ciencia ficción y fantasía", 10768 to "Bélica y política",
            10762 to "Infantil", 10766 to "Telenovela", 10767 to "Entrevistas",
        ),
        "pt" to mapOf(
            10759 to "Ação e aventura", 10765 to "Ficção científica e fantasia", 10768 to "Guerra e política",
            10762 to "Infantil", 10766 to "Novela", 10767 to "Entrevistas",
        ),
        "sv" to mapOf(
            10759 to "Action och äventyr", 10765 to "Sci-fi och fantasy", 10768 to "Krig och politik",
            10762 to "Barn", 10763 to "Nyheter", 10766 to "Såpopera", 10767 to "Talkshow", 10402 to "Musik",
        ),
        "no" to mapOf(
            28 to "Action", 12 to "Eventyr", 16 to "Animasjon", 35 to "Komedie", 80 to "Krim", 99 to "Dokumentar",
            18 to "Drama", 10751 to "Familie", 14 to "Fantasy", 36 to "Historie", 27 to "Skrekk", 10402 to "Musikk",
            9648 to "Mysterium", 10749 to "Romantikk", 878 to "Science fiction", 10770 to "TV-film", 53 to "Thriller",
            10752 to "Krig", 37 to "Western", 10759 to "Action og eventyr", 10762 to "Barn", 10763 to "Nyheter",
            10764 to "Reality", 10765 to "Sci-fi og fantasy", 10766 to "Såpeopera", 10767 to "Talkshow", 10768 to "Krig og politikk",
        ),
        "da" to mapOf(
            99 to "Dokumentar", 10770 to "TV-film", 10765 to "Sci-fi og fantasy", 10768 to "Krig og politik",
            10763 to "Nyheder", 10764 to "Reality", 10766 to "Sæbeopera", 10767 to "Talkshow",
        ),
        "fi" to mapOf(
            10770 to "TV-elokuva", 10759 to "Toiminta ja seikkailu", 10765 to "Sci-fi ja fantasia", 10768 to "Sota ja politiikka",
            10766 to "Saippuasarja", 10767 to "Keskusteluohjelma",
        ),
    )

    fun code(language: String) = language.substringBefore('-').lowercase()

    fun regionOf(language: String) = language.substringAfter('-', "US").uppercase()

    fun texts(language: String) = TEXTS[code(language)] ?: TEXTS.getValue("en")

    /** Genre name in [language]: TMDB's [name] unless it is missing or untranslated; null when there is none. */
    fun genreName(language: String, id: Int?, name: String?): String? =
        (GENRE_NAMES[code(language)]?.get(id) ?: name)?.trim()?.takeIf { it.isNotEmpty() }

    fun defaultFor(locale: Locale): String {
        val tag = "${locale.language}-${locale.country}"
        return when {
            tag in OPTIONS -> tag
            locale.language == "es" -> "es-ES"
            locale.language == "pt" -> "pt-BR"
            locale.language in setOf("nb", "nn", "no") -> "no-NO"
            else -> OPTIONS.keys.firstOrNull { it.startsWith(locale.language + "-") } ?: "en-US"
        }
    }

    fun displayName(language: String) = OPTIONS[language] ?: language

    fun countryName(region: String): String =
        Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.getDefault()).ifEmpty { region }
}
