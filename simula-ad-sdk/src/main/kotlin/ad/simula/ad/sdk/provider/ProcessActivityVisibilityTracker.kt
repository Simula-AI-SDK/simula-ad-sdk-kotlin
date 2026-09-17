package ad.simula.ad.sdk.provider

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.minigame.WebViewPool
import ad.simula.ad.sdk.privacy.SimulaPrivacy
import ad.simula.ad.sdk.telemetry.Telemetry
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

internal const val SESSION_BACKGROUND_EXPIRATION_MS = 30L * 60L * 1_000L

/** Pure transition state used by the Android callback adapter and JVM tests. */
internal class ActivityVisibilityState(
    private val clock: () -> Long,
    private val expirationMs: Long = SESSION_BACKGROUND_EXPIRATION_MS,
) {
    private val startedActivities = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var backgroundStartedAtMs: Long? = null
    private var expiredSessionGeneration = 0L

    val startedActivityCount: Int
        @Synchronized get() = startedActivities.size

    val sessionGeneration: Long
        @Synchronized get() = expiredSessionGeneration

    /** Returns true only when this foreground expires a continuously backgrounded session. */
    @Synchronized
    fun onActivityStarted(activity: Any): Boolean {
        if (!startedActivities.add(activity)) return false
        val backgroundAt = backgroundStartedAtMs ?: return false
        backgroundStartedAtMs = null
        val now = clock()
        if (now < backgroundAt || now - backgroundAt < expirationMs) return false
        expiredSessionGeneration++
        return true
    }

    /** Seeds callbacks registered after an Activity's onStart without creating a transition. */
    @Synchronized
    fun seedStartedActivity(activity: Any) {
        startedActivities.add(activity)
        backgroundStartedAtMs = null
    }

    /** Records the start of a real process background as soon as the last Activity stops. */
    @Synchronized
    fun onActivityStopped(activity: Any, changingConfigurations: Boolean): Boolean {
        val wasTracked = startedActivities.remove(activity)
        // Activity callbacks are not replayed. When registration happens after the current
        // Activity's onStart (commonly through a late Application-context initialize), its first
        // onStop is necessarily untracked. If no other Activity is known to be started, treat that
        // stop as the missing foreground boundary. Android starts the destination Activity before
        // stopping the source Activity, so started-activity counting needs no delayed settle race.
        if (!wasTracked && startedActivities.isNotEmpty()) return false
        if (startedActivities.isNotEmpty() || changingConfigurations) return false
        if (backgroundStartedAtMs != null) return false
        backgroundStartedAtMs = clock()
        return true
    }
}

/** Weak current-Activity ownership isolated for deterministic transition tests. */
internal class CurrentActivityState<T : Any> {
    @Volatile
    private var currentRef: WeakReference<T>? = null

    val current: T? get() = currentRef?.get()

    @Synchronized
    fun seed(activity: T, safeToPresent: Boolean) {
        if (safeToPresent) currentRef = WeakReference(activity)
    }

    @Synchronized
    fun onResumed(activity: T) {
        currentRef = WeakReference(activity)
    }

    @Synchronized
    fun onDestroyed(activity: T) {
        if (currentRef?.get() === activity) currentRef = null
    }
}

internal fun isSafeCurrentActivitySeed(
    hasWindowFocus: Boolean,
    isFinishing: Boolean,
    isDestroyed: Boolean,
): Boolean = hasWindowFocus && !isFinishing && !isDestroyed

/**
 * Process-level visibility tracking without a lifecycle dependency. Started-activity counting avoids
 * multi-window, Activity-to-Activity, and SDK-fullscreen false backgrounds. Configuration stops are
 * explicitly excluded. One process callback also owns the other SDK Activity lifecycle integration.
 */
internal object ProcessActivityVisibilityTracker {
    private val lock = Any()
    private val state = ActivityVisibilityState(SystemClock::elapsedRealtime)
    private val registeredApplications = Collections.newSetFromMap(IdentityHashMap<Application, Boolean>())
    private val currentActivityState = CurrentActivityState<Activity>()

    val sessionGeneration: Long get() = state.sessionGeneration
    val currentActivity: Activity? get() = currentActivityState.current

    fun register(application: Application, seedStartedActivity: Activity? = null) {
        seedStartedActivity?.let { activity ->
            state.seedStartedActivity(activity)
            val safeToPresent = runCatching {
                isSafeCurrentActivitySeed(
                    hasWindowFocus = activity.hasWindowFocus(),
                    isFinishing = activity.isFinishing,
                    isDestroyed = activity.isDestroyed,
                )
            }.getOrDefault(false)
            currentActivityState.seed(activity, safeToPresent)
            markApplicationActive(activity)
        }
        val shouldRegister = synchronized(lock) { registeredApplications.add(application) }
        if (!shouldRegister) return

        val registered = runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    runCatching { state.onActivityStarted(activity) }
                }

                override fun onActivityStopped(activity: Activity) {
                    val changingConfigurations = runCatching { activity.isChangingConfigurations }.getOrDefault(false)
                    runCatching { state.onActivityStopped(activity, changingConfigurations) }
                    runCatching { Telemetry.flush() }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityState.onResumed(activity)
                    markApplicationActive(activity)
                    runCatching {
                        SimulaScope.launch { runCatching { SimulaPrivacy.refreshAdvertisingId() } }
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    currentActivityState.onDestroyed(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            })
        }.isSuccess
        if (!registered) synchronized(lock) { registeredApplications.remove(application) }
    }

    private fun markApplicationActive(activity: Activity) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { WebViewPool.markApplicationActive(activity) }
        } else {
            runCatching {
                activity.runOnUiThread { runCatching { WebViewPool.markApplicationActive(activity) } }
            }
        }
    }
}
