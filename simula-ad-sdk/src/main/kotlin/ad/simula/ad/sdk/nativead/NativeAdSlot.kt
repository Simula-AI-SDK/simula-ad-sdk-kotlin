package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.NativeAdInfoOverlay
import ad.simula.ad.sdk.ads.SimulaAdError
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.NativeAdData
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.LocalSimulaContext
import ad.simula.ad.sdk.util.ParsedDimension
import ad.simula.ad.sdk.util.clampMinWidth
import ad.simula.ad.sdk.util.parseDimension
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * An inline, contextually-targeted native ad — a sponsored character card rendered inside a
 * publisher's feed (PRD).
 *
 * Loads `POST /load/native` on first composition (or renders a [preloadedAdId] from cache with no
 * network call), mounts the creative in a content-sized [WebView][NativeAdWebView], and reports an
 * impression only when ≥50% of the creative is visible for ≥1 continuous second (the OMID-shaped
 * [trackNativeAdViewability] seam). A no-fill or any error collapses the slot to **zero height** with
 * no placeholder; an error additionally surfaces via [onError] (a no-fill does not — PRD).
 *
 * Targeting context is not a parameter here: it is read automatically from the
 * [SimulaProvider][ad.simula.ad.sdk.provider.SimulaProvider] this slot is composed within (PRD). Must
 * be used inside a `SimulaProvider`.
 *
 * @param adUnitId      Simula ad unit id (measurement + targeting). Optional.
 * @param position      Index position of the slot in the feed (sent to the backend).
 * @param width         Slot width. Accepts `300`, `"300"`, `"320px"`, `"80%"`, or a float
 *                      0.0–1.0 (e.g. `0.8` = 80% of parent). Defaults to 100% of the parent.
 *                      A 300 dp minimum is enforced after normalization; height fits content.
 * @param preloadedAdId An id from [ad.simula.ad.sdk.ads.SimulaAds.preloadNativeAd]; renders that
 *                      cached ad instead of a live request. An expired/unknown id falls back to a
 *                      live call with no error surfaced.
 * @param onImpression  Fired once when the viewability threshold is met (co-fired with the server
 *                      impression). Carries the [NativeAdData].
 * @param onError       Fired on a load/render failure (network, bad session). Not fired on a no-fill.
 * @param previewHtml   Debug/QA only: render this HTML creative directly through the full pipeline
 *                      (WebView + height sizing + viewability + AD-badge feedback bridge) with no
 *                      network call. Mirrors the imperative ads' `showPreview`.
 */
