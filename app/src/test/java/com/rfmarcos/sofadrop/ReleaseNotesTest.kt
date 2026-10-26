package com.rfmarcos.sofadrop

import com.rfmarcos.sofadrop.Updater.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release notes as written on GitHub (v1.1.0's layout), turned into the update screen's plain text. */
class ReleaseNotesTest {

    @Test
    fun sectionsAndItems() {
        val markdown = "### New\r\n" +
            "- **Ratings with logos**: IMDb, Rotten Tomatoes and `TMDB`, each with the platform's logo.\r\n" +
            "- **Header** (*Wallpaper details*): choose the order of the line under the title.\r\n" +
            "- **Age rating** of your streaming country next to the year.\r\n" +
            "\r\n" +
            "## Improvements\r\n" +
            "* Faster: see [the README](https://github.com/rfmarcos/projectivy-plugin-sofadrop#readme) for __details__.\r\n" +
            "- Keeps snake_case_names and 5 * 3 as they are.\r\n" +
            "\r\n" +
            "Install with **Downloader code 7882519**, or download **sofadrop.apk** below.\r\n" +
            "\r\n" +
            "SHA-256: `5df401b2b069bbb171207958b6bb763ef0b87c8692c5e2d0b81318e99193b055`\r\n"
        assertEquals(
            listOf(
                Note("New", heading = true),
                Note("Ratings with logos", "IMDb, Rotten Tomatoes and TMDB, each with the platform's logo."),
                Note("Header (Wallpaper details)", "Choose the order of the line under the title."),
                Note("", "Age rating of your streaming country next to the year."),
                Note("Improvements", heading = true),
                Note("", "Faster: see the README for details."),
                Note("", "Keeps snake_case_names and 5 * 3 as they are."),
            ),
            Updater.releaseNotes(markdown),
        )
    }

    @Test
    fun generatedChangelogAndEmptyBody() {
        val markdown = "**Full Changelog**: https://github.com/rfmarcos/projectivy-plugin-sofadrop/compare/v1.0.2...v1.1.0"
        assertTrue(Updater.releaseNotes(markdown).isEmpty())
        assertTrue(Updater.releaseNotes("").isEmpty())
        assertTrue(Updater.releaseNotes("\n  \n").isEmpty())
    }
}
