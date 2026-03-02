package ad.simula.ad.sdk.minigame

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ad.simula.ad.sdk.model.Defaults.MiniGameButtonDefaults
import ad.simula.ad.sdk.model.MiniGameButtonTheme
import ad.simula.ad.sdk.util.ColorUtil
import ad.simula.ad.sdk.util.applyWidth
import ad.simula.ad.sdk.util.parseDimension

/**
 * Mini game trigger button composable.
 * Equivalent to React's MiniGameButton.tsx.
 *
 * Styled button with optional pulsate glow animation and badge dot.
 */
@Composable
fun MiniGameButton(
    text: String? = null,
    showPulsate: Boolean = false,
    showBadge: Boolean = false,
    theme: MiniGameButtonTheme = MiniGameButtonTheme(),
    width: Any? = null,
    onClick: () -> Unit,
) {
    // Applied theme with defaults
    val cornerRadius = theme.cornerRadius ?: MiniGameButtonDefaults.CORNER_RADIUS
    val bgColor = ColorUtil.parseColor(
        theme.backgroundColor ?: MiniGameButtonDefaults.BACKGROUND_COLOR
    )
    val textColorValue = ColorUtil.parseColor(
        theme.textColor ?: MiniGameButtonDefaults.TEXT_COLOR
    )
    val fontSize = theme.fontSize ?: MiniGameButtonDefaults.FONT_SIZE
    val borderWidthVal = theme.borderWidth ?: MiniGameButtonDefaults.BORDER_WIDTH
    val borderColorVal = ColorUtil.parseColor(
        theme.borderColor ?: MiniGameButtonDefaults.BORDER_COLOR
    )
    val pulsateColor = if (!theme.pulsateColor.isNullOrBlank())
        ColorUtil.parseColor(theme.pulsateColor) else bgColor
    val badgeColor = ColorUtil.parseColor(
        theme.badgeColor ?: MiniGameButtonDefaults.BADGE_COLOR
    )

    val displayText = text ?: "\uD83C\uDFAE Play a Game" // 🎮 Play a Game

    val hasWidth = width != null
    val widthDimension = parseDimension(width)

    // Press interaction
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "buttonScale",
    )

    // Pulsate animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulsate")
    val pulsateAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsateAlpha",
    )
    val pulsateScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulsateScale",
    )

    // Badge ping animation
    val pingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingScale",
    )
    val pingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingAlpha",
    )

    Box(
        modifier = if (hasWidth) Modifier.applyWidth(widthDimension) else Modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(
                    if (showPulsate) {
                        Modifier.drawBehind {
                            // Pulsate glow ring behind the button
                            drawRoundRect(
                                color = pulsateColor.copy(alpha = pulsateAlpha),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    cornerRadius.dp.toPx()
                                ),
                                size = size.copy(
                                    width = size.width * pulsateScale,
                                    height = size.height * pulsateScale,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(
                                    x = size.width * (1 - pulsateScale) / 2,
                                    y = size.height * (1 - pulsateScale) / 2,
                                ),
                            )
                        }
                    } else Modifier
                )
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(bgColor)
                .then(
                    if (borderWidthVal > 0)
                        Modifier.border(
                            borderWidthVal.dp,
                            borderColorVal,
                            RoundedCornerShape(cornerRadius.dp),
                        )
                    else Modifier
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(
                    horizontal = MiniGameButtonDefaults.PADDING_HORIZONTAL_DP.dp,
                    vertical = MiniGameButtonDefaults.PADDING_VERTICAL_DP.dp,
                )
                .then(if (hasWidth) Modifier.width(IntrinsicSize.Max) else Modifier),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayText,
                    color = textColorValue,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Badge dot
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(0.dp),
            ) {
                // Ping ring
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .graphicsLayer(scaleX = pingScale, scaleY = pingScale)
                        .alpha(pingAlpha)
                        .background(badgeColor, CircleShape),
                )
                // Solid dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(badgeColor, CircleShape),
                )
            }
        }
    }
}
