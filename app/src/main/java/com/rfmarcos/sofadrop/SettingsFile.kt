package com.rfmarcos.sofadrop

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.IOException

/**
 * The settings as a file, to set up another device the same way: what a Projectivy backup carries
 * (see [Settings.export]), so no accounts, API keys or exact location.
 */
object SettingsFile {
    const val NAME = "sofadrop-settings.json"
    const val MIME = "application/json"

    /** Marks the file as Sofadrop's, with the version that made it; "_" keys are never imported. */
    private const val MARKER = "_sofadrop"

    /** The services connected where the file was made, by name only: to say which to connect again. */
    private const val ACCOUNTS = "_accounts"
    private const val MAX_SIZE = 1 shl 20
    private val json = Json { prettyPrint = true }

    fun content(): String {
        val settings = Json.parseToJsonElement(Settings.export()) as JsonObject
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put(MARKER, JsonPrimitive(BuildConfig.VERSION_NAME))
            put(ACCOUNTS, JsonArray(SourceProvider.entries.filter(AccountsFragment::connected).map { JsonPrimitive(it.name) }))
            settings.forEach { (key, value) -> put(key, value) }
        })
    }

    /** The Sofadrop version that made [text], or null when it isn't a Sofadrop settings file. */
    fun version(text: String): String? = runCatching {
        ((Json.parseToJsonElement(text) as JsonObject)[MARKER] as JsonPrimitive).content
    }.getOrNull()

    /** The services [text]'s TV had connected (accounts never travel in the file). */
    fun accounts(text: String): List<SourceProvider> = runCatching {
        ((Json.parseToJsonElement(text) as JsonObject)[ACCOUNTS] as JsonArray).mapNotNull { name ->
            SourceProvider.entries.firstOrNull { it.name == (name as JsonPrimitive).content }
        }
    }.getOrDefault(emptyList())

    /** Android 9 and older need the storage permission to write in Download. */
    val savingNeedsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Writes the file in the shared Download folder; returns its name there (Android may add a number). */
    fun saveToDownloads(context: Context): String {
        val bytes = content().toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No Download folder")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Can't write $uri")
            return displayName(context, uri) ?: NAME
        }
        @Suppress("DEPRECATION")
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        File(folder, NAME).writeBytes(bytes)
        return NAME
    }

    /** A chooser to send the file to another app (a file manager, Google Drive, Send files to TV…), or null if none can. */
    fun shareIntent(context: Context): Intent? {
        val file = File(context.cacheDir, "shared/$NAME").apply { parentFile?.mkdirs(); writeText(content()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (send.resolveActivity(context.packageManager) == null) return null
        return Intent.createChooser(send, null)
    }

    /** Can this device pick a file to import (Google TV has no documents picker)? */
    fun canPick(context: Context): Boolean =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(context.packageManager) != null

    /** The text of the file at [uri], or null when it can't be read (or is too big to be one). */
    fun read(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_SIZE + 1)
            if (bytes.size > MAX_SIZE) null else String(bytes)
        }
    }.getOrNull()

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Replaces this device's settings with [text]'s; the wallpapers are made again when the settings close. */
    fun import(context: Context, text: String): Boolean {
        if (version(text) == null || !Settings.import(text, replace = true)) return false
        // A device set up from a file doesn't need the guided setup
        Settings.wizardShown = true
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        return true
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}
