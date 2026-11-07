@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.xisotool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xisotool.core.Engine
import dev.xisotool.core.IsoNode
import dev.xisotool.core.IsoOps
import dev.xisotool.core.Native
import dev.xisotool.core.OpOutcome
import dev.xisotool.core.OperationManager
import dev.xisotool.core.PendingEdit
import dev.xisotool.core.buildTree
import dev.xisotool.core.indexTree
import dev.xisotool.core.key
import dev.xisotool.core.nameOf
import dev.xisotool.core.parentOf
import dev.xisotool.core.sortedChildren
import dev.xisotool.data.Prefs
import dev.xisotool.util.fmtSize
import dev.xisotool.util.isoFolderName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val FOUR_GIB = 4L * 1024 * 1024 * 1024

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

private class BrowserState {
    var isoPath by mutableStateOf<String?>(null)
    var root by mutableStateOf<IsoNode?>(null)
    var index by mutableStateOf<Map<String, IsoNode>>(emptyMap())
    var info by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var edits by mutableStateOf<List<PendingEdit>>(emptyList())
    var selection by mutableStateOf<Set<String>>(emptySet())
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)

    fun editFor(path: String): PendingEdit? {
        val k = key(path)
        return edits.lastOrNull { key(it.path) == k }
    }

    fun isDeleted(path: String): Boolean {
        val k = key(path)
        return edits.any { it is PendingEdit.Delete && (key(it.path) == k || k.startsWith(key(it.path) + "/")) }
    }

    fun addEdit(e: PendingEdit) {
        val k = key(e.path)
        var list = edits.filter { key(it.path) != k }
        if (e is PendingEdit.Delete) {
            list = list.filter { !key(it.path).startsWith("$k/") }
            // removing something that was only queued as "new" simply cancels it
            if (!index.containsKey(k)) {
                edits = list
                return
            }
        }
        edits = list + e
    }

    fun undo(path: String) {
        val k = key(path)
        edits = edits.filter { key(it.path) != k }
    }

    fun addedChildren(dirPath: String): List<IsoNode> {
        val k = key(dirPath)
        return edits
            .filterIsInstance<PendingEdit.Add>()
            .filter { key(parentOf(it.path)) == k }
            .map { IsoNode(nameOf(it.path), it.path, false, it.size, isNew = true) }
    }

    fun estimateNewSize(): Long {
        var total = root?.size ?: 0L
        for (e in edits) {
            val old = index[key(e.path)]?.size ?: 0L
            when (e) {
                is PendingEdit.Delete -> total -= old
                is PendingEdit.Replace -> total += e.size - old
                is PendingEdit.Add -> total += e.size
            }
        }
        return (if (total < 0) 0L else total) + (2L shl 20)
    }
}

private class TreeItem(val node: IsoNode, val depth: Int)

