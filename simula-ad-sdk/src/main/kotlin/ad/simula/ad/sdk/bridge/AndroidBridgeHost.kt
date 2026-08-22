package ad.simula.ad.sdk.bridge

import ad.simula.ad.sdk.telemetry.Telemetry
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
    return CreativeBridge(
        host = host,
        mainDispatch = { block ->
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
        },
    )
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
        applyRequestedOrientation(
            orientation = orientation,
            assign = { activity.requestedOrientation = it },
        )
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

    override fun audioState(): JsonObject = readCreativeAudioState(appContext).payload()

    override fun currentOrientation(): JsonObject {
        val landscape =
            appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return buildJsonObject { put("orientation", if (landscape) "landscape" else "portrait") }
    }
}

/** Creative-facing media volume, normalized to the native bridge's shared 0-100 scale. */
internal data class CreativeAudioState(
    val muted: Boolean,
    val volume: Int,
) {
    fun payload(): JsonObject = buildJsonObject {
        put("muted", muted)
        put("volume", volume)
    }
}

internal fun creativeAudioState(currentVolume: Int?, maxVolume: Int?): CreativeAudioState {
    val current = currentVolume?.coerceAtLeast(0)
    val volume = if (current != null && maxVolume != null && maxVolume > 0) {
        ((current.toLong() * 100L + maxVolume / 2L) / maxVolume).toInt().coerceIn(0, 100)
    } else {
        0
    }
    return CreativeAudioState(
        muted = volume == 0,
        volume = volume,
    )
}

/** Reads the media stream without allowing framework or OEM failures to escape into the host app. */
internal fun readCreativeAudioState(context: Context): CreativeAudioState = runCatching {
    val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    creativeAudioState(
        currentVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC),
        maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )
}.getOrElse { error ->
    runCatching {
        Telemetry.recordError(
            signature = "bridge:audio_state_failed",
            errorCode = error.javaClass.simpleName,
        )
    }
    CreativeAudioState(muted = true, volume = 0)
}

internal fun requestedOrientationFor(orientation: String): Int? = when (orientation) {
    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    "auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    else -> null
}

/** API 26 translucent/floating activities may reject this assignment; absorb and report that case. */
internal fun applyRequestedOrientation(
    orientation: String,
    assign: (Int) -> Unit,
    recordFailure: (String) -> Unit = { errorCode ->
        Telemetry.recordError(signature = "bridge:orientation_failed", errorCode = errorCode)
    },
) {
    val requestedOrientation = requestedOrientationFor(orientation) ?: return
    runCatching { assign(requestedOrientation) }
        .onFailure { error -> runCatching { recordFailure(error.javaClass.simpleName) } }
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
