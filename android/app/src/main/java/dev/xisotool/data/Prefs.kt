package dev.xisotool.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What to do with the original ISO once an edited / rebuilt ISO has been written successfully. */
enum class AfterEdit(val key: String, val title: String, val description: String) {
    ASK("ask", "Ask me every time", "You choose after each successful rebuild."),
    KEEP_BAK("bak", "Keep the original as .bak", "The new ISO takes the original name; the old one becomes name.iso.bak."),
    REPLACE("replace", "Replace the original", "The old ISO is deleted once the new one is complete. Uses the least storage."),
    SAVE_NEW("new", "Save as a new ISO", "The original is untouched; the result is saved as \"name (edited).iso\" next to it.");

    companion object {
        fun fromKey(k: String?): AfterEdit = values().firstOrNull { it.key == k } ?: ASK
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    var afterEdit by mutableStateOf(AfterEdit.ASK)
        private set
    var mediaPatch by mutableStateOf(true)
        private set
    var skipSystemUpdate by mutableStateOf(false)
        private set
    var keepScreenOn by mutableStateOf(true)
        private set
    var lastDir by mutableStateOf<String?>(null)
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("xiso_prefs", Context.MODE_PRIVATE)
        afterEdit = AfterEdit.fromKey(sp.getString("after_edit", null))
        mediaPatch = sp.getBoolean("media_patch", true)
        skipSystemUpdate = sp.getBoolean("skip_su", false)
        keepScreenOn = sp.getBoolean("keep_screen_on", true)
        lastDir = sp.getString("last_dir", null)
    }

    fun changeAfterEdit(v: AfterEdit) { afterEdit = v; sp.edit().putString("after_edit", v.key).apply() }
    fun changeMediaPatch(v: Boolean) { mediaPatch = v; sp.edit().putBoolean("media_patch", v).apply() }
    fun changeSkipSystemUpdate(v: Boolean) { skipSystemUpdate = v; sp.edit().putBoolean("skip_su", v).apply() }
    fun changeKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("keep_screen_on", v).apply() }
    fun changeLastDir(v: String) { lastDir = v; sp.edit().putString("last_dir", v).apply() }
}
