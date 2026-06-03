package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.minigame.CloseButton
import ad.simula.ad.sdk.model.CloseBehavior
import ad.simula.ad.sdk.model.CloseCountdownUi
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseSize
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent, full-screen host for the imperative interstitial. Reads its
 * [InterstitialPresentation] from [InterstitialHandoff] by token, injects the
 * shared (warmed) session via [ProvideSimulaContext], and renders a native
 * carousel of the rendered creative assets with a call-to-action.
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
                CreativeInterstitial(
                    presentation = p,
                    onFinish = ::closeOnce,
                    openDestination = { ad ->
                        // applicationContext so the open survives auto-dismiss. `storeOpen` is
                        // null when the payload omits `ad_behavior` → today's store path.
                        CreativeCtaRouter.open(
                            applicationContext,
                            ad.trackingUrl,
                            ad.destination,
                            ad.adBehavior?.storeOpen,
                        )
                    },
                )
            }
        }
    }

    /** Fire (reward then) CLOSED exactly once, then finish. */
    private fun closeOnce() {
        if (closed) return
        closed = true
        presentation?.let { p ->
            if (p.rewarded && p.rewardEarned) p.callbacks.onEarnedReward()
            p.callbacks.onClosed()
        }
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
                presentation?.let { p ->
                    if (p.rewarded && p.rewardEarned) p.callbacks.onEarnedReward()
                    p.callbacks.onClosed()
                }
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

/** True if a rewarded dwell was already started and its wall-clock window has elapsed. */
private fun gateElapsed(p: InterstitialPresentation): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs).milliseconds >= p.minPlayThreshold

/** True if a non-rewarded close delay was already started and its wall-clock window elapsed. */
private fun closeGateElapsed(p: InterstitialPresentation, delaySeconds: Int): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs).milliseconds >= delaySeconds.seconds

