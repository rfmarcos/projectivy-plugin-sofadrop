package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Projectivy backup (getPreferences / setPreferences) carries, and what it never does. */
class SettingsBackupTest {

    private val secrets = mapOf(
        "tmdb_token" to "eyJhbGciOiJIUzI1NiJ9.tmdb-read-access-token",
        "trakt_client_id" to "trakt-client-id",
        "trakt_client_secret" to "trakt-client-secret",
        "mdblist_api_key" to "mdblist-api-key",
    )

    private val choices = mapOf(
        "language" to "en-US",
        "titles_per_list" to 20,
        "global_hits_only" to true,
        "lists" to setOf("tmdb:trending_movies", "trakt:watchlist_movies"),
    )

    private val state = mapOf(
        "_trakt_access_token" to "trakt-access-token",
        "_mdblist_refresh_token" to "mdblist-refresh-token",
        "_last_generation" to 1761560000000L,
    )

    private fun keys(json: String) = (Json.parseToJsonElement(json) as JsonObject).keys

    @Test
    fun exportLeavesOutKeysAndConnections() {
        val exported = keys(Settings.exportJson(secrets + choices + state))
        assertEquals(choices.keys, exported)
    }

    @Test
    fun importIgnoresKeysAndConnections() {
        // A backup from an earlier version, or any app calling setPreferences, can't replace them
        val backup = Settings.exportJson(choices).removeSuffix("}") +
            secrets.entries.joinToString("") { (key, value) -> ""","$key":"$value"""" } +
            ""","_trakt_access_token":"injected"}"""
        assertEquals(choices.keys, Settings.importValues(backup).keys)
    }

    @Test
    fun choicesSurviveARoundTrip() {
        assertEquals(choices, Settings.importValues(Settings.exportJson(choices)))
    }

    @Test
    fun onlyChoicesAreBackedUp() {
        secrets.keys.forEach { assertFalse(it, Settings.inBackup(it)) }
        state.keys.forEach { assertFalse(it, Settings.inBackup(it)) }
        choices.keys.forEach { assertTrue(it, Settings.inBackup(it)) }
    }
}
