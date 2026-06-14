package ad.simula.ad.sdk.character

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.model.ResolvedCharacterSelectorTheme

// The idle glow colors from the reference HTML's box-shadows.
private val RingBluish = Color(145, 165, 185) // rgb(145,165,185)
private val GlowBluish = Color(130, 150, 170) // rgb(130,150,170)

/**
 * A single selectable character card — 1:1 cover image + name bar, with the
 * reference HTML's layered/pulsing glow, selection highlight, and grayscale of
 * unselected cards once a choice is made.
 *
 * [pulse] is a shared 0..1 phase from the picker (only meaningful before a
 * selection); passing the same value to every card keeps the idle pulse on a
 * single animation clock.
 */
@Composable
internal fun CharacterCard(
    entry: CharacterSelectorEntry,
    selected: Boolean,
    selectionMade: Boolean,
    pulse: Float,
    theme: ResolvedCharacterSelectorTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerDp = theme.cardCornerRadius.toFloat()
    val shape = RoundedCornerShape(theme.cardCornerRadius.dp)

    // Grayscale the image of non-selected cards once a selection exists (HTML: filter saturate(0)).
    val targetSaturation = if (selectionMade && !selected) 0f else 1f
    val saturation by animateFloatAsState(
        targetValue = targetSaturation,
        animationSpec = tween(350),
        label = "charSaturation",
    )
    val imageFilter = if (saturation >= 0.999f) {
        null
    } else {
        ColorFilter.colorMatrix(ColorMatrix().also { it.setToSaturation(saturation) })
    }

    // Ring + glow per state (selected / settled-unselected / pulsing) — mirrors the
    // three box-shadow variants in the HTML.
    val ringColor: Color
    val glowColor: Color
    val glowRadiusDp: Float
    when {
        selected -> {
            ringColor = theme.accentColor.copy(alpha = 0.55f)
            glowColor = theme.accentColor.copy(alpha = 0.32f)
            glowRadiusDp = 16f
        }
        selectionMade -> { // non-selected, pulse stopped → static base shadow
            ringColor = RingBluish.copy(alpha = 0.24f)
            glowColor = GlowBluish.copy(alpha = 0.16f)
            glowRadiusDp = 12f
        }
        else -> { // idle pulse
            ringColor = RingBluish.copy(alpha = lerp(0.22f, 0.38f, pulse))
            glowColor = GlowBluish.copy(alpha = lerp(0.14f, 0.26f, pulse))
            glowRadiusDp = lerp(10f, 18f, pulse)
        }
    }

    Box(
        modifier = modifier
            // translateY(-1px) on selection.
            .graphicsLayer { translationY = if (selected) -1.dp.toPx() else 0f }
            .cardGlow(cornerDp, ringColor, glowColor, glowRadiusDp)
            .clip(shape)
            .background(theme.cardBackgroundColor)
            .border(2.dp, if (selected) theme.accentColor else theme.cardBorderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Image wrap — 1:1, gradient backing (visible only if the image is absent).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF2A2A2F), Color(0xFF15151A))),
                    ),
            ) {
                val res = entry.localRes
                if (res != null) {
                    Image(
                        painter = painterResource(res),
                        contentDescription = entry.data.name,
                        contentScale = ContentScale.Crop,
                        colorFilter = imageFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CachedAsyncImage(
                        model = entry.data.imageUrl,
                        contentDescription = entry.data.name,
                        contentScale = ContentScale.Crop,
                        colorFilter = imageFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Name bar — 1px top border, translucent black backing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000)) // rgba(0,0,0,0.4)
                    .drawBehind {
                        drawLine(
                            color = theme.cardBorderColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.data.name,
                    color = theme.secondaryFontColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = theme.fontFamily,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Draws the card's layered glow behind it: a blurred halo (native [BlurMaskFilter],
 * which works on API 24+ unlike `Modifier.blur`) plus a 1dp ring stroked ~4dp outside
 * the edge — the visible part of the HTML's spread shadows. The `0 0 0 4px #000` black
 * ring is omitted: the picker background is pure black, so it merges with it.
 */
private fun Modifier.cardGlow(
    cornerRadiusDp: Float,
    ringColor: Color,
    glowColor: Color,
    glowRadiusDp: Float,
): Modifier = drawBehind {
    val r = cornerRadiusDp.dp.toPx()

    if (glowColor.alpha > 0f && glowRadiusDp > 0f) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = glowColor
                asFrameworkPaint().maskFilter =
                    BlurMaskFilter(glowRadiusDp.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }

    val inset = 4.dp.toPx()
    drawRoundRect(
        color = ringColor,
        topLeft = Offset(-inset, -inset),
        size = Size(size.width + inset * 2, size.height + inset * 2),
        cornerRadius = CornerRadius(r + inset),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * Loading skeleton for a grid slot whose character is still being fetched — same
 * footprint as [CharacterCard] (1:1 image area + name bar) with a horizontal shimmer,
 * so swapping in the real card doesn't shift the layout. Not selectable.
 */
@Composable
internal fun CharacterSkeletonCard(
    theme: ResolvedCharacterSelectorTheme,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(theme.cardCornerRadius.dp)
    val transition = rememberInfiniteTransition(label = "charSkeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "charSkeletonPhase",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(theme.cardBackgroundColor)
            .border(2.dp, theme.cardBorderColor, shape),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shimmer(phase),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000)) // rgba(0,0,0,0.4), matches the real name bar
                    .drawBehind {
                        drawLine(
                            color = theme.cardBorderColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                // A short name-placeholder bar.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .shimmer(phase),
                )
            }
        }
    }
}

/** A left-to-right shimmer sweep keyed on a 0..1 [phase] from the caller's clock. */
private fun Modifier.shimmer(phase: Float): Modifier = drawBehind {
    val base = Color(0xFF2A2A2F)
    val highlight = Color(0xFF3C3C46)
    val sweep = size.width * 1.5f
    val start = -sweep + (size.width + sweep) * phase
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(start, 0f),
            end = Offset(start + sweep, 0f),
        ),
    )
}
