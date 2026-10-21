package com.rfmarcos.sofadrop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * TMDB user authentication (v3): the user approves a request token on themoviedb.org
 * (opened from a QR code) and the plugin exchanges it for a session.
 */
class TmdbAccount(private val client: TmdbClient) {

    /** New request token and the URL where the user approves it. */
    fun requestToken(): Pair<String, String> {
        val token = client.get("authentication/token/new")?.str("request_token")
            ?: throw IOException("TMDB did not return a request token")
        return token to "https://www.themoviedb.org/authenticate/$token"
    }

    /** Try to create the session; false while the user has not approved the token yet. */
    fun tryCreateSession(requestToken: String): Boolean {
        val (code, body) = client.post("authentication/session/new", buildJsonObject { put("request_token", requestToken) })
        val session = body?.str("session_id")
        if (code != 200 || session.isNullOrBlank()) return false
        val account = client.get("account?session_id=$session")
        Settings.tmdbSessionId = session
        Settings.tmdbAccountId = account?.int("id") ?: 0
        Settings.tmdbUser = account?.str("username").orEmpty()
        return Settings.tmdbAccountId != 0
    }
}
