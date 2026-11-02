package com.rfmarcos.sofadrop

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the last generation did, shown in About for bug reports: device, lists, failures and duration.
 * Never keys or tokens: messages lose their URL queries.
 */
class GenerationLog(private val context: Context, private val profile: Profile = Profile.WALLPAPER) {

    private val started = System.currentTimeMillis()
    private val lines = mutableListOf<String>()

    init {
        add("Sofadrop ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${DATE.format(Date(started))}")
        add("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add(DeviceMemory.describe(context))
        add("${"Screensaver · ".takeIf { profile.isScreensaver }.orEmpty()}${profile.outputWidth} px · ${Settings.language} · ${profile.titlesPerList} per list")
    }

    @Synchronized
    fun add(line: String) {
        lines += line
    }

    /** A list: titles it gave, how many the filters left out and how many were drawn. */
    fun list(key: String, read: Int, filtered: Int, drawn: Int) = add("$key: $read read, $filtered filtered, $drawn drawn")

    fun listFailed(key: String, error: Throwable) = add("$key: not read · ${describe(error)}")

    fun titleFailed(ref: TitleRef, error: Throwable, width: Int? = null) =
        add("  ${ref.type.path}/${ref.tmdbId}${width?.let { " at $it px" }.orEmpty()}: ${describe(error)}")

    /** Write the log with its last line, [result], and how long it all took. */
    fun finish(result: String) {
        add("$result · ${(System.currentTimeMillis() - started + 500) / 1000} s")
        runCatching { file(context, profile).writeText(text()) }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    companion object {
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        /** One per set, so the screensaver's generation doesn't replace the wallpapers' log. */
        private fun file(context: Context, profile: Profile) =
            File(context.filesDir, if (profile.isScreensaver) "last-generation-screensaver.txt" else "last-generation.txt")

        /** [profile]'s last generation log, or null before the first one. */
        fun read(context: Context, profile: Profile = Profile.WALLPAPER): String? =
            file(context, profile).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

        /** The wallpapers' log, then the screensaver's when it has a set of its own; null when there is none. */
        fun readAll(context: Context): String? =
            listOfNotNull(read(context), read(context, Profile.SCREENSAVER)).joinToString("\n\n").ifEmpty { null }

        /** "OutOfMemoryError: Failed to allocate…", with URL queries (where keys could go) left out. */
        fun describe(error: Throwable): String =
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".replace(QUERY, "?…").trimEnd(' ', ':').take(200)

        private val QUERY = Regex("""\?\S*""")
    }
}

/** Memory figures of this device, for the log and the 4K option. */
object DeviceMemory {

    private fun manager(context: Context) = context.getSystemService(ActivityManager::class.java)

    private fun totalMb(context: Context) = ActivityManager.MemoryInfo().also { manager(context).getMemoryInfo(it) }.totalMem / (1024 * 1024)

    fun describe(context: Context): String {
        val manager = manager(context)
        return "Memory: class ${manager.memoryClass} MB, large ${manager.largeMemoryClass} MB, RAM ${totalMb(context)} MB" +
            if (manager.isLowRamDevice) ", low-RAM device" else ""
    }

    /** Devices where a 4K wallpaper (~33 MB, plus the images it's drawn from) may not fit. */
    fun tightFor4k(context: Context): Boolean {
        val manager = manager(context)
        return manager.isLowRamDevice || manager.memoryClass < 128 || totalMb(context) < 1536
    }
}
