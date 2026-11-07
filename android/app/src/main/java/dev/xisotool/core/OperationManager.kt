package dev.xisotool.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xisotool.XisoApp
import dev.xisotool.data.AfterEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

sealed class OpOutcome {
    class Success(
        val message: String,
        val outputPath: String? = null,
        val warnings: List<String> = emptyList(),
    ) : OpOutcome()

    class Failure(val message: String, val details: List<String> = emptyList()) : OpOutcome()

    /** A rebuilt ISO exists next to the original; the user decides what happens to the original. */
    class NeedsChoice(val originalPath: String, val partPath: String, val summary: String) : OpOutcome()

    object Cancelled : OpOutcome()
}

/** Lets a running job report what it is doing. */
interface Sink {
    fun status(text: String)
    fun progress(done: Long, total: Long)
}

class OpState(
    val running: Boolean = false,
    val title: String = "",
    val status: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val showDialog: Boolean = true,
    val outcome: OpOutcome? = null,
) {
    val visible: Boolean get() = showDialog && (running || outcome != null)

    fun copy(
        running: Boolean = this.running,
        status: String = this.status,
        done: Long = this.done,
        total: Long = this.total,
        outcome: OpOutcome? = this.outcome,
    ) = OpState(running, title, status, done, total, showDialog, outcome)
}

/**
 * Runs one long job at a time, independent of which screen is open, shows the progress
 * dialog and keeps the process alive through a foreground service.
 */
object OperationManager {
    var state by mutableStateOf(OpState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var onDone: ((OpOutcome) -> Unit)? = null
    private var lastNotify = 0L

    val busy: Boolean get() = state.running

    private val sink = object : Sink {
        override fun status(text: String) {
            state = state.copy(status = text)
            notifyThrottled(true)
        }

        override fun progress(done: Long, total: Long) {
            state = state.copy(done = done, total = total)
            notifyThrottled(false)
        }
    }

    private fun notifyThrottled(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        val s = state
        KeepAliveService.update(XisoApp.instance, s.title, s.status, s.done, s.total)
    }

    /** Returns false when another job is still running or its result has not been dismissed. */
    fun start(
        title: String,
        showDialog: Boolean = true,
        onDone: ((OpOutcome) -> Unit)? = null,
        work: (Sink) -> OpOutcome,
    ): Boolean {
        if (state.running || (state.outcome != null && state.showDialog)) return false
        this.onDone = onDone
        Engine.reset()
        state = OpState(running = true, title = title, status = "Starting…", showDialog = showDialog)
        KeepAliveService.start(XisoApp.instance)
        scope.launch {
            val raw = try {
                work(sink)
            } catch (t: Throwable) {
                OpOutcome.Failure("Something went wrong", listOf(t.toString()))
            }
            val result = if (Engine.cancelRequested && raw !is OpOutcome.Success) OpOutcome.Cancelled else raw
            Engine.reset()
            finish(result)
        }
        return true
    }

    fun cancel() {
        Engine.cancel()
    }

    /** Called by the dialog when the user picks what to do with the original ISO. */
    fun resolveChoice(choice: AfterEdit) {
        val o = state.outcome as? OpOutcome.NeedsChoice ?: return
        state = state.copy(running = true, status = "Finishing…", outcome = null)
        scope.launch {
            val res = try {
                IsoOps.finalizeRebuild(File(o.originalPath), File(o.partPath), choice)
            } catch (t: Throwable) {
                OpOutcome.Failure("Could not finish", listOf(t.toString()))
            }
            finish(res)
        }
    }

    fun dismiss() {
        state = OpState()
    }

    private fun finish(result: OpOutcome) {
        KeepAliveService.stop(XisoApp.instance)
        val showDialog = state.showDialog
        state = if (result is OpOutcome.NeedsChoice) {
            state.copy(running = false, outcome = result)
        } else if (showDialog) {
            state.copy(running = false, outcome = result)
        } else {
            OpState()
        }
        if (result !is OpOutcome.NeedsChoice) {
            val cb = onDone
            onDone = null
            if (cb != null) main.post { cb(result) }
        }
    }
}
