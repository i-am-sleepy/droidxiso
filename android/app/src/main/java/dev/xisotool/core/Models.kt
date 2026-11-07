package dev.xisotool.core

import java.util.Locale

/** One file or folder inside an ISO, as shown in the browser. */
class IsoNode(
    val name: String,
    /** Path inside the ISO using '/', no leading slash. The root has an empty path. */
    val path: String,
    val isDir: Boolean,
    var size: Long,
    val isNew: Boolean = false,
) {
    val children = ArrayList<IsoNode>()
    var fileCount = 0
}

/** A change the user queued; nothing is written until "Apply" is pressed. */
sealed class PendingEdit {
    abstract val path: String

    class Delete(override val path: String) : PendingEdit()
    class Replace(override val path: String, val src: String, val size: Long) : PendingEdit()
    class Add(override val path: String, val src: String, val size: Long) : PendingEdit()
}

fun key(path: String): String = path.lowercase(Locale.ROOT)

fun parentOf(path: String): String = path.substringBeforeLast('/', "")

fun nameOf(path: String): String = path.substringAfterLast('/')

private val nodeComparator = Comparator<IsoNode> { a, b ->
    if (a.isDir != b.isDir) {
        if (a.isDir) -1 else 1
    } else {
        a.name.compareTo(b.name, ignoreCase = true)
    }
}

fun sortedChildren(list: List<IsoNode>): List<IsoNode> = list.sortedWith(nodeComparator)

/** Builds the tree from the flat list the native core returns. */
fun buildTree(entries: List<RawEntry>): IsoNode {
    val root = IsoNode("", "", true, 0)
    val byPath = HashMap<String, IsoNode>()
    byPath[""] = root

    fun dirFor(path: String): IsoNode {
        byPath[path]?.let { return it }
        val parent = dirFor(parentOf(path))
        val d = IsoNode(nameOf(path), path, true, 0)
        parent.children.add(d)
        byPath[path] = d
        return d
    }

    for (e in entries) {
        if (e.isDir) {
            dirFor(e.path)
        } else {
            val parent = dirFor(parentOf(e.path))
            parent.children.add(IsoNode(nameOf(e.path), e.path, false, e.size))
        }
    }

    fun finish(n: IsoNode) {
        if (!n.isDir) {
            n.fileCount = 1
            return
        }
        var size = 0L
        var count = 0
        val sorted = sortedChildren(n.children)
        n.children.clear()
        n.children.addAll(sorted)
        for (c in n.children) {
            finish(c)
            size += c.size
            count += c.fileCount
        }
        n.size = size
        n.fileCount = count
    }
    finish(root)
    return root
}

fun indexTree(root: IsoNode): Map<String, IsoNode> {
    val m = HashMap<String, IsoNode>()
    fun walk(n: IsoNode) {
        m[key(n.path)] = n
        for (c in n.children) walk(c)
    }
    walk(root)
    return m
}
