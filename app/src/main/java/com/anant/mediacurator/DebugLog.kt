package com.anant.mediacurator

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Size-capped diagnostic logger for production debugging.
 *
 * Design
 * ──────
 *  - Two-file ring: writes go to `diag.log`; when it exceeds [MAX_FILE_BYTES] it is
 *    rotated to `diag.old.log` (replacing any previous one).  Worst-case disk use
 *    is ~2 × 128 KB, forever — logs never grow unbounded on user devices.
 *  - Log ONLY decision points and errors (indexing milestones, backup/restore results,
 *    permission states, crashes) — not per-item chatter.
 *  - [installCrashHandler] captures uncaught exceptions to the log before the app dies,
 *    then delegates to the system handler (so the crash dialog/report still happens).
 *  - [buildDiagnosticsReport] assembles device info + current state + both log files
 *    into one shareable string for the "Share diagnostics" menu action.
 *
 * Thread safety: all writes are synchronized on this object.  Writes are tiny appends;
 * doing them inline (even from the main thread) is cheaper than a handoff queue.
 */
object DebugLog {

    private const val MAX_FILE_BYTES = 128 * 1024L
    private const val TAG = "MediaCurator"

    private lateinit var logFile: File
    private lateinit var oldLogFile: File
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var initialized = false

    /** Call once from Application.onCreate(). */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        logFile    = File(dir, "diag.log")
        oldLogFile = File(dir, "diag.old.log")
        initialized = true
        i("app", "── start ── v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "Android ${Build.VERSION.RELEASE} ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        write("W", tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        write("E", tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        // Mirror to logcat so `adb logcat` debugging still works as before
        when (level) { "E" -> Log.e(TAG, "[$tag] $msg"); "W" -> Log.w(TAG, "[$tag] $msg"); else -> Log.i(TAG, "[$tag] $msg") }
        if (!initialized) return
        try {
            if (logFile.length() > MAX_FILE_BYTES) {
                oldLogFile.delete()
                logFile.renameTo(oldLogFile)
            }
            logFile.appendText("${ts.format(Date())} $level/$tag: $msg\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // Never let diagnostics take the app down
        }
    }

    /**
     * Install an uncaught-exception handler that appends the crash to the log,
     * then delegates to the previous handler (system crash flow continues normally).
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("CRASH", "Uncaught on thread ${thread.name}", throwable)
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Full diagnostics payload for the share intent: device/app info, runtime state
     * supplied by the caller, then the rolled + current log files (oldest first).
     */
    @Synchronized
    fun buildDiagnosticsReport(context: Context, stateLines: List<String>): String {
        val sb = StringBuilder(64 * 1024)
        sb.appendLine("══ Media Curator diagnostics ══")
        sb.appendLine("App      : v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device   : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android  : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val rt = Runtime.getRuntime()
        sb.appendLine("Heap     : max ${rt.maxMemory() / 1_048_576} MB, used ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
        sb.appendLine("Generated: ${ts.format(Date())}")
        sb.appendLine()
        sb.appendLine("── State ──")
        stateLines.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("── Log ──")
        try {
            if (oldLogFile.exists()) sb.append(oldLogFile.readText(Charsets.UTF_8))
            if (logFile.exists())    sb.append(logFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            sb.appendLine("(log read failed: ${e.message})")
        }
        return sb.toString()
    }
}