@Composable
fun NativeAdSlot(
    adUnitId: String? = null,
    position: Int = 0,
    width: Any? = null,
    modifier: Modifier = Modifier,
    preloadedAdId: String? = null,
    onImpression: (NativeAdData) -> Unit = {},
    onError: (SimulaAdError) -> Unit = {},
    previewHtml: String? = null,
) {
    val ctx = LocalSimulaContext.current
    val currentOnImpression by rememberUpdatedState(onImpression)
    val currentOnError by rememberUpdatedState(onError)
    val parsedWidth = remember(width) { parseDimension(width).clampMinWidth(MIN_SLOT_WIDTH) }
    val slotModifier = when (parsedWidth) {
        ParsedDimension.Fill -> modifier.fillMaxWidth()
        is ParsedDimension.Percentage ->
            modifier.widthIn(min = MIN_SLOT_WIDTH).fillMaxWidth(parsedWidth.fraction)
        is ParsedDimension.Pixels ->
            modifier.width(parsedWidth.dp)
    }

    // Initial state from the per-slot cache so a recycled row renders the SAME ad instantly (no
    // shimmer, no refetch). A preload id must be consumed asynchronously, so it starts Loading.
    val cachedFill = NativeAdCache.get(adUnitId, position) as? NativeAdCache.Value.Fill
    var state by remember(adUnitId, position, preloadedAdId, previewHtml) {
        mutableStateOf(initialNativeAdState(adUnitId, position, preloadedAdId, previewHtml))
    }
    var heightDp by remember(adUnitId, position, preloadedAdId, previewHtml) {
        mutableFloatStateOf(cachedFill?.heightDp ?: 0f)
    }
    var impressionFired by remember(adUnitId, position, preloadedAdId, previewHtml) {
        mutableStateOf(cachedFill?.impressionFired ?: false)
    }

    LaunchedEffect(adUnitId, position, preloadedAdId, previewHtml) {
        // Preview/QA: render the supplied HTML with no network (mirrors imperative showPreview).
        if (previewHtml != null) {
            state = NativeAdSlotState.Filled(
                SimulaApiClient.NativeAdResult(
                    impressionId = "",
                    adInserted = true,
                    adFormat = "character_ad",
                    iframeUrl = null,
                    renderedHtml = previewHtml,
                ),
            )
            return@LaunchedEffect
        }

        // Caches the outcome so the next remount of this slot reuses it (no duplicate serve).
        fun apply(result: SimulaApiClient.NativeAdResult) {
            val hasCreative = result.adInserted &&
                (!result.iframeUrl.isNullOrBlank() || !result.renderedHtml.isNullOrBlank())
            if (hasCreative) {
                NativeAdCache.putFill(adUnitId, position, result)
                heightDp = 0f
                impressionFired = false
                state = NativeAdSlotState.Filled(result)
            } else {
                // No-fill collapses silently (no onError). PRD.
                NativeAdCache.putNoFill(adUnitId, position)
                state = NativeAdSlotState.Empty
            }
        }

        // 1. Honor a fresh preload first (a new id the publisher just preloaded).
        preloadedAdId?.let { NativeAdPreloadCache.consume(it) }?.let { apply(it); return@LaunchedEffect }

        // 2. Per-slot cache hit → render without a network call (no duplicate serve / impression).
        when (val cached = NativeAdCache.get(adUnitId, position)) {
            is NativeAdCache.Value.Fill -> {
                heightDp = cached.heightDp
                impressionFired = cached.impressionFired
                state = NativeAdSlotState.Filled(cached.result)
                return@LaunchedEffect
            }
            NativeAdCache.Value.NoFill -> { state = NativeAdSlotState.Empty; return@LaunchedEffect }
            null -> Unit // fall through to a live request
        }

        // 3. Live request.
        state = NativeAdSlotState.Loading
        try {
            apply(NativeAdController.load(ctx.ensureSession, adUnitId, position))
        } catch (e: SimulaAdError) {
            state = NativeAdSlotState.Empty // error → hide; not cached so it can retry next time
            currentOnError(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = NativeAdSlotState.Empty
            currentOnError(SimulaAdError.Network(e))
        }
    }

    when (val s = state) {
        is NativeAdSlotState.Filled -> {
            val result = s.result
            Box(slotModifier) {
                NativeAdWebView(
                    iframeUrl = result.iframeUrl,
                    renderedHtml = result.renderedHtml,
                    apiKey = ctx.apiKey,
                    impressionId = result.impressionId,
                    heightDp = heightDp,
                    onHeightPx = { px ->
                        // Threshold sub-dp churn so a measuring creative can't thrash the feed below.
                        if (kotlin.math.abs(px - heightDp) >= 1f) {
                            heightDp = px
                            // Persist so a recycled row sizes correctly on its first frame.
                            (NativeAdCache.get(adUnitId, position) as? NativeAdCache.Value.Fill)?.heightDp = px
                        }
                    },
                    onAdClick = { /* CLICKED — reserved for a future click callback / telemetry hook. */ },
                    modifier = Modifier.trackNativeAdViewability(
                        // Only measure once the creative has a real, laid-out height.
                        enabled = heightDp > 0f,
                    ) {
                        if (!impressionFired) {
                            impressionFired = true
                            // Remember it on the cache entry so a remount of the same serve never re-fires.
                            (NativeAdCache.get(adUnitId, position) as? NativeAdCache.Value.Fill)?.impressionFired = true
                            // Co-fire the callback and the server impression off the one viewability event.
                            currentOnImpression(NativeAdData(result.impressionId, result.adFormat, adUnitId))
                            // No server impression for a preview (blank id).
                            if (result.impressionId.isNotBlank()) {
                                SimulaScope.launch {
                                    SimulaApiClient.trackImpression(result.impressionId, ctx.apiKey)
                                }
                            }
                        }
                    },
                )
                // Keep the shimmer over the slot until the creative reports its height. Without this
                // the slot would collapse to ~1dp between "filled" and "measured", jolting the feed
                // below up and then back down (it "looks broken"). One smooth grow instead.
                if (heightDp <= 0f) {
                    NativeAdShimmer(Modifier.matchParentSize())
                }

                // Tap-to-open AdChoices over the creative's top-left "AD" badge (Interested /
                // Not interested / Report / About) — the SDK's standard dialog, once the ad is shown.
                if (heightDp > 0f) {
                    NativeAdInfoOverlay(adId = result.impressionId, apiKey = ctx.apiKey)
                }
            }
        }

        NativeAdSlotState.Loading -> {
            // While the request is in flight, show a shimmer placeholder.
            NativeAdShimmer(slotModifier)
        }

        NativeAdSlotState.Empty -> {
            // No-fill / error → hide the card (zero height, no placeholder).
            Spacer(slotModifier.height(0.dp))
        }
    }
}

/** Provisional height the slot holds while the creative is measuring, so it never collapses to a
 * sliver between "filled" and "first height reported" (which would jolt the surrounding feed). */
internal val MIN_SLOT_WIDTH = 300.dp

internal const val NATIVE_AD_PROVISIONAL_HEIGHT_DP = 160f

/** Initial slot state derived synchronously from [NativeAdCache] so a recycled row paints the cached
 * ad on its first frame (no shimmer flash). A preview / preload resolves in the effect, so starts Loading. */
private fun initialNativeAdState(
    adUnitId: String?,
    position: Int,
    preloadedAdId: String?,
    previewHtml: String?,
): NativeAdSlotState {
    if (previewHtml != null || preloadedAdId != null) return NativeAdSlotState.Loading
    return when (val cached = NativeAdCache.get(adUnitId, position)) {
        is NativeAdCache.Value.Fill -> NativeAdSlotState.Filled(cached.result)
        NativeAdCache.Value.NoFill -> NativeAdSlotState.Empty
        null -> NativeAdSlotState.Loading
    }
}

/** Animated shimmer shown while a native ad request is in flight. Collapses to nothing once the
 * slot resolves to a fill (the creative replaces it) or a no-fill/error (zero height). */
@Composable
private fun NativeAdShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "native-ad-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "native-ad-shimmer-progress",
    )
    val base = Color(0xFF24242C)
    val highlight = Color(0xFF34343F)
    Box(
        modifier
            .height(NATIVE_AD_PROVISIONAL_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                val sweep = size.width
                // Reading `progress` here keeps the draw phase animating each frame.
                val start = -sweep + progress * (2f * size.width)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(start, 0f),
                        end = Offset(start + sweep, 0f),
                    ),
                )
            },
    )
}

private sealed interface NativeAdSlotState {
    data object Loading : NativeAdSlotState
    data object Empty : NativeAdSlotState
    data class Filled(val result: SimulaApiClient.NativeAdResult) : NativeAdSlotState
}
