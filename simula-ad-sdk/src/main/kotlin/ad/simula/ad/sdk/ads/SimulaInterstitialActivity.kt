package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.minigame.CloseButton
import ad.simula.ad.sdk.model.AdUnitType
import ad.simula.ad.sdk.model.ClosePosition
import ad.simula.ad.sdk.model.CloseTreatment
import ad.simula.ad.sdk.model.OverlayPosition
import ad.simula.ad.sdk.model.OverlayTiming
import ad.simula.ad.sdk.model.SkOverlayConfig
import ad.simula.ad.sdk.model.StorePrompt
import ad.simula.ad.sdk.model.StorePromptPlatform
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.ProvideSimulaContext
import ad.simula.ad.sdk.util.ColorUtil
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/** True if the close gate was already started in a prior Activity instance (config-change
 * recreation) and its wall-clock window has elapsed. Anchored so rotation can't reset the dwell. */
private fun gateAlreadyElapsed(p: InterstitialPresentation, total: Duration): Boolean =
    p.gateStartedAtMs != 0L &&
        (SystemClock.elapsedRealtime() - p.gateStartedAtMs).milliseconds >= total

/** Fallback playable length used to time the store prompt's 50% trigger when no playable
 * `midpoint` JS-bridge event is available (the image-carousel creative emits none). */
