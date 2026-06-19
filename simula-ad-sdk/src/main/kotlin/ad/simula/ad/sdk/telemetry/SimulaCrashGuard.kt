package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.core.SimulaScope
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Process-wide crash capture for the SDK, routed into [Telemetry]. No NDK required:
 *
 * - **Uncaught JVM/Kotlin exceptions** are caught via [Thread.setDefaultUncaughtExceptionHandler]
 *   (this also covers `launch{}` coroutine failures, which propagate to the thread's default handler).
 * - **ANRs and native-renderer crashes** — which the handler above can't see — are harvested from
 *   [ApplicationExitInfo] on the next launch (Android 11+ / API 30).
 *
 * SDK-citizen rules, deliberately baked in:
 * - **Only the SDK's own crashes are reported.** A throwable is recorded only when it (or a cause in
 *   its chain) has a frame in [SDK_PACKAGE]; an exit record only when its trace mentions the package.
 *   The host app's unrelated crashes / ANRs are never exfiltrated.
 * - **The host's crash handling is preserved.** The previously-installed default handler is always
 *   invoked after we persist, so Crashlytics / the host's own reporting / the platform "app stopped"
 *   dialog still fire. With no prior handler we reproduce the platform's process kill.
 * - **The crash path does no async work.** [Telemetry.recordError] persists on a coroutine, which a
 *   dying process won't run — so the handler writes a small record to disk *synchronously* on the
 *   crashing thread, and the next [install] replays it into [Telemetry].
 *
 * Gated by the same `telemetryEnabled` flag as the rest of the pipeline: host opt-out ⇒ no capture,
 * no replay, no send.
 */
internal object SimulaCrashGuard {

    private const val SDK_PACKAGE = "ad.simula.ad.sdk"
    private const val DIR = "simula_crash"
    private const val PENDING_FILE = "pending_crashes.txt"
    private const val PREFS = "simula_crash_prefs"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"

    /** Field separator + newline escape for the flat on-disk record (kept off any real text). */
    private const val FIELD_SEP = "\u0001"
    private const val NL_ESC = "\u0002"

    /** Cap the pending file so a crash-on-launch loop can't grow it without bound. */
    private const val MAX_FILE_BYTES = 64L * 1024
    /** Stack frames kept in the (300-char-capped) telemetry message — enough to locate + group. */
    private const val MAX_FRAMES = 6
    /** Bytes of an [ApplicationExitInfo] trace scanned for attribution. */
    private const val MAX_TRACE_BYTES = 256 * 1024

    @Volatile private var installed = false

    /**
     * Install the handler (synchronously, so a crash during the backgrounded replay/sweep is still
     * caught) and harvest anything left by a prior process. Idempotent; a no-op when [enabled] is
     * false. Call once from `SimulaAds.initialize`, after [Telemetry.initialize].
     */
    @MainThread
    fun install(appContext: Context, enabled: Boolean) {
        if (!enabled || installed) return
        installed = true
        val app = appContext.applicationContext

        installUncaughtHandler(app)

        // File + trace I/O off the main thread (SimulaScope is Dispatchers.IO). recordError persists
        // durably on its own, so no flush is needed here — the normal/background flush delivers it.
        SimulaScope.launch {
            runCatching { replayPending(app) }
            runCatching { sweepExitInfo(app) }
        }
    }

    // ── Uncaught JVM/Kotlin exceptions ───────────────────────────────────────────

    private fun installUncaughtHandler(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist only our own crashes, and never let our bookkeeping throw on the way down.
            runCatching { if (involvesSdk(throwable)) persistSync(app, thread, throwable) }
            // Always hand off so the host's crash reporting + the platform default still run.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No prior handler — reproduce the platform default so the app still dies (no zombie).
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    /** True if [t] or any cause in its chain has a stack frame in the SDK package. */
    private fun involvesSdk(t: Throwable?): Boolean {
        var cur = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            if (cur.stackTrace.any { it.className.startsWith(SDK_PACKAGE) }) return true
            cur = cur.cause
        }
        return false
    }

