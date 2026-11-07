package dev.xisotool.core

import dev.xisotool.XisoApp
import dev.xisotool.data.AfterEdit
import dev.xisotool.util.existingParent
import dev.xisotool.util.fmtSize
import dev.xisotool.util.hintFor
import dev.xisotool.util.uniqueFile
import java.io.File

/** The actual jobs. Every function blocks and is meant to run inside OperationManager.start { }. */
object IsoOps {

    private const val FOUR_GIB = 4L * 1024 * 1024 * 1024

    fun failure(what: String, r: NativeResult): OpOutcome {
        if (r.cancelled) return OpOutcome.Cancelled
        val lines = ArrayList<String>()
        hintFor(r.errors)?.let { lines.add(it) }
        lines.addAll(r.errors.takeLast(8))
        return OpOutcome.Failure(what, lines)
    }

    private fun notEnoughSpace(need: Long, free: Long, where: File): OpOutcome =
        OpOutcome.Failure(
            "Not enough free space",
            listOf(
                "Needed: about ${fmtSize(need)}",
                "Available: ${fmtSize(free)}",
                "Location: ${existingParent(where).path}",
            ),
        )

    // ------------------------------------------------------------------ extract

    /** [selection] = paths inside the ISO, or null for everything. */
    fun extract(sink: Sink, iso: File, dest: File, skipSystemUpdate: Boolean, selection: List<String>?): OpOutcome {
        val existedBefore = dest.exists()

        sink.status("Reading ${iso.name}…")
        val list = Engine.run(Native.OP_LIST, listOf(iso.path))
        if (!list.ok) return failure("Could not read this ISO", list)

        var need = 0L
        var files = 0
        for (e in list.entries) {
            if (e.isDir) continue
            if (skipSystemUpdate && e.path.contains("\$SystemUpdate")) continue
            val wanted = selection == null || selection.any {
                e.path.equals(it, true) || e.path.startsWith("$it/", true)
            }
            if (wanted) {
                need += e.size
                files++
            }
        }
        val free = existingParent(dest).usableSpace
        if (need + need / 100 + (1 shl 20) > free) return notEnoughSpace(need, free, dest)

        val args = arrayListOf(iso.path, dest.path, if (skipSystemUpdate) "1" else "0")
        if (selection != null) {
            val f = File(XisoApp.instance.cacheDir, "selection.txt")
            f.writeText(selection.joinToString("\n"), Charsets.UTF_8)
            args.add(f.path)
        }

        sink.status("Extracting…")
        val r = Engine.run(Native.OP_EXTRACT, args, onProgress = { d, t -> sink.progress(d, t) })
        if (r.cancelled) {
            if (!existedBefore && selection == null) dest.deleteRecursively()
            return OpOutcome.Cancelled
        }
        if (!r.ok) return failure("Extraction failed", r)

        return OpOutcome.Success(
            "Extracted $files file(s), ${fmtSize(need)}",
            dest.path,
            r.warnings,
        )
    }

    // ------------------------------------------------------------------ build

    /** Builds [target] (a .iso path) from [folder]. Written as .part first, then renamed. */
    fun build(sink: Sink, folder: File, target: File, mediaPatch: Boolean): OpOutcome {
        sink.status("Scanning ${folder.name}…")
        var files = 0L
        var bytes = 0L
        val tooBig = ArrayList<String>()
        for (f in folder.walkTopDown()) {
            if (!f.isFile) continue
            files++
            bytes += f.length()
            if (f.length() >= FOUR_GIB) tooBig.add(f.name + " (" + fmtSize(f.length()) + ")")
        }
        if (folder.list().isNullOrEmpty()) return OpOutcome.Failure("The folder is empty", listOf("Put the game files into it first."))
        if (tooBig.isNotEmpty()) {
            return OpOutcome.Failure("A file is too big for an xiso", listOf("Files must be smaller than 4 GiB:") + tooBig.take(5))
        }

        val dir = target.parentFile ?: return OpOutcome.Failure("Invalid output location")
        val need = bytes + bytes / 50 + (4L shl 20)
        val free = dir.usableSpace
        if (need > free) return notEnoughSpace(need, free, dir)

        val part = File(dir, target.name + ".part")
        part.delete()

        sink.status("Building ${target.name}…")
        val r = Engine.run(
            Native.OP_CREATE,
            listOf(folder.path, dir.path, part.name, if (mediaPatch) "1" else "0"),
            onProgress = { d, t -> sink.progress(d, t) },
        )
        if (r.cancelled) {
            part.delete()
            return OpOutcome.Cancelled
        }
        if (!r.ok || !part.exists() || part.length() == 0L) {
            part.delete()
            return failure("Building the ISO failed", r)
        }

        if (target.exists() && !target.delete()) {
            part.delete()
            return OpOutcome.Failure("Could not replace the existing ${target.name}")
        }
        if (!part.renameTo(target)) {
            part.delete()
            return OpOutcome.Failure("Could not finish ${target.name}")
        }
        return OpOutcome.Success("Built $files file(s) into ${fmtSize(target.length())}", target.path, r.warnings)
    }

