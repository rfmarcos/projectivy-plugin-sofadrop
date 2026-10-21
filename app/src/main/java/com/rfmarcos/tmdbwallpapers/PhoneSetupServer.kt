package com.rfmarcos.tmdbwallpapers

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny web server on the local network, running only while the "set up from your phone" screen
 * is open: the phone scans a QR code and fills a form instead of typing keys with the remote.
 * Requests must carry a random one-time key that only appears in the QR code.
 */
class PhoneSetupServer(private val context: Context, private val onSaved: (List<String>) -> Unit) {

    private val key = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    private var server: ServerSocket? = null

    /** Start listening; returns the URL to put in the QR code, or null without a local network. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val socket = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
        server = socket
        thread(name = "phone-setup", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { handle(it) } }
            }
        }
        return "http://$ip:${socket.localPort}/?k=$key"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val (method, target) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
            val query = parseForm(target.substringAfter('?', ""))
            val body = if (method == "POST" && contentLength in 1..16_384) {
                // The form is url-encoded ASCII, so bytes == chars; read until complete
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(buffer, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                parseForm(String(buffer, 0, read))
            } else emptyMap()

            val html = when {
                (query["k"] ?: body["k"]) != key -> page(context.getString(R.string.phone_invalid), "")
                method == "POST" -> page(
                    context.getString(R.string.phone_saved),
                    "<p>" + escape(context.getString(R.string.phone_saved_desc, save(body).joinToString(", ").ifEmpty { "—" })) + "</p>",
                )
                else -> form()
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        } catch (e: Exception) {
            Log.w("PhoneSetupServer", "Request failed", e)
        }
    }

    /** Store the non-empty fields; returns the names of what changed. */
    private fun save(form: Map<String, String>): List<String> {
        Settings.init(context)
        val changed = mutableListOf<String>()
        form["tmdb"]?.takeIf { it.isNotBlank() }?.let { Settings.token = it; changed += "TMDB" }
        form["mdblist"]?.takeIf { it.isNotBlank() }?.let {
            Settings.mdbListKey = it
            runCatching { Settings.mdbLists = (MdbListClient(apiKey = it).userLists() + Settings.mdbLists).distinctBy { list -> list.id } }
            changed += "MDBList"
        }
        form["mdblist_list"]?.takeIf { it.isNotBlank() }?.let { reference ->
            runCatching { MdbListClient.fromSettings()?.findList(reference) }.getOrNull()?.let { list ->
                Settings.mdbLists = (Settings.mdbLists + list).distinctBy { it.id }
                Settings.lists = Settings.lists + "mdblist:${list.id}"
                changed += list.name
            }
        }
        form["trakt_id"]?.takeIf { it.isNotBlank() }?.let {
            if (it != Settings.traktClientId) Settings.clearTraktSession()
            Settings.traktClientId = it
            changed += "Trakt Client ID"
        }
        form["trakt_secret"]?.takeIf { it.isNotBlank() }?.let { Settings.traktClientSecret = it; changed += "Trakt Client Secret" }
        if (changed.isNotEmpty()) onSaved(changed)
        return changed
    }

    private fun form(): String {
        fun field(name: String, label: Int, hint: String, current: String) = """
            <label>${escape(context.getString(label))}<small>${escape(if (current.isBlank()) context.getString(R.string.not_set) else context.getString(R.string.phone_current, "…" + current.takeLast(4)))}</small>
            <input name="$name" placeholder="${escape(hint)}" autocomplete="off" autocapitalize="off" spellcheck="false"></label>"""
        return page(
            context.getString(R.string.phone_title),
            """<p>${escape(context.getString(R.string.phone_intro))}</p>
            <form method="post"><input type="hidden" name="k" value="$key">
            ${field("tmdb", R.string.setting_token_title, "API key / Read Access Token", Settings.token)}
            ${field("mdblist", R.string.phone_mdblist_key, "API key", Settings.mdbListKey)}
            ${field("mdblist_list", R.string.mdblist_add, context.getString(R.string.mdblist_add_desc), "")}
            <details><summary>${escape(context.getString(R.string.phone_trakt_advanced))}</summary>
            ${field("trakt_id", R.string.trakt_client_id, "Client ID", Settings.traktClientId)}
            ${field("trakt_secret", R.string.trakt_client_secret, "Client Secret", Settings.traktClientSecret)}
            </details>
            <button type="submit">${escape(context.getString(R.string.phone_save))}</button></form>""",
        )
    }

    private fun page(title: String, content: String) = """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title><style>
        body{margin:0;padding:24px 16px;font-family:system-ui,sans-serif;background:#0b0f17;color:#e8edf5}
        h1{font-weight:300;color:#4fb3e8;font-size:26px;margin:0 0 8px}p{color:#a9b4c4;line-height:1.5}
        label{display:block;margin:18px 0 6px;font-weight:600}small{display:block;font-weight:400;color:#7f8aa0;margin:2px 0 6px}
        input{box-sizing:border-box;width:100%;padding:14px;border-radius:12px;border:1px solid #2a3446;background:#141b27;color:#fff;font-size:16px}
        details{margin-top:18px;color:#a9b4c4}button{margin-top:24px;width:100%;padding:16px;border:0;border-radius:28px;background:#4fb3e8;color:#08111c;font-size:17px;font-weight:700}
        </style></head><body><h1>${escape(title)}</h1>$content</body></html>"""

    companion object {
        private const val PORT = 8642
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        private fun escape(text: String) =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun parseForm(text: String): Map<String, String> =
            text.split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8").trim()
            }

        /** IPv4 address of the TV on the local network (Wi-Fi or Ethernet). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