private const val NOMINAL_PLAYABLE_DURATION_MS = 30_000L

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
    val treatment = behavior?.close?.treatment ?: CloseTreatment.HIDDEN
    // "Reward in X" vs "Close in X" copy for the reward_or_close_label treatment.
    val isRewardCopy = ad.adUnitType == AdUnitType.REWARDED

    // Unified close gate. Rewarded ads gate on `minPlayThreshold` (and earn the reward when it
    // elapses); non-rewarded gate on the server-driven `close.delay_seconds`.
    val gateTotal: Duration = if (presentation.rewarded) {
        presentation.minPlayThreshold
    } else {
        (behavior?.close?.delaySeconds ?: 0).seconds
    }

    // FIX C2: close is enabled immediately UNLESS a gate applies. A gate that already elapsed in a
    // prior Activity instance (config-change recreation) also starts closable — anchored to
    // wall-clock so rotation can't reset the dwell or strand the user with close blocked.
    var closeEnabled by remember {
        mutableStateOf(gateTotal <= Duration.ZERO || gateAlreadyElapsed(presentation, gateTotal))
    }

    // Countdown affordance state. `closeRemaining` drives the reward_or_close_label copy.
    var closeRemaining by remember {
        mutableStateOf(ceil(gateTotal.toDouble(DurationUnit.SECONDS)).toInt().coerceAtLeast(0))
    }
    val closeProgress = remember { Animatable(0f) }

    if (gateTotal > Duration.ZERO) {
        LaunchedEffect(Unit) {
            // Anchor the dwell to wall-clock on first run so a config-change recreation resumes the
            // remaining time instead of restarting it.
            if (presentation.gateStartedAtMs == 0L) {
                presentation.gateStartedAtMs = SystemClock.elapsedRealtime()
            }
            val elapsed = (SystemClock.elapsedRealtime() - presentation.gateStartedAtMs).milliseconds
            val remaining = gateTotal - elapsed
            if (remaining.isPositive()) {
                when (treatment) {
                    // Ring / bar fill linearly over the remaining time (the animation IS the wait).
                    CloseTreatment.COUNTDOWN_CIRCLE, CloseTreatment.PROGRESS_BAR -> {
                        val start = (elapsed / gateTotal).toFloat().coerceIn(0f, 1f)
                        closeProgress.snapTo(start)
                        closeProgress.animateTo(
                            1f,
                            tween(remaining.inWholeMilliseconds.toInt(), easing = LinearEasing),
                        )
                    }
                    // Tick the visible seconds down once per second.
                    CloseTreatment.REWARD_OR_CLOSE_LABEL -> {
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
            if (presentation.rewarded) presentation.rewardEarned = true
        }
    }

    // Mid-ad store prompt (`store_prompt`) — revealed at the 50% playable mark via a timer fallback
    // (a true playable would post a `midpoint` JS-bridge event). Independent of close + skoverlay.
    val storePrompt = behavior?.storePrompt
    var storePromptVisible by remember { mutableStateOf(false) }
    if (storePrompt != null && storePrompt.enabled) {
        LaunchedEffect(Unit) {
            delay(NOMINAL_PLAYABLE_DURATION_MS / 2)
            storePromptVisible = true
        }
    }

    // Play Install Prompt (`skoverlay`) — an SDK-presented bottom install banner. Gated to API 21+.
    val skoverlay = behavior?.skoverlay
    var installBannerVisible by remember { mutableStateOf(false) }
    if (skoverlay != null && skoverlay.enabled && Build.VERSION.SDK_INT >= 21) {
        when (skoverlay.timing) {
            // during_play / delayed present automatically (after the optional delay).
            OverlayTiming.DURING_PLAY, OverlayTiming.DELAYED -> LaunchedEffect(Unit) {
                if (skoverlay.delaySeconds > 0) delay(skoverlay.delaySeconds.seconds)
                installBannerVisible = true
            }
            // on_click is triggered from the CTA handler.
            OverlayTiming.ON_CLICK -> Unit
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
                SimulaScope.launch {
                    SimulaApiClient.trackImpression(ad.adId, presentation.apiKey, ad.experiment)
                }
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
                treatment = behavior.close.treatment,
                position = behavior.close.position,
                progressBarColor = behavior.close.progressBarColor,
                isRewardCopy = isRewardCopy,
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

        // Mid-ad store prompt — rendered at the server-resolved position (never recomputed).
        if (storePrompt != null && storePrompt.enabled && storePromptVisible) {
            StorePromptBadge(prompt = storePrompt, onTap = { openDestination(ad) })
        }

        // Play Install Prompt banner — independent install affordance, pinned to the bottom.
        if (skoverlay != null && skoverlay.enabled && installBannerVisible) {
            PlayInstallBanner(
                config = skoverlay,
                onTap = { openDestination(ad) },
                onDismiss = { installBannerVisible = false },
            )
        }

        // Always-visible bottom CTA.
        Button(
            onClick = {
                presentation.callbacks.onClicked()
                // SKOverlay/Play banner timed to the click (independent of the store the CTA opens).
                if (skoverlay != null && skoverlay.enabled &&
                    skoverlay.timing == OverlayTiming.ON_CLICK && Build.VERSION.SDK_INT >= 21
                ) {
                    installBannerVisible = true
                }
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

// v2 dropped the per-size config; the close glyph renders at the former `.standard` size, with the
// circle pinned to the Material-minimum tappable target.
private const val CLOSE_GLYPH_SP = 24
private const val CLOSE_BOX_DP = 44
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * The `ad_behavior`-driven close button. Renders the assigned [treatment] at the configured corner:
 * `HIDDEN` shows nothing until the gate unlocks, `COUNTDOWN_CIRCLE` draws a ring, `PROGRESS_BAR` a
 * top-edge bar, `REWARD_OR_CLOSE_LABEL` a counting-down text pill. [progressBarColor] tints the
 * ring/bar fill. The label copy is reward- vs interstitial-aware via [isRewardCopy].
 */
@Composable
private fun BoxScope.InterstitialCloseButton(
    treatment: CloseTreatment,
    position: ClosePosition,
    progressBarColor: String,
    isRewardCopy: Boolean,
    enabled: Boolean,
    remaining: Int,
    progress: Float,
    onClose: () -> Unit,
) {
    val tint = remember(progressBarColor) { ColorUtil.parseColor(progressBarColor) }
    val isBottom = position == ClosePosition.BOTTOM_LEFT
    val alignment = when (position) {
        ClosePosition.TOP_RIGHT -> Alignment.TopEnd
        ClosePosition.TOP_LEFT -> Alignment.TopStart
        ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
    }

    // `progress_bar`: a slim top-edge bar shown during the delay, tinted by color.
    if (!enabled && treatment == CloseTreatment.PROGRESS_BAR) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // safeDrawing (not statusBars, which is 0 while system bars are hidden) keeps the
                // bar clear of display cutouts / notches.
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
                    .background(tint),
            )
        }
    }

    // The button (or its in-delay indicator), pinned to the configured corner. Bottom corners
    // sit above the full-width CTA so they can't overlap it.
    Box(
        modifier = Modifier
            .align(alignment)
            // safeDrawing merges system bars + display cutout so a top-corner button never
            // lands under a notch (system bars are hidden, so navigationBars alone gave 0 at top).
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .padding(bottom = if (isBottom) 96.dp else 0.dp),
    ) {
        when {
            // The resolved tap target: a labelled pill for reward_or_close_label, the circular X otherwise.
            enabled -> if (treatment == CloseTreatment.REWARD_OR_CLOSE_LABEL) {
                LabelPill(text = "Close", onClick = onClose)
            } else {
                CloseCircle(onClick = onClose) { CloseGlyph() }
            }
            // Nothing in the corner during the delay (the bar shows progress separately).
            treatment == CloseTreatment.HIDDEN || treatment == CloseTreatment.PROGRESS_BAR -> Unit
            treatment == CloseTreatment.REWARD_OR_CLOSE_LABEL ->
                LabelPill(text = "${if (isRewardCopy) "Reward" else "Close"} in ${remaining.coerceAtLeast(0)}")
            treatment == CloseTreatment.COUNTDOWN_CIRCLE -> {
                Box(contentAlignment = Alignment.Center) {
                    CloseCircle(alpha = 0.5f) { CloseGlyph() }
                    Canvas(modifier = Modifier.size(CLOSE_BOX_DP.dp)) {
                        // Stroke in dp (not raw px, which was ~1dp on a 3x screen), inset by half
                        // its width so the ring isn't drawn half-outside the canvas bounds.
                        val stroke = 3.dp.toPx()
                        drawArc(
                            color = tint,
                            startAngle = -90f,
                            sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}

/** White circular control at the standard size; tappable only when [onClick] is non-null. When
 * tappable, the hit area is expanded to at least [MIN_TOUCH_TARGET_DP] around the circle. */
@Composable
private fun CloseCircle(
    alpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val circle = Modifier
        .size(CLOSE_BOX_DP.dp)
        .clip(CircleShape)
        .background(Color.White.copy(alpha = 0.9f * alpha))
    if (onClick != null) {
        Box(
            modifier = Modifier
                .size(maxOf(MIN_TOUCH_TARGET_DP, CLOSE_BOX_DP).dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(circle, contentAlignment = Alignment.Center) { content() }
        }
    } else {
        Box(circle, contentAlignment = Alignment.Center) { content() }
    }
}

/** The "✕" glyph at the standard point size. */
@Composable
private fun CloseGlyph() {
    Text(
        text = "✕",
        color = Color(0xFF1F2937),
        fontSize = CLOSE_GLYPH_SP.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** The text pill used by the `reward_or_close_label` treatment (counting down, then "Close"). */
@Composable
private fun LabelPill(text: String, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .clip(RoundedCornerShape(22.dp))
        .background(Color.White.copy(alpha = 0.9f))
    val mod = if (onClick != null) {
        base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else {
        base
    }
    Box(mod.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFF1F2937), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Mid-ad store prompt ─────────────────────────────────────────────────────────

/**
 * The mid-ad store prompt (`store_prompt`): a tappable "▶| App Store" / "▶| Google Play" badge
 * rendered at the server-resolved corner. The SDK never recomputes the position — it trusts the
 * backend's collision resolution (opposite the close button).
 */
@Composable
private fun BoxScope.StorePromptBadge(
    prompt: StorePrompt,
    onTap: () -> Unit,
) {
    val label = if (prompt.platform == StorePromptPlatform.IOS) "▶| App Store" else "▶| Google Play"
    val isBottom = prompt.position == ClosePosition.BOTTOM_LEFT
    val alignment = when (prompt.position) {
        ClosePosition.TOP_RIGHT -> Alignment.TopEnd
        ClosePosition.TOP_LEFT -> Alignment.TopStart
        ClosePosition.BOTTOM_LEFT -> Alignment.BottomStart
    }
    Box(
        modifier = Modifier
            .align(alignment)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .padding(bottom = if (isBottom) 96.dp else 0.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xA6000000))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Play Install Prompt banner ──────────────────────────────────────────────────

/**
 * The Play Install Prompt (`skoverlay`): an SDK-presented bottom install banner, independent of the
 * creative click handler. Android has no first-party SKOverlay analog, so this is a custom Play-style
 * banner that launches the Play install flow (via [CreativeCtaRouter]) on tap. `BOTTOM_RAISED` floats
 * it higher above the CTA than `BOTTOM`.
 */
@Composable
private fun BoxScope.PlayInstallBanner(
    config: SkOverlayConfig,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp)
            .padding(bottom = if (config.position == OverlayPosition.BOTTOM_RAISED) 150.dp else 96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF01875F)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Color.White, fontSize = 16.sp)
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Google Play", color = Color(0xFF5F6368), fontSize = 12.sp)
            Text(
                "Install the app",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Button(
            onClick = onTap,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF01875F),
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("GET", fontWeight = FontWeight.Bold)
        }
        if (config.dismissible) {
            Text(
                "✕",
                color = Color(0xFF9CA3AF),
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
