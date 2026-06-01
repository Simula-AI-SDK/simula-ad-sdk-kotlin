package ad.simula.ad.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime coroutine scope for SDK background work that must outlive any
 * single composable: dedup'd image downloads, session creation, WebView prewarm
 * refills, and fire-and-forget tracking.
 *
 * [SupervisorJob] isolates failures so one failed task never cancels its
 * siblings; [Dispatchers.IO] fits the network + decode I/O profile of this work.
 * UI-bound state that should cancel with its composable must stay in
 * `LaunchedEffect`/`rememberCoroutineScope` instead.
 */
internal val SimulaScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