    // ------------------------------------------------------------------ rebuild

    private fun writeEdits(edits: List<PendingEdit>): File {
        val f = File(XisoApp.instance.cacheDir, "edits.txt")
        val sb = StringBuilder()
        for (e in edits) {
            when (e) {
                is PendingEdit.Delete -> sb.append("D\t").append(e.path).append('\n')
                is PendingEdit.Replace -> sb.append("R\t").append(e.path).append('\t').append(e.src).append('\n')
                is PendingEdit.Add -> sb.append("A\t").append(e.path).append('\t').append(e.src).append('\n')
            }
        }
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    /**
     * Rewrites [iso] with [edits] applied (may be empty = just optimise) into "<name>.rebuild.part"
     * next to it, then applies the "what to do with the original" setting.
     */
    fun rebuild(
        sink: Sink,
        iso: File,
        edits: List<PendingEdit>,
        estimatedSize: Long,
        mediaPatch: Boolean,
        policy: AfterEdit,
    ): OpOutcome {
        val dir = iso.parentFile ?: return OpOutcome.Failure("Invalid ISO location")
        val need = estimatedSize + estimatedSize / 100 + (4L shl 20)
        val free = dir.usableSpace
        if (need > free) return notEnoughSpace(need, free, dir)

        val part = File(dir, iso.name + ".rebuild.part")
        part.delete()
        val editsFile = if (edits.isEmpty()) "" else writeEdits(edits).path

        sink.status("Rebuilding ${iso.name}…")
        val r = Engine.run(
            Native.OP_REBUILD,
            listOf(iso.path, dir.path, part.name, editsFile, if (mediaPatch) "1" else "0"),
            onProgress = { d, t -> sink.progress(d, t) },
        )
        if (r.cancelled) {
            part.delete()
            return OpOutcome.Cancelled
        }
        if (!r.ok || !part.exists() || part.length() == 0L) {
            part.delete()
            return failure("Rebuilding the ISO failed", r)
        }

        val summary = "New ISO is ${fmtSize(part.length())} (original ${fmtSize(iso.length())})."
        return if (policy == AfterEdit.ASK) {
            OpOutcome.NeedsChoice(iso.path, part.path, summary)
        } else {
            finalizeRebuild(iso, part, policy)
        }
    }

    /** Moves the finished ".part" file into place according to [choice]. Only renames - safe and quick. */
    fun finalizeRebuild(original: File, part: File, choice: AfterEdit): OpOutcome {
        val dir = original.parentFile ?: return OpOutcome.Failure("Invalid ISO location")
        if (!part.exists()) return OpOutcome.Failure("The rebuilt file is missing")

        return when (choice) {
            AfterEdit.REPLACE -> {
                val old = File(dir, original.name + ".old-tmp")
                old.delete()
                if (!original.renameTo(old)) {
                    return OpOutcome.Failure("Could not replace the original", listOf("The rebuilt ISO was kept as ${part.name}."))
                }
                if (!part.renameTo(original)) {
                    old.renameTo(original)
                    return OpOutcome.Failure("Could not move the new ISO into place", listOf("Your original is unchanged."))
                }
                old.delete()
                OpOutcome.Success("Done - the original ISO was replaced.", original.path)
            }
            AfterEdit.KEEP_BAK -> {
                val bak = uniqueFile(dir, original.name, ".bak")
                if (!original.renameTo(bak)) {
                    return OpOutcome.Failure("Could not create the backup", listOf("The rebuilt ISO was kept as ${part.name}."))
                }
                if (!part.renameTo(original)) {
                    bak.renameTo(original)
                    return OpOutcome.Failure("Could not move the new ISO into place", listOf("Your original is unchanged."))
                }
                OpOutcome.Success("Done - the original was kept as ${bak.name}.", original.path)
            }
            else -> {
                val stem = original.name.substringBeforeLast('.', original.name)
                val ext = if (original.name.contains('.')) "." + original.name.substringAfterLast('.') else ".iso"
                val target = uniqueFile(dir, "$stem (edited)", ext)
                if (!part.renameTo(target)) {
                    return OpOutcome.Failure("Could not save the new ISO", listOf("It is still available as ${part.name}."))
                }
                OpOutcome.Success("Done - saved as ${target.name}. The original is untouched.", target.path)
            }
        }
    }
}
