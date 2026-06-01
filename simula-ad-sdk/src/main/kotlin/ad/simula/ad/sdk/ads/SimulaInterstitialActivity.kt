package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.minigame.LocalPreloadedCatalog
import ad.simula.ad.sdk.minigame.MiniGameInterstitial
import ad.simula.ad.sdk.minigame.MiniGameMenu
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Transparent, full-screen host for the imperative interstitial. Reads its
 * [InterstitialPresentation] from [InterstitialHandoff] by token, injects the
 * shared (warmed) session via [ProvideSimulaContext], and renders the teaser →
 * menu → game → ad flow in a [androidx.compose.ui.platform.ComposeView].
 */
internal class SimulaInterstitialActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "ad.simula.ad.sdk.TOKEN"
    }

    private var presentation: InterstitialPresentation? = null
    private var token: String? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent?.getStringExtra(EXTRA_TOKEN)
        // Non-destructive read so the presentation survives Activity recreation
        // (a config change not covered by configChanges, e.g. fontScale/density).
        val p = token?.let { InterstitialHandoff.get(it) }
        if (p == null) {
            // No presentation (e.g. process death after handoff cleared) — nothing to show.
            finish()
            return
        }
        presentation = p

        configureWindow()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        setContent {
            ProvideSimulaContext(
                store = SimulaAds.store,
                apiKey = SimulaAds.apiKey,
                devMode = SimulaAds.devMode,
                hasPrivacyConsent = SimulaAds.hasPrivacyConsent,
            ) {
                InterstitialFlow(p, onFinish = ::closeOnce)
            }
        }
    }

    /** Fire CLOSED exactly once, then finish. */
    private fun closeOnce() {
        if (closed) return
        closed = true
        presentation?.callbacks?.onClosed()
        finish() // isFinishing becomes true → onDestroy drops the handoff entry
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only act when finishing for good. On a config-change recreation
        // (isFinishing == false) we keep the handoff so the new instance can read
        // it, and we must NOT report CLOSED.
        if (isFinishing) {
            token?.let { InterstitialHandoff.remove(it) }
            if (!closed) {
                closed = true
                presentation?.callbacks?.onClosed()
            }
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun InterstitialFlow(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    // DISPLAYED fires once the flow first composes (parity with iOS "after present").
    // Guarded so an Activity recreation (config change) doesn't double-report it.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
        }
    }

    CompositionLocalProvider(LocalPreloadedCatalog provides presentation.catalog) {
        if (!showMenu) {
            MiniGameInterstitial(
                charImage = presentation.charImage,
                invitationText = presentation.invitationText,
                ctaText = presentation.ctaText,
                backgroundImage = presentation.backgroundImage,
                theme = presentation.inviteTheme,
                isOpen = true,
                onClick = {
                    presentation.callbacks.onClicked()
                    showMenu = true
                },
                onClose = onFinish,
            )
        } else {
            MiniGameMenu(
                isOpen = true,
                onClose = onFinish,
                charName = presentation.charName,
                charID = presentation.charID,
                charImage = presentation.charImage,
                messages = presentation.messages,
                charDesc = presentation.charDesc,
                maxGamesToShow = presentation.maxGamesToShow,
                theme = presentation.theme,
                delegateChar = presentation.delegateChar,
            )
        }
    }
}
