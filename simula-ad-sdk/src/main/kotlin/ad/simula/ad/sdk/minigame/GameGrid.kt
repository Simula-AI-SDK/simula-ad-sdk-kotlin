package ad.simula.ad.sdk.minigame

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ad.simula.ad.sdk.model.Defaults
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.util.ColorUtil
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

private const val MAX_VISIBLE_DOTS = 5
private const val DOT_SIZE_CURRENT = 8
private const val DOT_SIZE_ADJACENT = 6
private const val DOT_SIZE_EDGE = 4
private const val SWIPE_THRESHOLD = 50f

/**
 * Paginated 3-column game grid with dot pagination and swipe support.
 * Equivalent to React's GameGrid.tsx.
 */
@Composable
fun GameGrid(
    games: List<GameData>,
    maxGamesToShow: Int = 6,
    charID: String,
    theme: MiniGameTheme,
    onGameSelect: (gameId: String, gameName: String) -> Unit,
    menuId: String? = null,
) {
    var currentPage by remember { mutableIntStateOf(0) }
    var isAnimating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val slideOffset = remember { Animatable(0f) }

    val totalPages by remember(games.size, maxGamesToShow) {
        derivedStateOf { ceil(games.size.toFloat() / maxGamesToShow).toInt().coerceAtLeast(1) }
    }

    // Reset to valid page if out of bounds
    LaunchedEffect(totalPages) {
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1
        }
    }

    val currentGames by remember(games, currentPage, maxGamesToShow) {
        derivedStateOf {
            val start = currentPage * maxGamesToShow
            val end = (start + maxGamesToShow).coerceAtMost(games.size)
            if (start < games.size) games.subList(start, end) else emptyList()
        }
    }

    val visibleDots by remember(currentPage, totalPages) {
        derivedStateOf { calculateVisibleDots(currentPage, totalPages) }
    }

    val showPagination = totalPages > 1
    val accentColor = ColorUtil.parseColor(
        theme.accentColor ?: Defaults.MiniGameMenuTheme.ACCENT_COLOR
    )

    // Animate to a new page
    fun animateToPage(newPage: Int) {
        if (isAnimating) return
        isAnimating = true
        val direction = if (newPage > currentPage) -1f else 1f

        scope.launch {
            // Slide out
            slideOffset.animateTo(
                targetValue = direction * 0.3f,
                animationSpec = tween(125),
            )
            // Snap to new page
            currentPage = newPage
            slideOffset.snapTo(-direction * 0.15f)
            // Slide in
            slideOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(125),
            )
            isAnimating = false
        }
    }

    // Track swipe gesture
    var totalDragX by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Game Grid with swipe and slide animation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = slideOffset.value * size.width
                    alpha = 1f - abs(slideOffset.value) * 2f
                }
                .pointerInput(currentPage, totalPages, isAnimating) {
                    detectHorizontalDragGestures(
                        onDragStart = { totalDragX = 0f },
                        onDragEnd = {
                            if (abs(totalDragX) >= SWIPE_THRESHOLD && !isAnimating) {
                                if (totalDragX < 0 && currentPage < totalPages - 1) {
                                    animateToPage(currentPage + 1)
                                } else if (totalDragX > 0 && currentPage > 0) {
                                    animateToPage(currentPage - 1)
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDragX += dragAmount
                        },
                    )
                }
        ) {
            // 3-column grid layout
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val rows = currentGames.chunked(3)
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (game in row) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                GameCard(
                                    game = game,
                                    charID = charID,
                                    theme = theme,
                                    onGameSelect = { gameId ->
                                        onGameSelect(gameId, game.name)
                                    },
                                    menuId = menuId,
                                )
                            }
                        }
                        // Fill remaining columns with empty boxes for alignment
                        repeat(3 - row.size) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }

        // Dot Pagination
        if (showPagination) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (dot in visibleDots) {
                    val isCurrent = dot.pageIndex == currentPage
                    val dotSize = getDotSize(dot.pageIndex, currentPage)
                    val dotOpacity = getDotOpacity(dot.pageIndex, currentPage)

                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(dotSize.dp)
                            .alpha(dotOpacity),
                        shape = CircleShape,
                        color = accentColor,
                        onClick = {
                            if (!isCurrent && !isAnimating) {
                                animateToPage(dot.pageIndex)
                            }
                        },
                        content = {},
                    )
                }
            }
        }
    }
}

// ── Dot Pagination Helpers ──────────────────────────────────────────────────

private data class DotInfo(val pageIndex: Int, val isVisible: Boolean)

private fun calculateVisibleDots(currentPage: Int, totalPages: Int): List<DotInfo> {
    if (totalPages <= MAX_VISIBLE_DOTS) {
        return List(totalPages) { DotInfo(pageIndex = it, isVisible = true) }
    }

    val halfWindow = MAX_VISIBLE_DOTS / 2
    var startPage = currentPage - halfWindow
    var endPage = currentPage + halfWindow

    if (startPage < 0) {
        startPage = 0
        endPage = MAX_VISIBLE_DOTS - 1
    }
    if (endPage >= totalPages) {
        endPage = totalPages - 1
        startPage = totalPages - MAX_VISIBLE_DOTS
    }

    return List(MAX_VISIBLE_DOTS) { i ->
        DotInfo(pageIndex = startPage + i, isVisible = true)
    }
}

private fun getDotSize(pageIndex: Int, currentPage: Int): Int {
    val distance = abs(pageIndex - currentPage)
    return when {
        distance == 0 -> DOT_SIZE_CURRENT
        distance == 1 -> DOT_SIZE_ADJACENT
        else -> DOT_SIZE_EDGE
    }
}

private fun getDotOpacity(pageIndex: Int, currentPage: Int): Float {
    val distance = abs(pageIndex - currentPage)
    return when {
        distance == 0 -> 1f
        distance == 1 -> 0.5f
        else -> 0.3f
    }
}
