package dev.xisotool.core

/** Receives raw bytes written by the native child process. */
interface NativeCallback {
    fun onData(buf: ByteArray, len: Int)
}

/** Thin JNI bridge. All logic lives in the native core (see app/src/main/cpp). */
object Native {
    const val OP_LIST = 1
    const val OP_EXTRACT = 2
    const val OP_CREATE = 3
    const val OP_REBUILD = 4
    const val OP_CLI = 5

    init {
        System.loadLibrary("xisonative")
    }

    /** Runs one operation in a child process and returns its exit code. Blocks until done. */
    @JvmStatic
    external fun exec(op: Int, args: Array<ByteArray>, cb: NativeCallback): Int

    /** Kills the running child process (if any). */
    @JvmStatic
    external fun cancel()
}
