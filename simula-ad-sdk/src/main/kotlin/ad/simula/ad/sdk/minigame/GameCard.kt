package ad.simula.ad.sdk.minigame

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import ad.simula.ad.sdk.model.Defaults
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.util.ColorUtil

/**
 * Individual game card composable — equivalent to React's GameCard.tsx.
 *
 * Displays a game icon and name. Supports image loading state with spinner,
 * error fallback to random emoji, and press feedback (scale).
 */
@Composable
fun GameCard(
    game: GameData,
    charID: String,
    theme: MiniGameTheme,
    onGameSelect: (gameId: String) -> Unit,
    menuId: String? = null,
) {
    var imageError by remember { mutableStateOf(false) }
    var imageLoading by remember { mutableStateOf(true) }

    // Random fallback icon — picked once per composition, matching React behavior
    val fallbackIcons = remember { listOf("🎲", "🎮", "🎰", "🧩", "🎯") }
    val randomFallback = remember { fallbackIcons.random() }

    val iconCornerRadius = theme.iconCornerRadius ?: Defaults.MiniGameMenuTheme.ICON_CORNER_RADIUS
    val titleFontColor = ColorUtil.parseColor(
        theme.titleFontColor ?: Defaults.MiniGameMenuTheme.TITLE_FONT_COLOR
    )
    val borderColor = ColorUtil.parseColor(
        theme.borderColor ?: Defaults.MiniGameMenuTheme.BORDER_COLOR
    )
    val backgroundColor = if (theme.backgroundColor != null)
        ColorUtil.parseColor(theme.backgroundColor) else Color.White

    // Press interaction for scale effect (replaces mouse hover)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "gameCardScale",
    )

    // Responsive icon size: smaller on compact screens
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isCompact = screenWidthDp < 640
    val iconSize = if (isCompact) 50.dp else 80.dp
    val nameFontSize = if (isCompact) 11.sp else 14.sp
    val cardPadding = if (isCompact) 10.dp else 16.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null, // We use scale instead of ripple
            ) {
                onGameSelect(game.id)
            }
            .padding(cardPadding)
    ) {
        // Game Icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(iconCornerRadius.dp))
        ) {
            if (imageError) {
                // Fallback emoji
                Text(
                    text = game.iconFallback ?: randomFallback,
                    fontSize = if (isCompact) 36.sp else 56.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Loading spinner (shown while image loads)
                if (imageLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = titleFontColor,
                        strokeWidth = 2.dp,
                    )
                }

                AsyncImage(
                    model = game.iconUrl,
                    contentDescription = game.name,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> imageLoading = false
                            is AsyncImagePainter.State.Error -> {
                                imageError = true
                                imageLoading = false
                            }
                            is AsyncImagePainter.State.Loading -> imageLoading = true
                            else -> {}
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(iconCornerRadius.dp))
                        .then(if (imageLoading) Modifier.size(0.dp) else Modifier),
                )
            }
        }

        // Spacer
        Box(modifier = Modifier.padding(top = if (isCompact) 8.dp else 12.dp))

        // Game Name
        Text(
            text = game.name,
            fontSize = nameFontSize,
            fontWeight = FontWeight.Medium,
            color = titleFontColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
