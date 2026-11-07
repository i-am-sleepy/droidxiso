package dev.xisotool.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

object Perm {
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Opens the system screen where "All files access" is granted (Android 11+). */
    fun openAllFilesSettings(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + ctx.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                // nothing else we can do
            }
        }
    }
}

fun fmtSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    do {
        v /= 1024.0
        i++
    } while (v >= 1024.0 && i < units.size - 1)
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

/** Internal storage plus any SD card / USB volumes. */
fun storageRoots(ctx: Context): List<Pair<String, File>> {
    val primary = Environment.getExternalStorageDirectory()
    val out = mutableListOf<Pair<String, File>>()
    out.add(Pair("Internal storage", primary))
    try {
        for (f in ctx.getExternalFilesDirs(null)) {
            if (f == null) continue
            val idx = f.absolutePath.indexOf("/Android/data")
            if (idx <= 0) continue
            val root = File(f.absolutePath.substring(0, idx))
            if (root.absolutePath != primary.absolutePath && out.none { it.second.absolutePath == root.absolutePath }) {
                out.add(Pair("SD card / USB", root))
            }
        }
    } catch (e: Exception) {
        // ignore - internal storage is still available
    }
    return out
}

fun isIsoName(name: String): Boolean {
    val l = name.lowercase(Locale.ROOT)
    return l.endsWith(".iso") || l.endsWith(".xiso")
}

/** "Halo.iso" -> "Halo". Files without an extension get a suffix so the folder name never equals the file name. */
fun isoFolderName(isoFile: File): String {
    val n = isoFile.name
    val dot = n.lastIndexOf('.')
    return if (dot > 0) n.substring(0, dot) else "$n (extracted)"
}

/** First name that does not exist yet: stem+ext, "stem (2)"+ext, ... */
fun uniqueFile(dir: File, stem: String, ext: String): File {
    var f = File(dir, stem + ext)
    var i = 2
    while (f.exists()) {
        f = File(dir, "$stem ($i)$ext")
        i++
    }
    return f
}

/** Nearest existing parent (used to ask the file system for free space). */
fun existingParent(f: File): File {
    var cur: File? = f
    while (cur != null && !cur.exists()) cur = cur.parentFile
    return cur ?: File("/")
}

fun hintFor(lines: List<String>): String? {
    val all = lines.joinToString("\n").lowercase(Locale.ROOT)
    return when {
        "no space left" in all -> "The storage ran out of space."
        "file too large" in all ->
            "The destination refuses files this big. SD cards formatted as FAT32 cannot hold files over 4 GB - use internal storage or an exFAT card."
        "read-only" in all -> "The destination is read-only."
        "permission denied" in all || "operation not permitted" in all ->
            "Android blocked access to a file or folder. Check that \"All files access\" is still enabled for this app."
        "does not appear to be a valid xbox iso" in all -> "This file is not an Xbox / Xbox 360 xiso image."
        else -> null
    }
}

/** Splits a command line like a simple shell: spaces separate, quotes group. */
fun splitArgs(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quote = 0.toChar()
    var any = false
    for (c in s) {
        when {
            quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else cur.append(c)
            c == '"' || c == '\'' -> { quote = c; any = true }
            c == ' ' || c == '\t' || c == '\n' -> if (cur.isNotEmpty() || any) { out.add(cur.toString()); cur.setLength(0); any = false }
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty() || any) out.add(cur.toString())
    return out
}