@Composable
private fun CreativeInterstitial(
    presentation: InterstitialPresentation,
    onFinish: () -> Unit,
    openDestination: (SimulaApiClient.AdLoadResult) -> Unit,
) {
    val ad = presentation.ad
    // FIX M2: defensively filter blank assets for the carousel.
    val assets = remember(ad) { ad.renderedAssets.filter { it.isNotBlank() } }

    // Server-driven render config (null → render today's literal close button / store path).
    val behavior = ad.adBehavior
    // Non-rewarded close gate is driven by `close.delay_seconds`; rewarded ads keep gating on
    // `minPlayThreshold` (unchanged).
    val closeDelaySeconds = if (presentation.rewarded) 0 else (behavior?.close?.delaySeconds ?: 0)

    // FIX C2: close is enabled immediately UNLESS a gate applies — rewarded with a positive
    // minPlayThreshold, or non-rewarded with a positive close delay. A gate that already elapsed
    // in a prior Activity instance (config-change recreation) also starts closable — anchored to
    // wall-clock so rotation can't reset the dwell or strand the user with close blocked.
    var closeEnabled by remember {
        mutableStateOf(
            if (presentation.rewarded) {
                presentation.minPlayThreshold <= Duration.ZERO || gateElapsed(presentation)
            } else {
                closeDelaySeconds <= 0 || closeGateElapsed(presentation, closeDelaySeconds)
            },
        )
    }

    // Countdown affordance state for the non-rewarded close gate.
    var closeRemaining by remember { mutableStateOf(closeDelaySeconds) }
    val closeProgress = remember { Animatable(0f) }

    // FIX C2: the reward gate runs ONLY for rewarded ads. Without this guard the
    // default minPlayThreshold == ZERO would mark every ad as reward-earned.
    if (presentation.rewarded) {
        LaunchedEffect(Unit) {
            // Anchor the dwell to wall-clock on first run so a config-change
            // recreation resumes the remaining time instead of restarting it.
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val remaining = presentation.minPlayThreshold -
                (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            if (remaining.isPositive()) delay(remaining)
            closeEnabled = true
            presentation.rewardEarned = true
        }
    }

    // Non-rewarded close-delay gate (server-driven). Anchored to wall-clock for config-change
    // resilience; drives the countdown affordance and unlocks close when the delay elapses.
    if (!presentation.rewarded && closeDelaySeconds > 0) {
        val countdown = behavior?.close?.countdownUi ?: CloseCountdownUi.NONE
        LaunchedEffect(Unit) {
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val total = closeDelaySeconds.seconds
            val elapsed = (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            val remaining = total - elapsed
            if (remaining.isPositive()) {
                when (countdown) {
                    // Ring / bar fill linearly over the remaining time (the animation IS the wait).
                    CloseCountdownUi.CIRCULAR_PROGRESS, CloseCountdownUi.BAR -> {
                        val start = (elapsed / total).toFloat().coerceIn(0f, 1f)
                        closeProgress.snapTo(start)
                        closeProgress.animateTo(
                            1f,
                            tween(remaining.inWholeMilliseconds.toInt(), easing = LinearEasing),
                        )
                    }
                    // Tick the visible seconds down once per second.
                    CloseCountdownUi.NUMERIC_ALWAYS -> {
                        var left = ceil(remaining.toDouble(DurationUnit.SECONDS)).toInt()
                        closeRemaining = left
                        while (left > 0) {
                            delay(1000)
                            left -= 1
                            closeRemaining = left
                        }
                    }
                    else -> delay(remaining)
                }
            }
            closeEnabled = true
        }
    }

    // DISPLAYED + impression fire once the creative first composes. Guarded so an
    // Activity recreation (config change) doesn't double-report either.
    LaunchedEffect(Unit) {
        if (!presentation.displayedReported) {
            presentation.displayedReported = true
            presentation.callbacks.onDisplayed()
            // FIX M2: skip impression when there is no ad id.
            if (ad.adId.isNotBlank()) {
                SimulaScope.launch { SimulaApiClient.trackImpression(ad.adId, presentation.apiKey) }
            }
        }
    }

    // Block system back while the reward gate is active.
    BackHandler(enabled = !closeEnabled) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CreativeCarousel(
            assets = assets,
            modifier = Modifier.fillMaxSize(),
        )

        // Close button — driven by `ad_behavior` when present; otherwise today's literal
        // top-right button (an absent ad_behavior renders exactly as before).
        if (behavior != null) {
            InterstitialCloseButton(
                close = behavior.close,
                isRewarded = presentation.rewarded,
                enabled = closeEnabled,
                remaining = closeRemaining,
                progress = closeProgress.value,
                onClose = onFinish,
            )
        } else if (closeEnabled) {
            CloseButton(
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            )
        }

        // Always-visible bottom CTA.
        Button(
            onClick = {
                presentation.callbacks.onClicked()
                openDestination(ad)
                // For a rewarded ad the user must remain until the gate elapses.
                if (!presentation.rewarded) onFinish()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = presentation.ctaText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * Native creative carousel. A single asset renders static (no swipe / no dots).
 * Multiple assets use a STABLE swipe driven by a single [Animatable] scroll
 * position (mirrors `GameGrid.MobileCarousel`) — no experimental HorizontalPager.
 * Position is clamped to [0, n-1] (non-wrapping).
 */
@Composable
private fun CreativeCarousel(
    assets: List<String>,
    modifier: Modifier = Modifier,
) {
    val n = assets.size
    if (n == 0) return

    if (n == 1) {
        CachedAsyncImage(
            model = assets[0],
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        return
    }

    val scrollPosition = remember { Animatable(0f) }
    val velocityTracker = remember { VelocityTracker() }
    val coroutineScope = rememberCoroutineScope()

    // The current settled page (for the dot indicator).
    val page by remember {
        derivedStateOf { kotlin.math.round(scrollPosition.value).toInt().coerceIn(0, n - 1) }
    }

    Box(modifier = modifier) {
        var widthPx by remember { mutableStateOf(1f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coroutineScope.launch { scrollPosition.stop() }
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val velocityPx = velocityTracker.calculateVelocity().x
                            val velocityPages = velocityPx / widthPx
                            val decayFactor = 0.4f
                            val projected = scrollPosition.value - velocityPages * decayFactor
                            val snapTarget = kotlin.math.round(projected)
                                .coerceIn(0f, (n - 1).toFloat())
                            coroutineScope.launch {
                                scrollPosition.animateTo(
                                    snapTarget,
                                    spring(dampingRatio = 0.85f, stiffness = 220f),
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val scrollDelta = dragAmount / widthPx
                            coroutineScope.launch {
                                val next = (scrollPosition.value - scrollDelta)
                                    .coerceIn(0f, (n - 1).toFloat())
                                scrollPosition.snapTo(next)
                            }
                        },
                    )
                },
        ) {
            val currentPos = scrollPosition.value
            for (i in 0 until n) {
                val offset = i.toFloat() - currentPos
                // Only render on-screen and immediately-adjacent assets.
                if (kotlin.math.abs(offset) > 1.05f) continue
                CachedAsyncImage(
                    model = assets[i],
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offset * widthPx },
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Dot indicator — only when there is more than one asset.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until n) {
                val active = i == page
                Box(
                    modifier = Modifier
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (active) Color.White else Color(0x66FFFFFF)),
                )
            }
        }
    }
}

// ── Ad-behavior close button ──────────────────────────────────────────────────

/**
 * The `ad_behavior`-driven close button: applies size, corner position, and the countdown_ui
 * treatment. Rewarded creatives ignore the countdown (they gate on `minPlayThreshold` and use
 * "appears" semantics) but still honor position/size.
 */
@Composable
private fun BoxScope.InterstitialCloseButton(
    close: CloseBehavior,
    isRewarded: Boolean,
    enabled: Boolean,
    remaining: Int,
    progress: Float,
    onClose: () -> Unit,
) {
    val countdown = if (isRewarded) CloseCountdownUi.APPEARS_AT_NS else close.countdownUi
    val isBottom =
        close.position == ClosePosition.BOTTOM_LEFT || close.position == ClosePosition.BOTTOM_RIGHT
    val alignment = when (close.position) {
        ClosePosition.TOP_RIGHT -> Alignment.TopEnd
        ClosePosition.TOP_LEFT -> Alignment.TopStart
        ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
        ClosePosition.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    // `bar`: a slim top-edge progress bar shown during the delay.
    if (!enabled && countdown == CloseCountdownUi.BAR) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }

    // The button (or its in-delay indicator), pinned to the configured corner. Bottom corners
    // sit above the full-width CTA so they can't overlap it.
    Box(
        modifier = Modifier
            .align(alignment)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
            .padding(bottom = if (isBottom) 96.dp else 0.dp),
    ) {
        when {
            enabled -> CloseCircle(close.size, onClick = onClose) { CloseGlyph(close.size) }
            // Hidden during the delay (the bar shows progress separately).
            countdown == CloseCountdownUi.APPEARS_AT_NS || countdown == CloseCountdownUi.BAR -> Unit
            countdown == CloseCountdownUi.NONE ->
                CloseCircle(close.size, alpha = 0.4f) { CloseGlyph(close.size) }
            countdown == CloseCountdownUi.NUMERIC_ALWAYS -> CloseCircle(close.size) {
                Text(
                    text = remaining.coerceAtLeast(0).toString(),
                    color = Color(0xFF1F2937),
                    fontSize = close.size.glyphSp.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            countdown == CloseCountdownUi.CIRCULAR_PROGRESS -> {
                Box(contentAlignment = Alignment.Center) {
                    CloseCircle(close.size, alpha = 0.5f) { CloseGlyph(close.size) }
                    Canvas(modifier = Modifier.size(close.size.boxDp.dp)) {
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false,
                            style = Stroke(width = 4f, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}

/** White circular control at the configured size; tappable only when [onClick] is non-null. */
@Composable
private fun CloseCircle(
    size: CloseSize,
    alpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = Modifier
        .size(size.boxDp.dp)
        .clip(CircleShape)
        .background(Color.White.copy(alpha = 0.9f * alpha))
    val modifier = if (onClick != null) {
        base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else {
        base
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

/** The "✕" glyph at the configured point size. */
@Composable
private fun CloseGlyph(size: CloseSize) {
    Text(
        text = "✕",
        color = Color(0xFF1F2937),
        fontSize = size.glyphSp.sp,
        fontWeight = FontWeight.Bold,
    )
}
