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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val CRASH_SDK_PACKAGE = "ad.simula.ad.sdk"
private const val CRASH_SDK_CLASS_PREFIX = "$CRASH_SDK_PACKAGE."
internal const val MAX_FRAMES = 8
private const val MAX_CANONICAL_FRAME_LENGTH = 160
private const val MAX_INCIDENT_CONTEXT_LENGTH = 180
private val HEX_ADDRESS_RE = Regex("0x[0-9a-fA-F]+")
private val WHITESPACE_RE = Regex("\\s+")
private val QUOTED_THREAD_HEADER_RE = Regex("^\\s*\"([^\"]+)\".*$")
private val TOMBSTONE_THREAD_HEADER_RE = Regex("^\\s*pid:\\s*\\d+,\\s*tid:\\s*\\d+,\\s*name:.*$")

/**
 * Deterministic unsigned 64-bit FNV-1a over at most eight NUL-delimited canonical SDK frames.
 * The fixed 16-lowercase-hex representation is shared with the Swift watchdog implementation.
 */
internal fun fnv1a64Hex(frames: List<String>): String {
    var hash = -3750763034362895579L // unsigned 0xcbf29ce484222325
    frames.take(MAX_FRAMES).forEach { frame ->
        for (byte in frame.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        hash = hash xor 0x00L
        hash *= 1099511628211L
    }
    return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
}

/** Canonical SDK-only throwable frames, walking causes in order and excluding host frames. */
internal fun canonicalSdkFrames(t: Throwable): List<String> {
    val frames = ArrayList<String>(MAX_FRAMES)
    val seen = HashSet<Throwable>()
    var current: Throwable? = t
    while (current != null && seen.add(current) && frames.size < MAX_FRAMES) {
        for (frame in current.stackTrace) {
            if (!frame.className.startsWith(CRASH_SDK_CLASS_PREFIX)) continue
            val owner = frame.className.removePrefix(CRASH_SDK_CLASS_PREFIX)
            val location = when {
                frame.fileName != null && frame.lineNumber >= 0 -> "${frame.fileName}:${frame.lineNumber}"
                frame.fileName != null -> frame.fileName
                else -> "unknown"
            }
            frames.add("$owner.${frame.methodName}($location)".take(MAX_CANONICAL_FRAME_LENGTH))
            if (frames.size >= MAX_FRAMES) break
        }
        current = current.cause
    }
    return frames
}

/** Canonical SDK-only lines from an OS-native ANR/crash trace. */
internal fun canonicalSdkFrames(trace: String): List<String> =
    trace.lineSequence().mapNotNull(::canonicalSdkTraceFrame).take(MAX_FRAMES).toList()

private fun canonicalSdkTraceFrame(line: String): String? {
    val trimmed = line.trim()
    val candidate = when {
        trimmed.startsWith("at ") -> {
            val frame = trimmed.removePrefix("at ").trim()
            val openParen = frame.indexOf('(')
            val closeParen = frame.indexOf(')', startIndex = openParen.coerceAtLeast(0))
            val callable = if (openParen > 0) frame.substring(0, openParen) else ""
            if (
                openParen <= 0 || closeParen < openParen ||
                !callable.startsWith(CRASH_SDK_CLASS_PREFIX) || callable.any(Char::isWhitespace)
            ) return null
            frame.substring(0, closeParen + 1) + frame.substring(closeParen + 1)
                .trim()
                .takeIf { it.isEmpty() || HEX_ADDRESS_RE.matches(it) }
                ?.let { if (it.isEmpty()) "" else " $it" }
                .orEmpty()
        }
        trimmed.startsWith("#") -> {
            val start = trimmed.indexOf(CRASH_SDK_CLASS_PREFIX)
            if (start <= 0 || trimmed[start - 1] !in "( ") return null
            if (trimmed[start - 1] == '(' && (start < 2 || !trimmed[start - 2].isWhitespace())) return null
            val remainder = trimmed.substring(start)
            val closeParen = remainder.indexOf(')')
            if (closeParen >= 0) remainder.substring(0, closeParen + 1)
            else remainder.substringBefore(' ').substringBefore('+')
        }
        else -> return null
    }
    return candidate
        .replace(HEX_ADDRESS_RE, "0x?")
        .replace(WHITESPACE_RE, " ")
        .trim()
        .take(MAX_CANONICAL_FRAME_LENGTH)
        .takeIf { it.isNotEmpty() }
}

internal fun canonicalFingerprint(frames: List<String>): String? =
    frames.take(MAX_FRAMES).takeIf { it.isNotEmpty() }?.let(::fnv1a64Hex)

internal fun canonicalFrameMessage(frames: List<String>): String? =
    frames.take(MAX_FRAMES).takeIf { it.isNotEmpty() }?.joinToString(" <- ")

private fun quotedThreadBlock(trace: String, name: String): String? {
    val block = ArrayList<String>()
    var collecting = false
    for (line in trace.lineSequence()) {
        val header = QUOTED_THREAD_HEADER_RE.matchEntire(line)
        if (header != null) {
            if (collecting) break
            collecting = header.groupValues[1] == name
        } else if (collecting) {
            block.add(line)
        }
    }
    return block.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun anrMainThreadSdkFrames(trace: String): List<String> =
    quotedThreadBlock(trace, "main")?.let(::canonicalSdkFrames).orEmpty()

/**
 * Conservative ANR attribution: only the quoted `main` thread block may implicate the SDK. SDK
 * worker frames elsewhere in the all-thread dump are intentionally ignored.
 */
internal fun anrMainThreadInvolvesSdk(trace: String): Boolean {
    return anrMainThreadSdkFrames(trace).isNotEmpty()
}

/** SDK frames from a safely identified native faulting thread; ambiguous whole traces are ignored. */
internal fun nativeCrashedThreadSdkFrames(trace: String): List<String> {
    val lines = trace.lineSequence().toList()

    // debuggerd tombstones identify the faulting thread in the first pid/tid/name header.
    val tombstoneStart = lines.indexOfFirst { TOMBSTONE_THREAD_HEADER_RE.matches(it) }
    if (tombstoneStart >= 0) {
        val end = (tombstoneStart + 1 until lines.size).firstOrNull { index ->
            TOMBSTONE_THREAD_HEADER_RE.matches(lines[index]) ||
                QUOTED_THREAD_HEADER_RE.matches(lines[index])
        } ?: lines.size
        return canonicalSdkFrames(lines.subList(tombstoneStart + 1, end).joinToString("\n"))
    }

    // Some platform traces explicitly mark a quoted thread as crashed/faulting.
    val crashMarker = Regex("\\b(crashed|faulting)\\b", RegexOption.IGNORE_CASE)
    val markedHeader = lines.indexOfFirst { line ->
        val firstQuote = line.indexOf('"')
        val closingQuote = if (firstQuote >= 0) line.indexOf('"', firstQuote + 1) else -1
        QUOTED_THREAD_HEADER_RE.matches(line) && closingQuote >= 0 &&
            crashMarker.containsMatchIn(line.substring(closingQuote + 1))
    }
    if (markedHeader >= 0) {
        val end = (markedHeader + 1 until lines.size).firstOrNull { index ->
            QUOTED_THREAD_HEADER_RE.matches(lines[index])
        } ?: lines.size
        return canonicalSdkFrames(lines.subList(markedHeader + 1, end).joinToString("\n"))
    }

    return emptyList()
}

/**
 * `ApplicationExitInfo` may return binary tombstone protobufs for native crashes. Without a public
 * platform decoder, treat only strict UTF-8 text as parseable; under-attribution is safer than
 * searching arbitrary protobuf bytes and attributing a host crash to an SDK worker.
 */
internal fun decodeTextExitTrace(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    if (bytes.any { byte ->
            val value = byte.toInt() and 0xff
            value < 0x20 && value != '\t'.code && value != '\n'.code && value != '\r'.code
        }
    ) return null
    return runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Process-wide crash capture for the SDK, routed into [Telemetry]. No NDK required:
 *
 * - **Uncaught JVM/Kotlin exceptions** are caught via [Thread.setDefaultUncaughtExceptionHandler]
 *   (this also covers `launch{}` coroutine failures, which propagate to the thread's default handler).
 * - **ANRs and parseable text native-crash traces** — which the handler above can't see — are
 *   harvested from [ApplicationExitInfo] on the next launch (Android 11+ / API 30). Binary native
 *   tombstones are skipped because Android exposes no public protobuf decoder for their faulting stack.
 *
 * SDK-citizen rules, deliberately baked in:
 * - **Only the SDK's own crashes are reported.** A throwable is recorded only when it (or a cause in
 *   its chain) has an SDK frame; exits require one on the ANR main thread or native faulting thread.
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

    private const val SDK_PACKAGE = CRASH_SDK_PACKAGE
    /** Trailing dot so a host package like `ad.simula.ad.sdkfoo` can't false-positive a class match. */
    private const val SDK_CLASS_PREFIX = CRASH_SDK_CLASS_PREFIX
    private const val DIR = "simula_crash"
    private const val PENDING_FILE = "pending_crashes.txt"
    private const val PREFS = "simula_crash_prefs"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"

    /** Field separator + newline escape for the flat on-disk record (kept off any real text). */
    private const val FIELD_SEP = "\u0001"
    private const val NL_ESC = "\u0002"

    /** Separator between stack frames within the single persisted frames field (off real text). */
    private const val FRAME_SEP = "\u0003"

    /** Distinguishes SDK-only canonical records from legacy records that may contain host frames. */
    private const val PENDING_RECORD_VERSION = "v2"

    /** Cap the pending file so a crash-on-launch loop can't grow it without bound. */
    private const val MAX_FILE_BYTES = 64L * 1024
    /** Bytes of an [ApplicationExitInfo] trace scanned for attribution. */
    private const val MAX_TRACE_BYTES = 256 * 1024
    /** Most-recent crash records replayed per launch. Bounds the SQLite-write burst a crash-loop
     * (which can fill the pending file) would otherwise cause; identical signatures aggregate anyway. */
    private const val MAX_REPLAY = 20

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
            if (cur.stackTrace.any { it.className.startsWith(SDK_CLASS_PREFIX) }) return true
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
        val frames = canonicalSdkFrames(t)
        val message = canonicalFrameMessage(frames) ?: return
        val record = listOf(
            PENDING_RECORD_VERSION,
            System.currentTimeMillis().toString(),
            thread.name.orEmpty(),
            signatureFor(t),
            t.javaClass.simpleName,
            message,
            frames.joinToString(FRAME_SEP),
        ).joinToString(FIELD_SEP) { it.replace(FIELD_SEP, " ").replace("\n", NL_ESC) }
        file.appendText(record + "\n")
    }

    internal fun decodePendingCrashRecord(line: String): List<String>? {
        if (line.isBlank()) return null
        val fields = line.split(FIELD_SEP)
        if (fields.size != 7 || fields[0] != PENDING_RECORD_VERSION) return null
        return fields.subList(1, fields.size)
    }

    private fun replayPending(app: Context) {
        val file = File(File(app.filesDir, DIR), PENDING_FILE)
        if (!file.exists()) return
        // Most-recent records only: a crash-loop can fill the pending file, and replaying every line
        // would launch one recordError (→ store.save + flush) each at launch. The cap bounds that
        // burst; the telemetry layer still aggregates identical signatures.
        val lines = file.readLines().takeLast(MAX_REPLAY)
        file.delete()
        for (line in lines) {
            // Unversioned legacy records may contain raw host text, so never replay them.
            val f = decodePendingCrashRecord(line) ?: continue
            val stack = if (f[5].isNotBlank()) {
                f[5].split(FRAME_SEP)
                    .filter { it.isNotBlank() }
                    .take(MAX_FRAMES)
                    .map { it.take(MAX_CANONICAL_FRAME_LENGTH) }
            } else {
                emptyList()
            }
            val message = canonicalFrameMessage(stack) ?: continue
            val fingerprint = canonicalFingerprint(stack) ?: continue
            val threadKind = if (f[1] == "main") "main" else "background"
            Telemetry.recordError(
                signature = f[2],
                errorCode = f[3],
                message = message,
                breadcrumb = "fatal=uncaught;thread=$threadKind",
                stack = stack,
                fingerprint = fingerprint,
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
            cur.stackTrace.firstOrNull { it.className.startsWith(SDK_CLASS_PREFIX) }?.let { return it }
            cur = cur.cause
        }
        return null
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
        if (trace == null) return
        val frames = if (kind == "anr") {
            anrMainThreadSdkFrames(trace)
        } else {
            nativeCrashedThreadSdkFrames(trace)
        }
        val message = canonicalFrameMessage(frames) ?: return
        val fingerprint = canonicalFingerprint(frames) ?: return
        val incidentContext = incidentContext(info)
        Telemetry.recordError(
            signature = "exit:$kind",
            errorCode = "exit_reason_${info.reason}",
            message = message,
            breadcrumb = "fatal=$kind;$incidentContext",
            stack = frames,
            fingerprint = fingerprint,
        )
    }

    /**
     * Bounded, non-identifying OS context for exit diagnostics. Numeric platform fields help
     * distinguish renderer pressure from logic failures without adding new wire keys.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun incidentContext(info: ApplicationExitInfo): String {
        return buildString {
            append("incidentMs=").append(info.timestamp.coerceAtLeast(0L))
            append(";status=").append(info.status)
            append(";importance=").append(info.importance)
            append(";pssKb=").append(info.pss)
            append(";rssKb=").append(info.rss)
        }.take(MAX_INCIDENT_CONTEXT_LENGTH)
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
            decodeTextExitTrace(out.toByteArray())
        }
    }.getOrNull()

}
