package ad.simula.ad.sdk.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wired once by the telemetry layer at install to report uncaught [SimulaScope] task
 * failures into the pipeline. Null before then (failures are swallowed silently —
 * the handler below terminally consumes them either way, so the host's crash handler
 * never sees one). Injectable so the reporting is unit-testable without a manager.
 */
@Volatile
internal var uncaughtExceptionReporter: ((Throwable) -> Unit)? = null

/**
 * Process-wide backstop for uncaught exceptions in fire-and-forget [SimulaScope] launches.
 *
 * [SupervisorJob] isolates a failed task from its siblings, but an unhandled throwable still
 * propagates to the context's handler — and without one it reaches the host app's default
 * crash handler. Every SDK API already guards its own failures; this guarantees that even a
 * miss can never crash the host (PRD: the SDK must never bring down the publisher's app).
 * Telemetry, not console (rule): failures are reported via [uncaughtExceptionReporter].
 */
private val crashGuard = CoroutineExceptionHandler { _, throwable ->
    runCatching { uncaughtExceptionReporter?.invoke(throwable) }
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
