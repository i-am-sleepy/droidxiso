@file:OptIn(ExperimentalMaterial3Api::class)

package dev.xisotool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xisotool.Dest
import dev.xisotool.core.IsoOps
import dev.xisotool.core.OperationManager
import dev.xisotool.data.AfterEdit
import dev.xisotool.data.Prefs
import dev.xisotool.util.fmtSize
import dev.xisotool.util.isoFolderName
import dev.xisotool.util.uniqueFile
import java.io.File

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActionCard(icon: @Composable () -> Unit, title: String, text: String, onClick: () -> Unit) {
    XCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VectorIcon(v: ImageVector) {
    Icon(v, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
}

@Composable
fun HomeScreen(onOpen: (Dest) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("XISO Tool", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Works directly on your files - nothing is copied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onOpen(Dest.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(4.dp))

        ActionCard({ VectorIcon(XIcons.Download) }, "Extract ISO", "Unpack an ISO into a folder named after it, next to the ISO.") { onOpen(Dest.Extract) }
        ActionCard({ VectorIcon(XIcons.Upload) }, "Build ISO", "Turn a folder into an ISO with the matching name.") { onOpen(Dest.Build) }
        ActionCard({ VectorIcon(XIcons.Folder) }, "Browse & edit ISO", "See everything inside. Extract, delete or replace single files, then rebuild.") { onOpen(Dest.Browse) }
        ActionCard({ VectorIcon(XIcons.Disc) }, "Optimize ISO", "Rewrite an ISO cleanly (also strips the extra video partition of full dumps).") { onOpen(Dest.Optimize) }
        ActionCard(
            {
                Text(">_", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            },
            "Command line",
            "Run the original extract-xiso with its own options.",
        ) { onOpen(Dest.Terminal) }
    }
}

// ---------------------------------------------------------------------------------------------
// Extract
// ---------------------------------------------------------------------------------------------

@Composable
fun ExtractScreen(onBack: () -> Unit) {
    var iso by remember { mutableStateOf<File?>(null) }
    var customParent by remember { mutableStateOf<File?>(null) }
    var skipSu by remember { mutableStateOf(Prefs.skipSystemUpdate) }
    var pickIso by remember { mutableStateOf(false) }
    var pickDest by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<File?>(null) }

    val isoFile = iso
    val dest: File? = isoFile?.let { File(customParent ?: it.parentFile ?: File("/"), isoFolderName(it)) }

    fun launch(i: File, d: File) {
        val skip = skipSu
        OperationManager.start("Extracting ${i.name}") { sink -> IsoOps.extract(sink, i, d, skip, null) }
    }

    fun begin() {
        val i = isoFile ?: return
        val d = dest ?: return
        when {
            d.isFile -> launch(i, uniqueFile(d.parentFile ?: File("/"), d.name, ""))
            d.isDirectory && !d.list().isNullOrEmpty() -> conflict = d
            else -> launch(i, d)
        }
    }

    ScreenScaffold("Extract ISO", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PathCard("ISO file", isoFile?.path, "No ISO chosen yet", if (isoFile == null) "Choose ISO…" else "Change…") { pickIso = true }

            if (dest != null) {
                PathCard("Will be extracted to", dest.path, "", "Change location…") { pickDest = true }
                Note("A folder named after the ISO is created in the chosen location. Existing files are never touched unless you pick \"merge\".")
            }

            XCard {
                ToggleRow("Skip \$SystemUpdate", "Leave out the console update files (saves space).", skipSu) { skipSu = it }
            }

            PrimaryAction("Extract", enabled = isoFile != null && !OperationManager.busy) { begin() }
        }
    }

    if (pickIso) {
        FilePickerDialog(PickMode.ISO_FILE, "Choose an ISO", onDismiss = { pickIso = false }) { f ->
            iso = f
            customParent = null
            pickIso = false
        }
    }
    if (pickDest) {
        FilePickerDialog(PickMode.FOLDER, "Extract into which folder?", onDismiss = { pickDest = false }) { f ->
            customParent = f
            pickDest = false
        }
    }
    val c = conflict
    if (c != null && isoFile != null) {
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Folder already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${c.name}\" already contains files.")
                    Button(
                        onClick = {
                            val n = uniqueFile(c.parentFile ?: File("/"), c.name, "")
                            conflict = null
                            launch(isoFile, n)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create a new folder") }
                    OutlinedButton(
                        onClick = {
                            conflict = null
                            launch(isoFile, c)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Merge into it (overwrites same names)") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------------------------

@Composable
fun BuildScreen(onBack: () -> Unit) {
    var folder by remember { mutableStateOf<File?>(null) }
    var media by remember { mutableStateOf(Prefs.mediaPatch) }
    var pick by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<File?>(null) }

    val src = folder
    val target: File? = src?.let { File(it.parentFile ?: File("/"), it.name + ".iso") }
    val hasBootFile = src?.list()?.any { it.equals("default.xbe", true) || it.equals("default.xex", true) }

    fun launch(f: File, t: File) {
        val patch = media
        OperationManager.start("Building ${t.name}") { sink -> IsoOps.build(sink, f, t, patch) }
    }

    fun begin() {
        val f = src ?: return
        val t = target ?: return
        if (t.exists()) conflict = t else launch(f, t)
    }

    ScreenScaffold("Build ISO", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PathCard("Folder to build from", src?.path, "No folder chosen yet", if (src == null) "Choose folder…" else "Change…") { pick = true }

            if (target != null) {
                PathCard("ISO that will be created", target.path, "", "", null)
                if (hasBootFile == false) {
                    Note("Heads up: this folder has no default.xbe / default.xex at its top level, so the ISO probably will not boot.")
                }
            }

            XCard {
                ToggleRow(
                    "Patch .xbe media flags",
                    "Applies the usual media-enable patch so discs are not region/media locked. Same as upstream's default.",
                    media,
                ) { media = it }
            }

            PrimaryAction("Build ISO", enabled = src != null && !OperationManager.busy) { begin() }
        }
    }

    if (pick) {
        FilePickerDialog(PickMode.FOLDER, "Choose the game folder", onDismiss = { pick = false }) { f ->
            folder = f
            pick = false
        }
    }
    val c = conflict
    if (c != null && src != null) {
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("${c.name} already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The existing file (${fmtSize(c.length())}) is only replaced once the new ISO is complete.")
                    Button(
                        onClick = {
                            val n = uniqueFile(c.parentFile ?: File("/"), c.name.removeSuffix(".iso"), ".iso")
                            conflict = null
                            launch(src, n)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Keep both (new name)") }
                    OutlinedButton(
                        onClick = {
                            conflict = null
                            launch(src, c)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Replace the existing ISO") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Optimize (rewrite)
// ---------------------------------------------------------------------------------------------

@Composable
fun OptimizeScreen(onBack: () -> Unit) {
    var iso by remember { mutableStateOf<File?>(null) }
    var pick by remember { mutableStateOf(false) }
    val f = iso

    ScreenScaffold("Optimize ISO", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Note(
                "Rewrites the ISO from scratch in the clean layout the console expects. " +
                    "Full redumps lose their extra video partition, so the result is usually much smaller. " +
                    "The file contents stay the same.",
            )
            PathCard("ISO file", f?.path, "No ISO chosen yet", if (f == null) "Choose ISO…" else "Change…") { pick = true }
            if (f != null) Note("Needs free space of about ${fmtSize(f.length())} for the new file while it is written.")
            PrimaryAction("Rewrite ISO", enabled = f != null && !OperationManager.busy) {
                val file = f ?: return@PrimaryAction
                val patch = Prefs.mediaPatch
                val policy = Prefs.afterEdit
                OperationManager.start("Optimizing ${file.name}") { sink ->
                    IsoOps.rebuild(sink, file, emptyList(), file.length(), patch, policy)
                }
            }
        }
    }

    if (pick) {
        FilePickerDialog(PickMode.ISO_FILE, "Choose an ISO", onDismiss = { pick = false }) { x ->
            iso = x
            pick = false
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    ScreenScaffold("Settings", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XCard {
                Text("After editing or rebuilding an ISO", style = MaterialTheme.typography.titleMedium)
                Text(
                    "What happens to the original once the new ISO has been written successfully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (opt in AfterEdit.values()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { Prefs.setAfterEdit(opt) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = Prefs.afterEdit == opt, onClick = { Prefs.setAfterEdit(opt) })
                        Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
                            Text(opt.title, style = MaterialTheme.typography.bodyLarge)
                            Text(opt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            XCard {
                ToggleRow(
                    "Patch .xbe media flags when building",
                    "Used by Build, Optimize and edits. Matches upstream's default behaviour.",
                    Prefs.mediaPatch,
                ) { Prefs.setMediaPatch(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    "Skip \$SystemUpdate by default",
                    "Pre-selects the option on the Extract screen.",
                    Prefs.skipSystemUpdate,
                ) { Prefs.setSkipSystemUpdate(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    "Keep the screen on during operations",
                    null,
                    Prefs.keepScreenOn,
                ) { Prefs.setKeepScreenOn(it) }
            }

            XCard {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("XISO Tool $version", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is an Android app built around the original extract-xiso by in (XboxDev/extract-xiso on GitHub). " +
                        "The original program code is used unmodified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This product includes software developed by in <in@fishtank.com>.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Copyright (c) 2003 in. Distributed under the licence in LICENSE.TXT of the original project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
