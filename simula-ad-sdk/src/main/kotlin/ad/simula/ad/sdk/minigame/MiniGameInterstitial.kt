package ad.simula.ad.sdk.minigame

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import ad.simula.ad.sdk.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import ad.simula.ad.sdk.model.Defaults.MiniGameInterstitialDefaults
import ad.simula.ad.sdk.model.MiniGameInterstitialTheme
import ad.simula.ad.sdk.util.ColorUtil
import ad.simula.ad.sdk.util.FontUtil

/**
 * Full-screen interstitial invitation composable.
 * Equivalent to React's MiniGameInterstitial.tsx.
 *
 * Shows a full-screen overlay with character image, invitation text,
 * CTA button, and background image.
 */
@Composable
fun MiniGameInterstitial(
    charImage: String,
    invitationText: String = "Want to play a game?",
    ctaText: String = "Play a Game",
    backgroundImage: String? = null,
    theme: MiniGameInterstitialTheme = MiniGameInterstitialTheme(),
    isOpen: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    var imageError by remember { mutableStateOf(false) }
    var closedInternally by remember { mutableStateOf(false) }

    // Applied theme
    val ctaCornerRadius = theme.ctaCornerRadius ?: MiniGameInterstitialDefaults.CTA_CORNER_RADIUS
    val characterSize = theme.characterSize ?: MiniGameInterstitialDefaults.CHARACTER_SIZE
    val titleTextColor = ColorUtil.parseColor(
        theme.titleTextColor ?: MiniGameInterstitialDefaults.TITLE_TEXT_COLOR
    )
    val titleFontSize = theme.titleFontSize ?: MiniGameInterstitialDefaults.TITLE_FONT_SIZE
    val ctaTextColor = ColorUtil.parseColor(
        theme.ctaTextColor ?: MiniGameInterstitialDefaults.CTA_TEXT_COLOR
    )
    val ctaFontSize = theme.ctaFontSize ?: MiniGameInterstitialDefaults.CTA_FONT_SIZE
    val fontFamily = FontUtil.parseFont(theme.fontFamily)
    val ctaColor = ColorUtil.parseColor(
        theme.ctaColor ?: MiniGameInterstitialDefaults.CTA_COLOR
    )

    val isVisible = isOpen && !closedInternally

    // Reset internal close state when parent re-opens
    LaunchedEffect(isOpen) {
        if (isOpen) {
            closedInternally = false
            imageError = false
        }
    }

    // Reset image error when charImage changes
    LaunchedEffect(charImage) {
        imageError = false
    }

    if (!isVisible) return

    // Back handler
    BackHandler(enabled = true) {
        closedInternally = true
        onClose?.invoke()
    }

    // Press interaction for CTA button
    val ctaInteractionSource = remember { MutableInteractionSource() }
    val ctaPressed by ctaInteractionSource.collectIsPressedAsState()
    val ctaScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (ctaPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "ctaScale",
    )

    Dialog(
        onDismissRequest = {
            closedInternally = true
            onClose?.invoke()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Full-screen background — clicking anywhere triggers CTA
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000)) // Dark overlay (0.6 alpha)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    closedInternally = true
                    onClose?.invoke()
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            // Background image (user-provided URL or bundled default)
            if (backgroundImage != null) {
                AsyncImage(
                    model = backgroundImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.minigame_interstitial_background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Dark overlay on top of background image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000)),
            )

            // Content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Character image in circle
                if (!imageError) {
                    AsyncImage(
                        model = charImage,
                        contentDescription = "AI companion",
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) {
                                imageError = true
                            }
                        },
                        modifier = Modifier
                            .size(characterSize.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(characterSize.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "🎮",
                            fontSize = (characterSize * 0.4f).sp,
                        )
                    }
                }

                // Invitation text
                Text(
                    text = invitationText,
                    color = titleTextColor,
                    fontSize = titleFontSize.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = (titleFontSize * 1.3f).sp,
                    modifier = Modifier.widthIn(max = 320.dp),
                )

                // CTA button
                Box(
                    modifier = Modifier
                        .graphicsLayer(scaleX = ctaScale, scaleY = ctaScale)
                        .clip(RoundedCornerShape(ctaCornerRadius.dp))
                        .background(ctaColor)
                        .clickable(
                            interactionSource = ctaInteractionSource,
                            indication = null,
                        ) {
                            closedInternally = true
                            onClose?.invoke()
                            onClick()
                        }
                        .padding(horizontal = 32.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "▶",
                            color = ctaTextColor,
                            fontSize = (ctaFontSize - 2).sp,
                        )
                        Text(
                            text = ctaText,
                            color = ctaTextColor,
                            fontSize = ctaFontSize.sp,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Close button — top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        closedInternally = true
                        onClose?.invoke()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = titleTextColor,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
