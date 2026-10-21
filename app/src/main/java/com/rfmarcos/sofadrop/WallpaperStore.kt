package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredWallpaper(
    val file: String,
    val title: String,
    val type: String,
    val tmdbId: Int,
    /** Key of the list it came from (empty in sets made before it was stored). */
    val source: String = "",
)

/**
 * Generated wallpapers live in files/wallpapers/<generation>/ with an index.json.
 * A new generation is written to its own folder and swapped in atomically.
 */
class WallpaperStore(private val context: Context) {

    private val root = File(context.filesDir, "wallpapers")
    private val currentFile = File(root, "current.txt")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun newGeneration(): File =
        File(root, "gen-${System.currentTimeMillis()}").apply { mkdirs() }

    /** Make [generation] the current set and delete the previous ones. */
    fun publish(generation: File, wallpapers: List<StoredWallpaper>) {
        File(generation, INDEX).writeText(json.encodeToString(wallpapers))
        val tmp = File(root, "current.tmp")
        tmp.writeText(generation.name)
        tmp.renameTo(currentFile)
        root.listFiles()?.filter { it.isDirectory && it.name != generation.name }?.forEach { it.deleteRecursively() }
    }

    fun discard(generation: File) {
        generation.deleteRecursively()
    }

    private fun currentDir(): File? =
        currentFile.takeIf { it.exists() }?.readText()?.trim()?.let { File(root, it) }?.takeIf { it.isDirectory }

    fun current(): List<StoredWallpaper> {
        val dir = currentDir() ?: return emptyList()
        val index = File(dir, INDEX).takeIf { it.exists() } ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredWallpaper>>(index.readText()).filter { File(dir, it.file).exists() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fileOf(wallpaper: StoredWallpaper): File? = currentDir()?.let { File(it, wallpaper.file) }

    /** content:// URI readable by the packages it is granted to. */
    fun uriOf(wallpaper: StoredWallpaper, grantTo: Collection<String>): Uri? {
        val file = fileOf(wallpaper) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        grantTo.forEach {
            try {
                context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Package not installed: nothing to grant
            }
        }
        return uri
    }

    companion object {
        private const val INDEX = "index.json"
    }
}
