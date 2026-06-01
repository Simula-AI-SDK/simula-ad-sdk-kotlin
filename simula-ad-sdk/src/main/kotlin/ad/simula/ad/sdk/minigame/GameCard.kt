package ad.simula.ad.sdk.minigame

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ad.simula.ad.sdk.image.CachedCoverImage
import ad.simula.ad.sdk.model.GameData

private val fallbackEmojis = listOf("🎲", "🎮", "🎰", "🧩", "🎯")

/**
 * Cover-style game card — 9:16 aspect ratio with full-bleed image,
 * dark gradient overlay, and game name at the bottom.
 * Equivalent to React's CoverCard.tsx.
 */
@Composable
internal fun CoverCard(
    game: GameData,
    onGameSelect: (gameId: String) -> Unit,
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
) {
    val randomEmoji = remember { fallbackEmojis.random() }

    // Press scale animation
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "coverCardScale",
    )

    val cardShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .graphicsLayer(scaleX = pressScale, scaleY = pressScale)
            .shadow(14.dp, cardShape)
            .clip(cardShape)
            .background(Color(0x0FFFFFFF)) // rgba(255,255,255,0.06)
            .border(2.dp, Color(0x1A78C8FF), cardShape) // rgba(120,200,255,0.1)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                onGameSelect(game.id)
            },
    ) {
        // Cover Image (gifCover → iconUrl → emoji), animated only when centered
        CachedCoverImage(
            gifCover = game.gifCover,
            iconUrl = game.iconUrl,
            fallbackEmoji = game.iconFallback ?: randomEmoji,
            isAnimating = isAnimating,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.04f
                    scaleY = 1.04f
                },
        )

        // Dark gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.52f to Color.Transparent,
                            0.75f to Color(0x73000000),
                            1.0f to Color(0xF2000000),
                        ),
                    ),
                ),
        )

        // Game title at bottom
        Text(
            text = game.name,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xA6000000), // rgba(0,0,0,0.65)
                    offset = Offset(0f, 10f),
                    blurRadius = 24f,
                ),
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}
