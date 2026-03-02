package ad.simula.ad.sdk.minigame

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import ad.simula.ad.sdk.model.Defaults.MiniGameInvitationDefaults
import ad.simula.ad.sdk.model.MiniGameInvitationAnimation
import ad.simula.ad.sdk.model.MiniGameInvitationTheme
import ad.simula.ad.sdk.util.ColorUtil
import ad.simula.ad.sdk.util.ParsedOffset
import ad.simula.ad.sdk.util.applyWidth
import ad.simula.ad.sdk.util.parseDimension
import ad.simula.ad.sdk.util.parseOffset
import kotlinx.coroutines.delay

/**
 * Mini game invitation banner composable.
 * Equivalent to React's MiniGameInvitation.tsx.
 *
 * Fixed-position banner that invites the user to play a game.
 * Supports multiple animation types, auto-close, and character image display.
 */
@Composable
fun MiniGameInvitation(
    titleText: String = "Want to play a game?",
    subText: String = "Take a break and challenge yourself!",
    ctaText: String = "Play a Game",
    charImage: String,
    animation: MiniGameInvitationAnimation = MiniGameInvitationAnimation.AUTO,
    theme: MiniGameInvitationTheme = MiniGameInvitationTheme(),
    isOpen: Boolean = false,
    autoCloseDuration: Long? = null,
    width: Any? = null,
    top: Any? = 0.05,
    onClick: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    var imageError by remember { mutableStateOf(false) }
    var shouldRender by remember { mutableStateOf(false) }

    // Applied theme with defaults
    val cornerRadius = theme.cornerRadius ?: MiniGameInvitationDefaults.CORNER_RADIUS
    val bgColor = ColorUtil.parseColor(
        theme.backgroundColor ?: MiniGameInvitationDefaults.BACKGROUND_COLOR
    )
    val textColor = ColorUtil.parseColor(
        theme.textColor ?: MiniGameInvitationDefaults.TEXT_COLOR
    )
    val ctaColor = ColorUtil.parseColor(
        theme.ctaColor ?: MiniGameInvitationDefaults.CTA_COLOR
    )
    val charImageCornerRadius = theme.charImageCornerRadius
        ?: MiniGameInvitationDefaults.CHAR_IMAGE_CORNER_RADIUS
    val charImageAnchor = theme.charImageAnchor
        ?: MiniGameInvitationDefaults.CHAR_IMAGE_ANCHOR
    val borderWidth = theme.borderWidth ?: MiniGameInvitationDefaults.BORDER_WIDTH
    val borderColor = ColorUtil.parseColor(
        theme.borderColor ?: MiniGameInvitationDefaults.BORDER_COLOR
    )
    val fontSize = theme.fontSize ?: MiniGameInvitationDefaults.FONT_SIZE

    val animDuration = MiniGameInvitationDefaults.ANIMATION_DURATION_MS

    // Handle open/close state
    LaunchedEffect(isOpen) {
        if (isOpen) {
            shouldRender = true
            imageError = false
        } else {
            // Small delay for exit animation
            delay(animDuration.toLong())
            shouldRender = false
        }
    }

    // Auto-close timer
    LaunchedEffect(isOpen, autoCloseDuration) {
        if (isOpen && autoCloseDuration != null && autoCloseDuration > 0) {
            delay(autoCloseDuration)
            onClose?.invoke()
        }
    }

    if (!shouldRender && !isOpen) return

    // Calculate top offset
    val config = LocalConfiguration.current
    val topOffset = when (val parsed = parseOffset(top)) {
        is ParsedOffset.Percentage -> (config.screenHeightDp * parsed.fraction).dp
        is ParsedOffset.Fixed -> parsed.dp
        null -> (config.screenHeightDp * 0.05f).dp
    }

    // Width modifier
    val widthDimension = parseDimension(width)

    // Determine enter/exit animations
    val effectiveAnimation = if (animation == MiniGameInvitationAnimation.AUTO)
        MiniGameInvitationAnimation.SLIDE_DOWN else animation
    val isNoAnimation = effectiveAnimation == MiniGameInvitationAnimation.NONE

    val enterTransition = when (effectiveAnimation) {
        MiniGameInvitationAnimation.SLIDE_DOWN, MiniGameInvitationAnimation.AUTO ->
            slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(animDuration),
            ) + fadeIn(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.SLIDE_UP ->
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(animDuration),
            ) + fadeIn(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.FADE_IN ->
            fadeIn(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.NONE ->
            fadeIn(animationSpec = tween(0))
    }

    val exitTransition = when (effectiveAnimation) {
        MiniGameInvitationAnimation.SLIDE_DOWN, MiniGameInvitationAnimation.AUTO ->
            slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(animDuration),
            ) + fadeOut(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.SLIDE_UP ->
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(animDuration),
            ) + fadeOut(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.FADE_IN ->
            fadeOut(animationSpec = tween(animDuration))
        MiniGameInvitationAnimation.NONE ->
            fadeOut(animationSpec = tween(0))
    }

    Popup(
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, topOffset.value.toInt()),
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(
            visible = isOpen,
            enter = enterTransition,
            exit = exitTransition,
        ) {
            Box(
                modifier = Modifier
                    .applyWidth(widthDimension)
                    .padding(horizontal = 8.dp)
                    .height(120.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(cornerRadius.dp),
                    )
                    .clip(RoundedCornerShape(cornerRadius.dp))
                    .background(bgColor)
                    .border(
                        width = borderWidth.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(cornerRadius.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier.height(120.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (charImageAnchor == "right")
                        Arrangement.Start else Arrangement.End,
                ) {
                    // Content layout depends on charImageAnchor
                    if (charImageAnchor == "right") {
                        // Text first, then image
                        InvitationTextContent(
                            titleText = titleText,
                            subText = subText,
                            ctaText = ctaText,
                            textColor = textColor,
                            ctaColor = ctaColor,
                            fontSize = fontSize,
                            onClick = {
                                onClose?.invoke()
                                onClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        CharacterImage(
                            charImage = charImage,
                            imageError = imageError,
                            onImageError = { imageError = true },
                            cornerRadius = charImageCornerRadius,
                            padding = Modifier.padding(end = 16.dp),
                        )
                    } else {
                        // Image first, then text
                        CharacterImage(
                            charImage = charImage,
                            imageError = imageError,
                            onImageError = { imageError = true },
                            cornerRadius = charImageCornerRadius,
                            padding = Modifier.padding(start = 16.dp),
                        )
                        InvitationTextContent(
                            titleText = titleText,
                            subText = subText,
                            ctaText = ctaText,
                            textColor = textColor,
                            ctaColor = ctaColor,
                            fontSize = fontSize,
                            onClick = {
                                onClose?.invoke()
                                onClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Close button (top-right, always shown)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onClose?.invoke()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        color = textColor.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InvitationTextContent(
    titleText: String,
    subText: String,
    ctaText: String,
    textColor: Color,
    ctaColor: Color,
    fontSize: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = titleText,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            lineHeight = (fontSize * 1.3f).sp,
        )
        Text(
            text = subText,
            fontSize = 13.sp,
            color = textColor.copy(alpha = 0.65f),
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ctaColor,
                contentColor = Color.White,
            ),
            contentPadding = ButtonDefaults.ContentPadding.let {
                // 6px vertical, 16px horizontal
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 6.dp,
                )
            },
        ) {
            Text(
                text = "▶",
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = ctaText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CharacterImage(
    charImage: String,
    imageError: Boolean,
    onImageError: () -> Unit,
    cornerRadius: Int,
    padding: Modifier = Modifier,
) {
    Box(
        modifier = padding,
        contentAlignment = Alignment.Center,
    ) {
        if (!imageError) {
            AsyncImage(
                model = charImage,
                contentDescription = "AI companion",
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        onImageError()
                    }
                },
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(cornerRadius.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(cornerRadius.dp))
                    .background(Color(0xFFF3F4F6)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🎮", fontSize = 32.sp)
            }
        }
    }
}
