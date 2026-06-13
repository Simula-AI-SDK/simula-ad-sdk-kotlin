package ad.simula.ad.sdk.nativead

import ad.simula.ad.sdk.ads.SimulaAdError
import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.NativeAdData
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.LocalSimulaContext
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
    modifier: Modifier = Modifier,
    preloadedAdId: String? = null,
    onImpression: (NativeAdData) -> Unit = {},
    onError: (SimulaAdError) -> Unit = {},
    previewHtml: String? = null,
) {
    val ctx = LocalSimulaContext.current
    val currentOnImpression by rememberUpdatedState(onImpression)
    val currentOnError by rememberUpdatedState(onError)

    // Re-keyed on the slot identity so a recycled list row loads its own ad afresh.
    var state by remember(adUnitId, position, preloadedAdId, previewHtml) {
        mutableStateOf<NativeAdSlotState>(NativeAdSlotState.Loading)
    }
    var heightDp by remember(adUnitId, position, preloadedAdId, previewHtml) { mutableFloatStateOf(0f) }
    var impressionFired by remember(adUnitId, position, preloadedAdId, previewHtml) { mutableStateOf(false) }

    LaunchedEffect(adUnitId, position, preloadedAdId, previewHtml) {
        state = NativeAdSlotState.Loading
        heightDp = 0f
        impressionFired = false

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

        val result: SimulaApiClient.NativeAdResult? = try {
            // Preloaded id → render from cache; expired/unknown falls back to a live request.
            preloadedAdId?.let { NativeAdPreloadCache.consume(it) }
                ?: NativeAdController.load(ctx.ensureSession, adUnitId, position)
        } catch (e: SimulaAdError) {
            state = NativeAdSlotState.Empty
            currentOnError(e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = NativeAdSlotState.Empty
            currentOnError(SimulaAdError.Network(e))
            null
        }

        if (result != null) {
            val hasCreative = result.adInserted &&
                (!result.iframeUrl.isNullOrBlank() || !result.renderedHtml.isNullOrBlank())
            // No-fill collapses silently (no onError). PRD.
            state = if (hasCreative) NativeAdSlotState.Filled(result) else NativeAdSlotState.Empty
        }
    }

    when (val s = state) {
        is NativeAdSlotState.Filled -> {
            val result = s.result
            NativeAdWebView(
                iframeUrl = result.iframeUrl,
                renderedHtml = result.renderedHtml,
                apiKey = ctx.apiKey,
                impressionId = result.impressionId,
                heightDp = heightDp,
                onHeightPx = { px -> heightDp = px },
                onAdClick = { /* CLICKED — reserved for a future click callback / telemetry hook. */ },
                modifier = modifier.trackNativeAdViewability(
                    // Only measure once the creative has a real, laid-out height.
                    enabled = heightDp > 0f,
                ) {
                    if (!impressionFired) {
                        impressionFired = true
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
        }

        NativeAdSlotState.Loading -> {
            // While the request is in flight, show a shimmer placeholder.
            NativeAdShimmer(modifier)
        }

        NativeAdSlotState.Empty -> {
            // No-fill / error → hide the card (zero height, no placeholder).
            Spacer(Modifier.fillMaxWidth().height(0.dp))
        }
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
            .fillMaxWidth()
            .height(160.dp)
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
