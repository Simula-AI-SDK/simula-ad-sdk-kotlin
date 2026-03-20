package ad.simula.ad.sdk.minigame

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ad.simula.ad.sdk.model.Defaults
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.util.ColorUtil
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

private const val MAX_VISIBLE_DOTS = 5
private const val DOT_SIZE_CURRENT = 10
private const val DOT_SIZE_ADJACENT = 8
private const val DOT_SIZE_EDGE = 6
private const val DESKTOP_PAGE_SIZE = 4
private const val SWIPE_THRESHOLD = 50f
private const val CAROUSEL_GAP_DP = 12f

/**
 * Responsive game grid: mobile carousel (<768dp) or tablet 4-column grid (>=768dp).
 * Equivalent to React's GameGrid.tsx.
 *
 * Uses only stable (non-experimental) Compose APIs for cross-version compatibility.
 */
@Composable
internal fun GameGrid(
    games: List<GameData>,
    charID: String,
    theme: MiniGameTheme,
    onGameSelect: (gameId: String, gameName: String) -> Unit,
    menuId: String? = null,
) {
    if (games.isEmpty()) return

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isMobile = screenWidthDp < 768

    if (isMobile) {
        MobileCarousel(
            games = games,
            onGameSelect = onGameSelect,
            theme = theme,
        )
    } else {
        TabletGrid(
            games = games,
            onGameSelect = onGameSelect,
            theme = theme,
        )
    }
}

// ── Mobile Carousel ──────────────────────────────────────────────────────────
// Continuous scroll carousel with snap-to-nearest using only stable APIs.
// Avoids HorizontalPager which is experimental and has breaking API changes between
// Compose Foundation versions, causing NoSuchMethodError in host apps.
//
// Uses a SINGLE Animatable<Float> as scroll position. The integer part determines
// which card is centered; the fractional part drives smooth inter-card animation.
// During drag, cards follow the finger in real-time. On release, velocity-based
// fling with spring snap to the nearest card center.

@Composable
private fun MobileCarousel(
    games: List<GameData>,
    onGameSelect: (gameId: String, gameName: String) -> Unit,
    theme: MiniGameTheme,
) {
    val n = games.size
    if (n == 0) return

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val carouselHeightDp = (screenHeightDp * 0.62f).coerceIn(450f, 640f)
    val cardHeightDp = carouselHeightDp * 0.78f
    val cardWidthDp = cardHeightDp * 9f / 16f
    val density = LocalDensity.current

    // Convert card step (width + gap) to pixels for translationX
    val cardStepPx = with(density) { (cardWidthDp + CAROUSEL_GAP_DP).dp.toPx() }

    // Single source of truth: scroll position as a continuous float.
    val scrollPosition = remember { Animatable(0f) }
    val velocityTracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(carouselHeightDp.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        // Stop any ongoing fling animation so user takes control
                        scope.launch { scrollPosition.stop() }
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        val velocityPx = velocityTracker.calculateVelocity().x
                        val velocityCards = velocityPx / cardStepPx

                        // Project where scroll would land with momentum, snap to nearest
                        val decayFactor = 0.4f
                        val projected = scrollPosition.value - velocityCards * decayFactor
                        val snapTarget = kotlin.math.round(projected).toFloat()

                        scope.launch {
                            scrollPosition.animateTo(
                                snapTarget,
                                spring(dampingRatio = 0.8f, stiffness = 200f),
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position,
                        )
                        // Convert pixel drag to scroll position delta (live finger-following)
                        val scrollDelta = dragAmount / cardStepPx
                        scope.launch {
                            scrollPosition.snapTo(scrollPosition.value - scrollDelta)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val currentPos = scrollPosition.value
        val centerIndex = kotlin.math.round(currentPos).toInt()

        // Render visible cards: center ±2
        // key(rawIndex) preserves each card's composable state (loaded images etc.)
        // when centerIndex shifts, so only the new edge card recomposes — no flash.
        for (cardOffset in -2..2) {
            val rawIndex = centerIndex + cardOffset
            key(rawIndex) {
                val gameIndex = ((rawIndex % n) + n) % n
                val game = games[gameIndex]

                // Continuous offset from center — drives position + depth
                val effectiveOffset = rawIndex.toFloat() - currentPos
                val xTranslationPx = effectiveOffset * cardStepPx
                val dist = abs(effectiveOffset).coerceAtMost(2f)
                val scale = 1f - dist * 0.08f // center=1.0, ±1=0.92, ±2=0.84

                CoverCard(
                    game = game,
                    onGameSelect = { id -> onGameSelect(id, game.name) },
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = xTranslationPx
                            scaleX = scale
                            scaleY = scale
                        }
                        .width(cardWidthDp.dp)
                        .height(cardHeightDp.dp),
                )
            }
        }
    }
}

// ── Tablet Grid ──────────────────────────────────────────────────────────────

@Composable
private fun TabletGrid(
    games: List<GameData>,
    onGameSelect: (gameId: String, gameName: String) -> Unit,
    theme: MiniGameTheme,
) {
    var currentPage by remember { mutableIntStateOf(0) }
    var isAnimating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val slideOffset = remember { Animatable(0f) }

    val totalPages by remember(games.size) {
        derivedStateOf { ceil(games.size.toFloat() / DESKTOP_PAGE_SIZE).toInt().coerceAtLeast(1) }
    }

    LaunchedEffect(totalPages) {
        if (currentPage >= totalPages && totalPages > 0) {
            currentPage = totalPages - 1
        }
    }

    val currentGames by remember(games, currentPage) {
        derivedStateOf {
            val start = currentPage * DESKTOP_PAGE_SIZE
            val end = (start + DESKTOP_PAGE_SIZE).coerceAtMost(games.size)
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

    fun animateToPage(newPage: Int) {
        if (isAnimating) return
        isAnimating = true
        val direction = if (newPage > currentPage) -1f else 1f

        scope.launch {
            slideOffset.animateTo(
                targetValue = direction * 0.3f,
                animationSpec = tween(125),
            )
            currentPage = newPage
            slideOffset.snapTo(-direction * 0.15f)
            slideOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(125),
            )
            isAnimating = false
        }
    }

    var totalDragX by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            // 4-column grid layout
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                for (game in currentGames) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        CoverCard(
                            game = game,
                            onGameSelect = { gameId ->
                                onGameSelect(gameId, game.name)
                            },
                        )
                    }
                }
                // Fill remaining columns with empty boxes for alignment
                repeat(DESKTOP_PAGE_SIZE - currentGames.size) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        // Dot Pagination
        if (showPagination) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (dot in visibleDots) {
                    val isCurrent = dot.pageIndex == currentPage
                    val dotSize = getDotSize(dot.pageIndex, currentPage)
                    val dotOpacity = getDotOpacity(dot.pageIndex, currentPage)

                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
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

// ── Helpers ──────────────────────────────────────────────────────────────────

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
