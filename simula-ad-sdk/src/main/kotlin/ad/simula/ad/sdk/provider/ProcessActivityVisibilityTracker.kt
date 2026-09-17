package ad.simula.ad.sdk.provider

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
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
    private var hasUntrackedStartedActivity = false
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

    /** Seeds callbacks registered after onStart, honoring any background interval already observed. */
    @Synchronized
    fun seedStartedActivity(activity: Any): Boolean = onActivityStarted(activity)

    /** Records a visible host whose onStart happened before callback registration. */
    @Synchronized
    fun seedUntrackedStartedActivity() {
        if (backgroundStartedAtMs == null) {
            hasUntrackedStartedActivity = true
        }
    }

    /** Records the start of a real process background as soon as the last Activity stops. */
    @Synchronized
    fun onActivityStopped(
        activity: Any,
        changingConfigurations: Boolean,
        processHasVisibleUi: Boolean = false,
    ): Boolean {
        val wasTracked = startedActivities.remove(activity)
        // Activity callbacks are not replayed. When registration happens after the current
        // Activity's onStart (commonly through a late Application-context initialize), its first
        // onStop is necessarily untracked. If no other Activity is known to be started, treat that
        // stop as the missing foreground boundary. Android starts the destination Activity before
        // stopping the source Activity, so started-activity counting needs no delayed settle race.
        if (!wasTracked && startedActivities.isNotEmpty()) return false
        if (hasUntrackedStartedActivity && processHasVisibleUi) return false
        if (!processHasVisibleUi) hasUntrackedStartedActivity = false
        if (startedActivities.isNotEmpty() || hasUntrackedStartedActivity || changingConfigurations) return false
        if (backgroundStartedAtMs != null) return false
        backgroundStartedAtMs = clock()
        return true
    }

    /** Resolves pre-registration Activity uncertainty only at Android's aggregate UI-hidden signal. */
    @Synchronized
    fun onUiHidden(): Boolean {
        startedActivities.clear()
        hasUntrackedStartedActivity = false
        if (backgroundStartedAtMs != null) return false
        backgroundStartedAtMs = clock()
        return true
    }

    /** Seeds a process initialized after it was already backgrounded (for example, FCM startup). */
    @Synchronized
    fun seedBackgroundedProcess(): Boolean = onUiHidden()
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
    private val registeredUiHiddenCallbacks = Collections.newSetFromMap(IdentityHashMap<Application, Boolean>())
    private val currentActivityState = CurrentActivityState<Activity>()

    val sessionGeneration: Long get() = state.sessionGeneration
    val currentActivity: Activity? get() = currentActivityState.current

    fun register(application: Application, seedStartedActivity: Activity? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runCatching {
                Handler(Looper.getMainLooper()).post {
                    register(application, seedStartedActivity)
                }
            }
            return
        }
        synchronized(lock) {
            val observesUiHidden = registerUiHiddenCallbackLocked(application)
            val processHasVisibleUi = seedStartedActivity != null || processHasVisibleUi()
            if (processHasVisibleUi && !observesUiHidden) return

            if (!registeredApplications.contains(application)) {
                val registered = runCatching {
                    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityStarted(activity: Activity) {
                            runCatching { state.onActivityStarted(activity) }
                        }

                        override fun onActivityStopped(activity: Activity) {
                            val changingConfigurations = runCatching { activity.isChangingConfigurations }.getOrDefault(false)
                            runCatching {
                                state.onActivityStopped(
                                    activity = activity,
                                    changingConfigurations = changingConfigurations,
                                    processHasVisibleUi = processHasVisibleUi(),
                                )
                            }
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
                if (!registered) return
                registeredApplications.add(application)
            }

            seedStartedActivity?.let(::seedKnownActivity)
            if (processHasVisibleUi) {
                state.seedUntrackedStartedActivity()
            } else {
                state.seedBackgroundedProcess()
            }
        }
    }

    /** Caller holds [lock], serializing registration success with all competing entry points. */
    private fun registerUiHiddenCallbackLocked(application: Application): Boolean {
        if (registeredUiHiddenCallbacks.contains(application)) return true
        val registered = runCatching {
            application.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        runCatching { state.onUiHidden() }
                    }
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {}
            })
        }.isSuccess
        if (registered) registeredUiHiddenCallbacks.add(application)
        return registered
    }

    private fun seedKnownActivity(activity: Activity) {
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

    private fun markApplicationActive(activity: Activity) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { WebViewPool.markApplicationActive(activity) }
        } else {
            runCatching {
                activity.runOnUiThread { runCatching { WebViewPool.markApplicationActive(activity) } }
            }
        }
    }

    private fun processHasVisibleUi(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        return runCatching {
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.getOrDefault(false)
    }
}
