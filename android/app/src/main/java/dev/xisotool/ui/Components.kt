@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.xisotool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.xisotool.core.OpOutcome
import dev.xisotool.core.OpState
import dev.xisotool.core.OperationManager
import dev.xisotool.data.AfterEdit
import dev.xisotool.data.Prefs
import dev.xisotool.util.fmtSize
import dev.xisotool.util.isIsoName
import dev.xisotool.util.storageRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------------------------
// Basic building blocks
// ---------------------------------------------------------------------------------------------

@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun XCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, shape = shape) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, shape = shape) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

/** A card showing a chosen path (or a placeholder) that opens a picker when tapped. */
@Composable
fun PathCard(label: String, value: String?, placeholder: String, action: String, onClick: (() -> Unit)?) {
    XCard(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun Note(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Simple yes/no style dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

// ---------------------------------------------------------------------------------------------
// In-app file / folder picker: gives us REAL paths, so everything happens next to the input.
// ---------------------------------------------------------------------------------------------

enum class PickMode { ISO_FILE, ANY_FILE, FOLDER }

private fun listDir(dir: File, mode: PickMode, showAll: Boolean): List<File>? {
    val all = dir.listFiles() ?: return null
    return all
        .filter { !it.name.startsWith(".") }
        .filter {
            if (it.isDirectory) {
                true
            } else {
                when (mode) {
                    PickMode.FOLDER -> false
                    PickMode.ISO_FILE -> showAll || isIsoName(it.name)
                    PickMode.ANY_FILE -> true
                }
            }
        }
        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
}

@Composable
fun FilePickerDialog(mode: PickMode, title: String, onDismiss: () -> Unit, onPicked: (File) -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { storageRoots(ctx) }
    var dir by remember {
        val last = Prefs.lastDir?.let { File(it) }
        mutableStateOf(if (last != null && last.isDirectory) last else roots.first().second)
    }
    var showAll by remember { mutableStateOf(false) }

    val items by produceState<List<File>?>(null, dir, showAll) {
        value = null
        value = withContext(Dispatchers.IO) { listDir(dir, mode, showAll) }
    }
    val atRoot = roots.any { it.second.path == dir.path } || dir.parentFile == null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }

                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((name, root) in roots) {
                        FilterChip(
                            selected = dir.path.startsWith(root.path),
                            onClick = { dir = root },
                            label = { Text(name) },
                        )
                    }
                    if (mode == PickMode.ISO_FILE) {
                        FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show all files") })
                    }
                }

                Text(
                    dir.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                TextButton(
                    onClick = { dir.parentFile?.let { dir = it } },
                    enabled = !atRoot,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("↑  Up one level") }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(Modifier.weight(1f)) {
                    val list = items
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text(
                            if (dir.listFiles() == null) "This folder cannot be read." else "Nothing to show here.",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.path }) { f ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (f.isDirectory) {
                                                dir = f
                                            } else {
                                                f.parentFile?.let { Prefs.changeLastDir(it.path) }
                                                onPicked(f)
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (f.isDirectory) XIcons.Folder else XIcons.File,
                                        contentDescription = null,
                                        tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (!f.isDirectory) {
                                            Text(
                                                fmtSize(f.length()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (mode == PickMode.FOLDER) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Box(Modifier.padding(16.dp)) {
                            PrimaryAction("Use this folder", enabled = dir.isDirectory) {
                                Prefs.changeLastDir(dir.path)
                                onPicked(dir)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Global operation dialog (progress / result / "what to do with the original")
// ---------------------------------------------------------------------------------------------

@Composable
fun OperationDialog() {
    val st = OperationManager.state
    val view = LocalView.current
    val keepOn = st.running && Prefs.keepScreenOn
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    if (!st.visible) return

    val o = st.outcome
    when {
        o is OpOutcome.NeedsChoice -> ChoiceDialog(o)
        o != null -> ResultDialog(st.title, o)
        else -> RunningDialog(st)
    }
}

@Composable
private fun RunningDialog(st: OpState) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(st.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(st.status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                if (st.total > 0) {
                    val f = (st.done.toDouble() / st.total.toDouble()).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(f * 100).toInt()}%   ${fmtSize(st.done)} / ${fmtSize(st.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "You can leave the app - this keeps running in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { OperationManager.cancel() }) { Text("Cancel") } },
    )
}

@Composable
private fun ResultDialog(title: String, o: OpOutcome) {
    val heading: String
    val body: @Composable () -> Unit
    when (o) {
        is OpOutcome.Success -> {
            heading = "Done"
            body = {
                Column {
                    Text(o.message)
                    if (o.outputPath != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            o.outputPath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (o.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Warnings", style = MaterialTheme.typography.labelLarge, color = WarnColor)
                        for (w in o.warnings.take(6)) Text(w, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        is OpOutcome.Failure -> {
            heading = "Failed"
            body = {
                Column {
                    Text(o.message, color = MaterialTheme.colorScheme.error)
                    for (d in o.details) {
                        Spacer(Modifier.height(4.dp))
                        Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        else -> {
            heading = "Cancelled"
            body = { Text("Nothing was changed.") }
        }
    }
    AlertDialog(
        onDismissRequest = { OperationManager.dismiss() },
        title = { Text("$heading - $title", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = body,
        confirmButton = { TextButton(onClick = { OperationManager.dismiss() }) { Text("OK") } },
    )
}

@Composable
private fun ChoiceDialog(o: OpOutcome.NeedsChoice) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Rebuild complete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(o.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("What should happen to the original ISO?")
                Button(
                    onClick = { OperationManager.resolveChoice(AfterEdit.REPLACE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Replace the original") }
                OutlinedButton(
                    onClick = { OperationManager.resolveChoice(AfterEdit.KEEP_BAK) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Keep original as .bak") }
                OutlinedButton(
                    onClick = { OperationManager.resolveChoice(AfterEdit.SAVE_NEW) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save as a new ISO") }
                Text(
                    "You can change the default in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}