private fun visibleRows(st: BrowserState): List<TreeItem> {
    val root = st.root ?: return emptyList()
    val out = ArrayList<TreeItem>()
    val q = st.query.trim().lowercase()

    if (q.isNotEmpty()) {
        fun search(n: IsoNode) {
            for (c in sortedChildren(n.children + st.addedChildren(n.path))) {
                if (out.size >= 500) return
                if (c.name.lowercase().contains(q)) out.add(TreeItem(c, 0))
                if (c.isDir) search(c)
            }
        }
        search(root)
        return out
    }

    fun walk(n: IsoNode, depth: Int) {
        for (c in sortedChildren(n.children + st.addedChildren(n.path))) {
            out.add(TreeItem(c, depth))
            if (c.isDir && key(c.path) in st.expanded) walk(c, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

private sealed class Pick {
    object Iso : Pick()
    class Replace(val path: String) : Pick()
    class AddInto(val dirPath: String) : Pick()
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
fun BrowseScreen(onBack: () -> Unit) {
    val st = remember { BrowserState() }
    val scope = rememberCoroutineScope()

    var pick by remember { mutableStateOf<Pick?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmExtract by remember { mutableStateOf<List<String>?>(null) }
    var confirmDeleteSel by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun loadIso(path: String) {
        st.isoPath = path
        st.loading = true
        st.error = null
        st.root = null
        st.selection = emptySet()
        st.query = ""
        st.searching = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { Engine.run(Native.OP_LIST, listOf(path)) }
            if (r.ok) {
                val root = buildTree(r.entries)
                st.root = root
                st.index = indexTree(root)
                val opt = if (r.values["optimized"] == "1") " - optimized" else ""
                st.info = "${root.fileCount} files - ${fmtSize(root.size)} of data$opt"
            } else {
                val f = IsoOps.failure("Could not read this ISO", r)
                st.error = if (f is OpOutcome.Failure) (f.message + "\n" + f.details.joinToString("\n")) else "Could not read this ISO"
            }
            st.loading = false
        }
    }

    fun goBack() {
        when {
            st.selection.isNotEmpty() -> st.selection = emptySet()
            st.searching -> {
                st.searching = false
                st.query = ""
            }
            st.edits.isNotEmpty() -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(enabled = true) { goBack() }

    fun extractPaths(paths: List<String>) {
        val iso = File(st.isoPath ?: return)
        val real = paths.filter { st.index.containsKey(key(it)) }
        if (real.isEmpty()) {
            notice = "Nothing to extract - these items are new and not inside the ISO yet."
            return
        }
        val dest = File(iso.parentFile ?: File("/"), isoFolderName(iso))
        OperationManager.start("Extracting from ${iso.name}") { sink -> IsoOps.extract(sink, iso, dest, false, real) }
    }

    fun applyEdits() {
        val iso = File(st.isoPath ?: return)
        val edits = st.edits
        val est = st.estimateNewSize()
        val policy = Prefs.afterEdit
        val patch = Prefs.mediaPatch
        OperationManager.start(
            "Rebuilding ${iso.name}",
            onDone = { o ->
                if (o is OpOutcome.Success) {
                    st.edits = emptyList()
                    loadIso(o.outputPath ?: iso.path)
                }
            },
        ) { sink -> IsoOps.rebuild(sink, iso, edits, est, patch, policy) }
    }

    fun queueReplace(path: String, f: File) {
        if (f.length() >= FOUR_GIB) {
            notice = "That file is ${fmtSize(f.length())}. An xiso can only hold files smaller than 4 GiB."
            return
        }
        st.addEdit(PendingEdit.Replace(path, f.path, f.length()))
    }

    fun queueAdd(dirPath: String, f: File) {
        if (f.length() >= FOUR_GIB) {
            notice = "That file is ${fmtSize(f.length())}. An xiso can only hold files smaller than 4 GiB."
            return
        }
        if (f.name.length > 255) {
            notice = "That file name is too long for an xiso."
            return
        }
        val target = if (dirPath.isEmpty()) f.name else dirPath + "/" + f.name
        val existing = st.index[key(target)]
        when {
            existing != null && existing.isDir -> notice = "A folder named \"${f.name}\" already exists there."
            existing != null -> st.addEdit(PendingEdit.Replace(existing.path, f.path, f.length()))
            else -> st.addEdit(PendingEdit.Add(target, f.path, f.length()))
        }
    }

    val selecting = st.selection.isNotEmpty()
    val isoName = st.isoPath?.let { File(it).name }

    ScreenScaffold(
        title = if (selecting) "${st.selection.size} selected" else (isoName ?: "Browse & edit ISO"),
        onBack = { goBack() },
        actions = {
            if (selecting) {
                IconButton(onClick = { confirmExtract = st.selection.toList() }) {
                    Icon(XIcons.Download, contentDescription = "Extract selected")
                }
                IconButton(onClick = { confirmDeleteSel = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                }
                IconButton(onClick = { st.selection = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                }
            } else if (st.isoPath != null) {
                IconButton(onClick = {
                    st.searching = !st.searching
                    if (!st.searching) st.query = ""
                }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                IconButton(
                    enabled = st.edits.isEmpty(),
                    onClick = { st.isoPath?.let { loadIso(it) } },
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                IconButton(
                    enabled = st.edits.isEmpty(),
                    onClick = { pick = Pick.Iso },
                ) { Icon(XIcons.Folder, contentDescription = "Open another ISO") }
            }
        },
        bottomBar = {
            if (st.edits.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "${st.edits.size} pending change(s) - nothing is written until you apply",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showReview = true }) { Text("Review") }
                            TextButton(onClick = { st.edits = emptyList() }) { Text("Discard") }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { confirmApply = true }, enabled = !OperationManager.busy) { Text("Apply & rebuild") }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.isoPath == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Open an ISO to see everything inside. You can extract, delete or replace single files without unpacking the whole ISO.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryAction("Choose ISO…", enabled = true) { pick = Pick.Iso }
                }
            } else {
                if (st.info.isNotEmpty() && !st.loading) {
                    Text(
                        st.info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (st.searching) {
                    OutlinedTextField(
                        value = st.query,
                        onValueChange = { st.query = it },
                        singleLine = true,
                        placeholder = { Text("Search file and folder names") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    val err = st.error
                    when {
                        st.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        err != null -> Column(Modifier.padding(24.dp)) {
                            Text(err, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { st.isoPath?.let { loadIso(it) } }) { Text("Try again") }
                        }
                        else -> {
                            val rows = remember(st.root, st.expanded, st.edits, st.query) { visibleRows(st) }
                            if (rows.isEmpty()) {
                                Text(
                                    if (st.query.isNotBlank()) "No matches." else "This ISO is empty.",
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(rows, key = { (if (it.node.isNew) "new:" else "") + it.node.path }) { row ->
                                        TreeRow(
                                            row = row,
                                            st = st,
                                            showPath = st.query.isNotBlank(),
                                            onExtract = { confirmExtract = listOf(row.node.path) },
                                            onReplace = { pick = Pick.Replace(row.node.path) },
                                            onAddHere = { pick = Pick.AddInto(row.node.path) },
                                            onDelete = { st.addEdit(PendingEdit.Delete(row.node.path)) },
                                            onUndo = { st.undo(row.node.path) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    val p = pick
    if (p != null) {
        FilePickerDialog(
            mode = if (p is Pick.Iso) PickMode.ISO_FILE else PickMode.ANY_FILE,
            title = when (p) {
                is Pick.Iso -> "Choose an ISO"
                is Pick.Replace -> "Replace \"${nameOf(p.path)}\" with…"
                is Pick.AddInto -> "Add which file?"
            },
            onDismiss = { pick = null },
        ) { f ->
            pick = null
            when (p) {
                is Pick.Iso -> {
                    st.edits = emptyList()
                    st.expanded = emptySet()
                    loadIso(f.path)
                }
                is Pick.Replace -> queueReplace(p.path, f)
                is Pick.AddInto -> queueAdd(p.dirPath, f)
            }
        }
    }

    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }

    confirmExtract?.let { paths ->
        val iso = st.isoPath?.let { File(it) }
        val dest = iso?.let { File(it.parentFile ?: File("/"), isoFolderName(it)) }
        ConfirmDialog(
            title = "Extract ${paths.size} item(s)?",
            text = "They are extracted with their folder structure into:\n${dest?.path ?: ""}\n\nExisting files with the same name are overwritten.",
            confirm = "Extract",
            onConfirm = {
                confirmExtract = null
                extractPaths(paths)
                st.selection = emptySet()
            },
            onDismiss = { confirmExtract = null },
        )
    }

    if (confirmDeleteSel) {
        ConfirmDialog(
            title = "Delete ${st.selection.size} item(s)?",
            text = "They are marked for deletion. Nothing is written until you tap \"Apply & rebuild\", and you can undo this before then.",
            confirm = "Mark for deletion",
            onConfirm = {
                for (path in st.selection) st.addEdit(PendingEdit.Delete(path))
                st.selection = emptySet()
                confirmDeleteSel = false
            },
            onDismiss = { confirmDeleteSel = false },
        )
    }

    if (confirmApply) {
        val policy = Prefs.afterEdit
        ConfirmDialog(
            title = "Rebuild the ISO?",
            text = "A new ISO is written next to the original (about ${fmtSize(st.estimateNewSize())}), " +
                "so you need that much free space. Afterwards: ${policy.title.lowercase()}.\n\nThe original is not touched until the new one is complete.",
            confirm = "Rebuild",
            onConfirm = {
                confirmApply = false
                applyEdits()
            },
            onDismiss = { confirmApply = false },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "You have ${st.edits.size} pending change(s) that have not been applied.",
            confirm = "Discard",
            onConfirm = {
                confirmLeave = false
                st.edits = emptyList()
                onBack()
            },
            onDismiss = { confirmLeave = false },
            dismissLabel = "Keep editing",
        )
    }

    if (showReview) {
        AlertDialog(
            onDismissRequest = { showReview = false },
            title = { Text("Pending changes") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (st.edits.isEmpty()) Text("Nothing queued.")
                    for (e in st.edits) {
                        val (label, detail) = when (e) {
                            is PendingEdit.Delete -> Pair("Delete", e.path)
                            is PendingEdit.Replace -> Pair("Replace", e.path + "\n← " + nameOf(e.src) + " (" + fmtSize(e.size) + ")")
                            is PendingEdit.Add -> Pair("Add", e.path + "\n← " + nameOf(e.src) + " (" + fmtSize(e.size) + ")")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = { st.undo(e.path) }) { Text("Undo") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReview = false }) { Text("Close") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// One row of the tree
// ---------------------------------------------------------------------------------------------

@Composable
private fun TreeRow(
    row: TreeItem,
    st: BrowserState,
    showPath: Boolean,
    onExtract: () -> Unit,
    onReplace: () -> Unit,
    onAddHere: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
) {
    val node = row.node
    val deleted = st.isDeleted(node.path)
    val edit = st.editFor(node.path)
    val selected = key(node.path) in st.selection.map { key(it) }
    val selecting = st.selection.isNotEmpty()
    val open = key(node.path) in st.expanded
    var menu by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    val nameColor = when {
        deleted -> scheme.error
        node.isNew -> SuccessColor
        edit is PendingEdit.Replace -> WarnColor
        else -> scheme.onSurface
    }
    val sub = when {
        deleted -> "will be deleted"
        node.isNew -> "new - " + fmtSize(node.size)
        edit is PendingEdit.Replace -> "will be replaced - " + fmtSize(edit.size)
        node.isDir -> "${node.fileCount} files - ${fmtSize(node.size)}"
        else -> fmtSize(node.size)
    }

    fun toggleSelect() {
        st.selection = if (selected) st.selection.filter { key(it) != key(node.path) }.toSet() else st.selection + node.path
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = {
                    when {
                        selecting -> toggleSelect()
                        node.isDir -> st.expanded = if (open) st.expanded - key(node.path) else st.expanded + key(node.path)
                        else -> menu = true
                    }
                },
                onLongClick = { toggleSelect() },
            )
            .padding(start = (8 + if (showPath) 0 else row.depth * 16).dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (node.isDir && !showPath) Text(if (open) "▾" else "▸", color = scheme.onSurfaceVariant)
        }
        Icon(
            if (node.isDir) XIcons.Folder else if (edit is PendingEdit.Replace) XIcons.Swap else XIcons.File,
            contentDescription = null,
            tint = if (node.isDir) scheme.primary else nameColor.copy(alpha = if (deleted) 1f else 0.8f),
            modifier = Modifier.size(22.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                node.name,
                color = nameColor,
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (showPath) {
                Text(
                    node.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !selecting) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val hasEdit = edit != null
                val inheritedDelete = deleted && !hasEdit
                if (node.isNew) {
                    DropdownMenuItem(text = { Text("Remove from changes") }, onClick = { menu = false; onUndo() })
                } else if (inheritedDelete) {
                    DropdownMenuItem(text = { Text("Inside a deleted folder") }, enabled = false, onClick = { })
                } else {
                    DropdownMenuItem(text = { Text(if (node.isDir) "Extract folder" else "Extract") }, onClick = { menu = false; onExtract() })
                    if (!node.isDir) {
                        DropdownMenuItem(text = { Text("Replace with…") }, onClick = { menu = false; onReplace() })
                    } else if (!deleted) {
                        DropdownMenuItem(text = { Text("Add a file here…") }, onClick = { menu = false; onAddHere() })
                    }
                    if (hasEdit) {
                        DropdownMenuItem(text = { Text("Undo change") }, onClick = { menu = false; onUndo() })
                    }
                    if (edit !is PendingEdit.Delete) {
                        DropdownMenuItem(text = { Text(if (node.isDir) "Delete folder" else "Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
