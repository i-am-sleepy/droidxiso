@file:OptIn(ExperimentalMaterial3Api::class)

package dev.xisotool.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xisotool.core.Engine
import dev.xisotool.core.Native
import dev.xisotool.core.OpOutcome
import dev.xisotool.core.OperationManager
import dev.xisotool.data.Prefs
import dev.xisotool.util.splitArgs

/** Keeps the scrollback. Handles '\r' progress lines the way a real terminal would. */
private class TermState {
    val lines = mutableStateListOf<String>()
    private var replaceNext = false

    init {
        add("Runs the ORIGINAL extract-xiso program, with all its options. Examples:")
        add("")
        add("  -l \"/storage/emulated/0/Games/halo.iso\"")
        add("  -d \"/storage/emulated/0/out\" \"/storage/emulated/0/Games/halo.iso\"")
        add("  -c \"/storage/emulated/0/Games/halo\"")
        add("  -h     (shows every option)")
        add("")
        add("Tip: use the chips below to insert file paths without typing them.")
    }

    fun add(s: String) {
        lines.add(s)
        replaceNext = false
        trim()
    }

    fun onRaw(line: String, term: Char) {
        val clean = line.replace("\b", "")
        if (clean.isEmpty() && term == '\n' && replaceNext) {
            replaceNext = false
            return
        }
        if (replaceNext && lines.isNotEmpty()) lines[lines.size - 1] = clean else lines.add(clean)
        replaceNext = term == '\r'
        trim()
    }

    private fun trim() {
        while (lines.size > 3000) lines.removeAt(0)
    }
}

private enum class TermPick { ISO, FOLDER, CWD }

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val ts = remember { TermState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    var cwd by remember { mutableStateOf(Prefs.lastDir ?: Environment.getExternalStorageDirectory().path) }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<TermPick?>(null) }

    LaunchedEffect(ts.lines.size) {
        if (ts.lines.isNotEmpty()) listState.scrollToItem(ts.lines.size - 1)
    }

    fun insert(text: String) {
        command = (command.trimEnd() + " " + text + " ").trimStart()
    }

    fun run() {
        val args = splitArgs(command)
        if (args.isEmpty() || running) return
        val dir = cwd
        ts.add("$ extract-xiso " + command.trim())
        val started = OperationManager.start(
            "Command line",
            showDialog = false,
            onDone = { running = false },
        ) { _ ->
            val r = Engine.run(Native.OP_CLI, listOf(dir) + args, raw = { line, term -> ts.onRaw(line, term) })
            if (r.cancelled) {
                ts.add("[stopped]")
                OpOutcome.Cancelled
            } else {
                ts.add("[finished - exit code ${r.code}]")
                OpOutcome.Success("")
            }
        }
        if (started) running = true else ts.add("[another job is still running - wait for it to finish]")
    }

    ScreenScaffold(
        title = "Command line",
        onBack = onBack,
        actions = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(ts.lines.joinToString("\n"))) }) { Text("Copy") }
            TextButton(onClick = { ts.lines.clear() }) { Text("Clear") }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Working folder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cwd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { pick = TermPick.CWD }, enabled = !running) { Text("Change") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                items(ts.lines.size) { i ->
                    Text(
                        ts.lines.getOrNull(i) ?: "",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { insert("-l") }, label = { Text("-l list") })
                AssistChip(onClick = { insert("-x") }, label = { Text("-x extract") })
                AssistChip(onClick = { insert("-c") }, label = { Text("-c create") })
                AssistChip(onClick = { insert("-r") }, label = { Text("-r rewrite") })
                AssistChip(onClick = { insert("-s") }, label = { Text("-s skip \$SystemUpdate") })
                AssistChip(onClick = { insert("-h") }, label = { Text("-h help") })
                AssistChip(onClick = { pick = TermPick.ISO }, label = { Text("+ ISO path") })
                AssistChip(onClick = { pick = TermPick.FOLDER }, label = { Text("+ folder path") })
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    prefix = { Text("extract-xiso ", fontFamily = FontFamily.Monospace) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { run() }),
                )
                if (running) {
                    Button(onClick = { OperationManager.cancel() }) { Text("Stop") }
                } else {
                    Button(onClick = { run() }, enabled = command.isNotBlank()) { Text("Run") }
                }
            }
        }
    }

    val p = pick
    if (p != null) {
        FilePickerDialog(
            mode = when (p) {
                TermPick.ISO -> PickMode.ISO_FILE
                else -> PickMode.FOLDER
            },
            title = when (p) {
                TermPick.ISO -> "Choose an ISO"
                TermPick.FOLDER -> "Choose a folder"
                TermPick.CWD -> "Working folder"
            },
            onDismiss = { pick = null },
        ) { f ->
            pick = null
            when (p) {
                TermPick.CWD -> cwd = f.path
                else -> insert("\"" + f.path + "\"")
            }
        }
    }
}
