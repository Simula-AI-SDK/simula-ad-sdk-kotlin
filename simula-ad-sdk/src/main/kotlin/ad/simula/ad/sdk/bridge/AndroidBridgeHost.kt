package ad.simula.ad.sdk.bridge

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a [CreativeBridge] wired to the live device: haptics, orientation lock (on the host
 * [Activity]), and the device/audio/orientation queries. The bridge's main-thread dispatcher posts
 * to the main looper unless already on it.
 *
 * @param onEarlyComplete invoked (on the main thread) for `AD_EARLY_COMPLETE`; the caller flips its
 *   close state (`rewardEarned` / `closeEnabled`).
 */
internal fun androidCreativeBridge(
    appContext: Context,
    activityProvider: () -> Activity?,
    onEarlyComplete: () -> Unit,
): CreativeBridge {
    val main = Handler(Looper.getMainLooper())
    val host = AndroidBridgeHost(appContext.applicationContext, activityProvider, onEarlyComplete)
    return CreativeBridge(host) { block ->
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}

/** [BridgeHost] backed by Android framework APIs. */
internal class AndroidBridgeHost(
    private val appContext: Context,
    private val activityProvider: () -> Activity?,
    private val onEarlyComplete: () -> Unit,
) : BridgeHost {

    override fun earlyComplete() = onEarlyComplete()

    override fun haptic(style: String) = Haptics.trigger(appContext, style)

    override fun setOrientation(orientation: String) {
        val activity = activityProvider() ?: return
        activity.requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> return
        }
    }

    override fun deviceContext(): JsonObject {
        val config = appContext.resources.configuration
        val darkMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // minSdk 24 → `locales` is always available.
        val locale = config.locales[0]?.toLanguageTag() ?: ""
        return buildJsonObject {
            put("darkMode", darkMode)
            put("locale", locale)
            put("osVersion", Build.VERSION.RELEASE ?: "")
        }
    }

    /** Best-effort mute proxy: media-stream volume at zero (mirrors iOS's `outputVolume == 0`). */
    override fun audioState(): JsonObject {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val muted = am?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 } ?: false
        return buildJsonObject { put("muted", muted) }
    }

    override fun currentOrientation(): JsonObject {
        val landscape =
            appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return buildJsonObject { put("orientation", if (landscape) "landscape" else "portrait") }
    }
}

/** Maps `TRIGGER_HAPTIC` styles to vibration effects. Requires the `VIBRATE` permission. */
private object Haptics {
    fun trigger(context: Context, style: String) {
        val vibrator = resolve(context) ?: return
        if (!vibrator.hasVibrator()) return
        // The platform call is guarded: VIBRATE is declared in the SDK manifest, but a host that
        // strips it via manifest merging — or an OEM quirk — would otherwise throw SecurityException
        // and crash the creative's bridge thread.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (style) {
                "light" -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                "medium" -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                "heavy" -> VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                // Android has no native success/error feedback; approximate with patterns.
                "success" -> VibrationEffect.createWaveform(longArrayOf(0, 15, 60, 15), -1)
                "error" -> VibrationEffect.createWaveform(longArrayOf(0, 45, 45, 45), -1)
                else -> return
            }
            runCatching { vibrator.vibrate(effect) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { vibrator.vibrate(if (style == "heavy" || style == "error") 35L else 15L) }
        }
    }

    private fun resolve(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
