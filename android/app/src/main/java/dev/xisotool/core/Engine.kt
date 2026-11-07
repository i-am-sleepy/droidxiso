package dev.xisotool.core

class RawEntry(val isDir: Boolean, val size: Long, val path: String)

class NativeResult(
    val code: Int,
    val entries: List<RawEntry>,
    val values: Map<String, String>,
    val warnings: List<String>,
    /** Error text: our own X lines plus whatever upstream printed. */
    val errors: List<String>,
    val cancelled: Boolean,
) {
    val ok: Boolean get() = code == 0 && !cancelled
}

/** Splits a byte stream into lines (UTF-8 safe) and tells which character ended each line. */
private class LineSplitter(private val splitOnCr: Boolean, private val onLine: (String, Char) -> Unit) {
    private var buf = ByteArray(4096)
    private var n = 0

    fun feed(data: ByteArray, len: Int) {
        for (i in 0 until len) {
            val b = data[i]
            if (b == NL || (splitOnCr && b == CR)) {
                emit(if (b == CR) '\r' else '\n')
            } else {
                if (n == buf.size) buf = buf.copyOf(n * 2)
                buf[n++] = b
            }
        }
    }

    fun flush() {
        if (n > 0) emit('\n')
    }

    private fun emit(term: Char) {
        val s = String(buf, 0, n, Charsets.UTF_8)
        n = 0
        onLine(s, term)
    }

    companion object {
        private const val NL: Byte = 10
        private const val CR: Byte = 13
    }
}

object Engine {
    private val lock = Any()

    @Volatile
    var cancelRequested = false
        private set

    fun reset() {
        cancelRequested = false
    }

    fun cancel() {
        cancelRequested = true
        Native.cancel()
    }

    /**
     * Runs a native operation (blocking - call from a background thread).
     * [raw] receives unparsed lines instead of the structured protocol (used by the terminal).
     */
    fun run(
        op: Int,
        args: List<String>,
        onProgress: ((Long, Long) -> Unit)? = null,
        raw: ((String, Char) -> Unit)? = null,
    ): NativeResult = synchronized(lock) {
        val entries = ArrayList<RawEntry>()
        val values = HashMap<String, String>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()

        if (cancelRequested) return@synchronized NativeResult(-1, entries, values, warnings, errors, true)

        val splitter = LineSplitter(splitOnCr = raw != null) { line, term ->
            if (raw != null) {
                raw(line, term)
            } else {
                parseLine(line, entries, values, warnings, errors, onProgress)
            }
        }

        val cb = object : NativeCallback {
            override fun onData(buf: ByteArray, len: Int) {
                splitter.feed(buf, len)
            }
        }

        val code = try {
            Native.exec(op, Array(args.size) { args[it].toByteArray(Charsets.UTF_8) }, cb)
        } catch (t: Throwable) {
            errors.add("Native error: $t")
            -1
        }
        splitter.flush()

        if (code > 128 && !cancelRequested) {
            errors.add("The native worker crashed (signal ${code - 128}). The ISO may be damaged.")
        }
        NativeResult(code, entries, values, warnings, errors, cancelRequested)
    }

    private fun parseLine(
        line: String,
        entries: MutableList<RawEntry>,
        values: MutableMap<String, String>,
        warnings: MutableList<String>,
        errors: MutableList<String>,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        if (line.length < 2 || line[1] != '\t') {
            val t = line.trim()
            if (t.isNotEmpty() && errors.size < 40) errors.add(t)
            return
        }
        when (line[0]) {
            'P' -> {
                val p = line.split('\t')
                if (p.size >= 3) onProgress?.invoke(p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
            }
            'T' -> {
                if (line.startsWith("T\tD\t")) {
                    entries.add(RawEntry(true, 0L, line.substring(4)))
                } else if (line.startsWith("T\tF\t")) {
                    val rest = line.substring(4)
                    val i = rest.indexOf('\t')
                    if (i > 0) entries.add(RawEntry(false, rest.substring(0, i).toLongOrNull() ?: 0L, rest.substring(i + 1)))
                }
            }
            'W' -> warnings.add(line.substring(2))
            'X' -> if (errors.size < 40) errors.add(line.substring(2))
            'R' -> {
                val p = line.split('\t', limit = 3)
                if (p.size == 3) values[p[1]] = p[2]
            }
            else -> {
                val t = line.trim()
                if (t.isNotEmpty() && errors.size < 40) errors.add(t)
            }
        }
    }
}
