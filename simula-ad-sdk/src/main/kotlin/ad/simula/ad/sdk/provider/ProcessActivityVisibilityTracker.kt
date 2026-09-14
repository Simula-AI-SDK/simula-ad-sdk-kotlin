package ad.simula.ad.sdk.provider

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap

/** Pure transition state used by the Android callback adapter and JVM tests. */
internal class ActivityVisibilityState {
    private val startedActivities = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var generation = 0L
    private var enteredBackground = false

    val startedActivityCount: Int
        @Synchronized get() = startedActivities.size

    /** Returns true only for a foreground entered after a committed background state. */
    @Synchronized
    fun onActivityStarted(activity: Any): Boolean {
        generation++
        if (!startedActivities.add(activity)) return false
        if (!enteredBackground) return false
        enteredBackground = false
        return true
    }

    /** Seeds callbacks registered after an Activity's onStart without creating a transition. */
    @Synchronized
    fun seedStartedActivity(activity: Any) {
        generation++
        startedActivities.add(activity)
    }

    /** Returns a generation to settle after the Android activity-transition ordering window. */
    @Synchronized
    fun onActivityStopped(activity: Any, changingConfigurations: Boolean): Long? {
        val wasTracked = startedActivities.remove(activity)
        // Activity callbacks are not replayed. When registration happens after the current
        // Activity's onStart (commonly through a late Application-context initialize), its first
        // onStop is necessarily untracked. If no other Activity is known to be started, treat that
        // stop as the missing foreground boundary so the next start can refresh the session.
        if (!wasTracked && startedActivities.isNotEmpty()) return null
        generation++
        if (startedActivities.isNotEmpty() || changingConfigurations) return null
        return generation
    }

    /** Commits background only if no Activity started during the debounce window. */
    @Synchronized
    fun settleBackground(
        candidateGeneration: Long,
        processHasVisibleUi: Boolean = false,
    ): Boolean {
        if (candidateGeneration != generation || startedActivities.isNotEmpty() || processHasVisibleUi) return false
        enteredBackground = true
        return true
    }
}

private const val ACTIVITY_TRANSITION_DEBOUNCE_MS = 500L

/**
 * Process-level visibility tracking without a lifecycle dependency. Started-activity counting avoids
 * multi-window false stops; generation debounce absorbs Activity-to-Activity and SDK fullscreen
 * ordering. Configuration stops are explicitly excluded.
 */
internal object ProcessActivityVisibilityTracker {
    private val lock = Any()
    private val state = ActivityVisibilityState()
    private val registeredApplications = Collections.newSetFromMap(IdentityHashMap<Application, Boolean>())
    private val listeners = IdentityHashMap<Any, () -> Unit>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun register(application: Application, seedStartedActivity: Activity? = null) {
        seedStartedActivity?.let { state.seedStartedActivity(it) }
        val shouldRegister = synchronized(lock) { registeredApplications.add(application) }
        if (!shouldRegister) return

        val registered = runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (state.onActivityStarted(activity)) notifyForegroundReturn()
                }

                override fun onActivityStopped(activity: Activity) {
                    val changingConfigurations = runCatching { activity.isChangingConfigurations }.getOrDefault(false)
                    val candidate = state.onActivityStopped(activity, changingConfigurations) ?: return
                    runCatching {
                        mainHandler.postDelayed(
                            { state.settleBackground(candidate, processHasVisibleUi()) },
                            ACTIVITY_TRANSITION_DEBOUNCE_MS,
                        )
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }.isSuccess
        if (!registered) synchronized(lock) { registeredApplications.remove(application) }
    }

    fun addForegroundListener(owner: Any, listener: () -> Unit) {
        synchronized(lock) { listeners[owner] = listener }
    }

    fun removeForegroundListener(owner: Any) {
        synchronized(lock) { listeners.remove(owner) }
    }

    private fun notifyForegroundReturn() {
        val callbacks = synchronized(lock) { listeners.values.toList() }
        callbacks.forEach { callback -> runCatching { callback() } }
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