    /** Write one crash record, synchronously, on the crashing thread (the process is about to die). */
    private fun persistSync(app: Context, thread: Thread, t: Throwable) {
        val dir = File(app.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, PENDING_FILE)
        if (file.length() >= MAX_FILE_BYTES) return // crash-loop guard
        val record = listOf(
            System.currentTimeMillis().toString(),
            thread.name.orEmpty(),
            signatureFor(t),
            t.javaClass.simpleName,
            compactStack(t),
        ).joinToString(FIELD_SEP) { it.replace(FIELD_SEP, " ").replace("\n", NL_ESC) }
        file.appendText(record + "\n")
    }

    private fun replayPending(app: Context) {
        val file = File(File(app.filesDir, DIR), PENDING_FILE)
        if (!file.exists()) return
        val lines = file.readLines()
        file.delete()
        for (line in lines) {
            if (line.isBlank()) continue
            val f = line.split(FIELD_SEP)
            if (f.size < 5) continue
            Telemetry.recordError(
                signature = f[2],
                errorCode = f[3],
                message = f[4].replace(NL_ESC, "\n"),
                breadcrumb = "fatal=uncaught;thread=${f[1]}",
            )
        }
    }

    /** Dedup key: the top SDK frame, so repeats at the same crash site aggregate instead of flooding. */
    private fun signatureFor(t: Throwable): String {
        val frame = firstSdkFrame(t)
        val site = frame?.let { "${it.className.removePrefix("$SDK_PACKAGE.")}.${it.methodName}" } ?: "uncaught"
        return "crash:$site"
    }

    private fun firstSdkFrame(t: Throwable): StackTraceElement? {
        var cur: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            cur.stackTrace.firstOrNull { it.className.startsWith(SDK_PACKAGE) }?.let { return it }
            cur = cur.cause
        }
        return null
    }

    /** Exception type + message + the top [MAX_FRAMES] frames; [Telemetry] caps it to 300 chars. */
    private fun compactStack(t: Throwable): String {
        val sb = StringBuilder(t.javaClass.simpleName)
        t.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
        val frames = t.stackTrace.take(MAX_FRAMES).joinToString(" <- ") { fmtFrame(it) }
        if (frames.isNotEmpty()) sb.append(" @ ").append(frames)
        t.cause?.let { sb.append(" cause=").append(it.javaClass.simpleName) }
        return sb.toString()
    }

    private fun fmtFrame(e: StackTraceElement): String {
        val cls = e.className.substringAfterLast('.')
        return if (e.fileName != null && e.lineNumber >= 0) "$cls.${e.methodName}(${e.fileName}:${e.lineNumber})"
        else "$cls.${e.methodName}"
    }

    // ── ApplicationExitInfo sweep (ANR / native crash; API 30+) ───────────────────

    private fun sweepExitInfo(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 0) }.getOrNull()
        if (infos.isNullOrEmpty()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
        var newestTs = lastTs
        // getHistoricalProcessExitReasons returns most-recent-first.
        for (info in infos) {
            val ts = info.timestamp
            if (ts <= lastTs) break // sorted desc → everything below is already swept
            if (ts > newestTs) newestTs = ts
            runCatching { recordExitInfo(info) }
        }
        if (newestTs != lastTs) prefs.edit().putLong(KEY_LAST_EXIT_TS, newestTs).apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitInfo(info: ApplicationExitInfo) {
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            // REASON_CRASH (JVM) is already covered, with full detail, by the uncaught handler;
            // low-memory kills and other reasons are host-level and not attributable to the SDK.
            else -> return
        }
        val trace = readTrace(info)
        // Attribute: only report when the trace implicates the SDK (don't exfiltrate the host's ANRs).
        if (trace == null || !trace.contains(SDK_PACKAGE)) return
        Telemetry.recordError(
            signature = "exit:$kind",
            errorCode = "exit_reason_${info.reason}",
            message = sdkExcerpt(trace),
            breadcrumb = "fatal=$kind;desc=${info.description?.take(40)}",
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        info.traceInputStream?.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total < MAX_TRACE_BYTES) {
                val n = stream.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, minOf(n, MAX_TRACE_BYTES - total))
                total += n
            }
            String(out.toByteArray())
        }
    }.getOrNull()

    /** The trace lines that mention the SDK, joined + capped by [Telemetry] — the relevant frames. */
    private fun sdkExcerpt(trace: String): String {
        val lines = trace.lineSequence().filter { it.contains(SDK_PACKAGE) }.take(MAX_FRAMES).toList()
        return if (lines.isNotEmpty()) lines.joinToString(" <- ") { it.trim() } else trace
    }
}
