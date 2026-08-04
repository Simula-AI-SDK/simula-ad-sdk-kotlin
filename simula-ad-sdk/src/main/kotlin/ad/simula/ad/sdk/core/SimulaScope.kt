package ad.simula.ad.sdk.core

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide backstop for uncaught exceptions in fire-and-forget [SimulaScope] launches.
 *
 * [SupervisorJob] isolates a failed task from its siblings, but an unhandled throwable still
 * propagates to the context's handler — and without one it reaches the host app's default
 * crash handler. Every SDK API already guards its own failures; this guarantees that even a
 * miss can never crash the host. The first miss per stable failure signature is reported through an
 * optional failure-safe hook installed by telemetry, without making this core scope depend on telemetry.
 */
internal class SimulaScopeFailureHook {
    private val lock = Any()
    private val reported = LinkedHashSet<String>()

    @Volatile
    private var reporter: ((String, Throwable) -> Unit)? = null

    fun install(reporter: ((String, Throwable) -> Unit)?) {
        this.reporter = reporter
    }

    fun report(throwable: Throwable) {
        val callback = reporter ?: return
        val signature = simulaScopeFailureSignature(throwable)
        val firstForSignature = synchronized(lock) {
            if (signature in reported || reported.size >= MAX_REPORTED_SCOPE_FAILURE_SIGNATURES) false
            else reported.add(signature)
        }
        if (firstForSignature) runCatching { callback(signature, throwable) }
    }
}

private const val MAX_REPORTED_SCOPE_FAILURE_SIGNATURES = 32
private const val SDK_PACKAGE = "ad.simula.ad.sdk."

internal fun simulaScopeFailureSignature(throwable: Throwable): String {
    val frame = throwable.stackTrace.firstOrNull { it.className.startsWith(SDK_PACKAGE) }
        ?: throwable.stackTrace.firstOrNull()
    val site = frame?.let { "${it.className.removePrefix(SDK_PACKAGE)}.${it.methodName}" } ?: "unknown"
    return "${throwable.javaClass.simpleName}:$site"
}

private val failureHook = SimulaScopeFailureHook()

internal fun installSimulaScopeFailureReporter(reporter: ((String, Throwable) -> Unit)?) {
    failureHook.install(reporter)
}

private val crashGuard = CoroutineExceptionHandler { _, throwable ->
    Log.w("SimulaAdSDK", "Uncaught exception in a SimulaScope task (swallowed)", throwable)
    failureHook.report(throwable)
}

/**
 * Process-lifetime coroutine scope for SDK background work that must outlive any
 * single composable: dedup'd image downloads, session creation, WebView prewarm
 * refills, and fire-and-forget tracking.
 *
 * [SupervisorJob] isolates failures so one failed task never cancels its
 * siblings; [Dispatchers.IO] fits the network + decode I/O profile of this work;
 * [crashGuard] keeps a stray throwable from ever reaching the host's crash handler.
 * UI-bound state that should cancel with its composable must stay in
 * `LaunchedEffect`/`rememberCoroutineScope` instead.
 */
internal val SimulaScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
