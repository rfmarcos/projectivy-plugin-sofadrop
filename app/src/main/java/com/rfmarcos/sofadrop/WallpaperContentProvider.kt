package com.rfmarcos.sofadrop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the generated wallpapers to Projectivy, read-only and without per-URI grants: Android drops
 * those grants whenever Sofadrop is updated or force-stopped, and Projectivy then shows its own
 * wallpaper until it next asks for the list. Only JPEGs under files/wallpapers are reachable, and they
 * are public movie and TV art.
 */
class WallpaperContentProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        val root = File(requireNotNull(context).filesDir, WallpaperStore.DIR)
        val file = resolve(root, uri.path.orEmpty()) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** The wallpaper [path] points to inside [root]; null for anything else (other files, "..", folders). */
        fun resolve(root: File, path: String): File? {
            val base = root.canonicalFile
            val file = File(base, path.removePrefix("/")).canonicalFile
            return file.takeIf { it.path.startsWith(base.path + File.separator) && it.extension == "jpg" && it.isFile }
        }
    }
}
