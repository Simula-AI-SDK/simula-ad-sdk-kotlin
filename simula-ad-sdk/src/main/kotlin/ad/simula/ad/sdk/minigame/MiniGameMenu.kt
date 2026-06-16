package ad.simula.ad.sdk.minigame

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ad.simula.ad.sdk.ads.AdInfoReportOverlay
import ad.simula.ad.sdk.telemetry.Telemetry
import ad.simula.ad.sdk.image.CachedAsyncImage
import ad.simula.ad.sdk.R
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.model.resolve
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.useSimula
import ad.simula.ad.sdk.util.ColorUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Preloaded catalog for the imperative interstitial flow. When non-null, the
 * menu renders these games immediately instead of fetching the catalog itself.
 */
internal val LocalPreloadedCatalog =
    staticCompositionLocalOf<SimulaApiClient.CatalogResult?> { null }

/**
 * Full-screen mini game menu composable.
 * Equivalent to React's MiniGameMenu.tsx.
 *
 * Displays a game catalog in a modal with carousel (mobile) or 4-column grid (tablet),
 * game WebView, and post-game ad display.
 */
@Composable
fun MiniGameMenu(
    isOpen: Boolean,
    onClose: () -> Unit,
    charName: String,
    charID: String,
    charImage: String,
    messages: List<Message> = emptyList(),
    charDesc: String? = null,
    maxGamesToShow: Int = 6,
    theme: MiniGameTheme = MiniGameTheme(),
    delegateChar: Boolean = true,
) {
    val simulaContext = useSimula()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preloadedCatalog = LocalPreloadedCatalog.current

    // ── State ────────────────────────────────────────────────────────────────
    var selectedGameId by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<GameData>>(emptyList()) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf(false) }
    var adFetched by remember { mutableStateOf(false) }
    // Post-game ad screens (`GET /load/fallbacks/{serveId}`), revealed one per close tap.
    var fallbackAds by remember { mutableStateOf<List<SimulaApiClient.FallbackAd>>(emptyList()) }
    var fallbackAdIndex by remember { mutableStateOf(0) }
    var currentServeId by remember { mutableStateOf<String?>(null) }
    var lastGameHeightDp by remember { mutableStateOf<Float?>(null) }
    var lastGameWasBottomSheet by remember { mutableStateOf(false) }

    // The fallback screen currently on display; null when the overlay is closed.
    val currentFallbackAd = fallbackAds.getOrNull(fallbackAdIndex)

    // ── Theme (parsed once per theme identity) ───────────────────────────────
    val resolvedTheme = remember(theme) { theme.resolve() }
    val appliedTitleFont = resolvedTheme.titleFont
    val appliedSecondaryFont = resolvedTheme.secondaryFont
    val appliedTitleFontColor = resolvedTheme.titleFontColor
    val appliedSecondaryFontColor = resolvedTheme.secondaryFontColor
    val appliedBorderColor = resolvedTheme.borderColor
    val appliedBackgroundColor = resolvedTheme.backgroundColor

    // ── Fetch catalog + preload images when menu opens ───────────────────────
    LaunchedEffect(isOpen) {
        if (!isOpen) return@LaunchedEffect

        // Prewarm a WebView so the game/ad iframe opens without renderer cold-start.
        WebViewPool.prewarm(context)

        // Imperative interstitial path: catalog was already fetched by load().
        val preloaded = preloadedCatalog
        if (preloaded != null) {
            games = preloaded.games
            menuId = preloaded.menuId.takeIf { it.isNotBlank() }
            catalogError = false
            catalogLoading = false
            return@LaunchedEffect
        }

        catalogLoading = true
        catalogError = false
        try {
            val result = SimulaApiClient.fetchCatalog(simulaContext.ensureSession())
            games = result.games
            menuId = result.menuId.takeIf { it.isNotBlank() }
            // Covers load lazily per card through ImageCache — grid shows immediately.
        } catch (e: Exception) {
            catalogError = true
            games = emptyList()
            menuId = null
        } finally {
            catalogLoading = false
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────
    fun handleClose() {
        onClose()
    }

    fun handleGameSelect(gameId: String, gameName: String) {
        val currentMenuId = menuId
        if (currentMenuId != null && gameName.isNotBlank()) {
            scope.launch {
                SimulaApiClient.trackMenuGameClick(currentMenuId, gameName, simulaContext.apiKey)
            }
        }
        selectedGameId = gameId
        adFetched = false
        fallbackAds = emptyList()
        fallbackAdIndex = 0
        currentServeId = null
    }

    fun handleServeIdReceived(serveId: String) {
        currentServeId = serveId
    }

    fun handleIframeClose() {
        // Closing the game returns to the catalog menu (isOpen stays true) rather
        // than dismissing the whole menu. If post-game ad screens were fetched, the
        // ad overlay shows them first (in reveal order); otherwise clearing
        // selectedGameId re-reveals the catalog. The menu is dismissed only by an
        // explicit close/back action.
        if (!adFetched) {
            val sid = currentServeId
            if (sid != null) {
                scope.launch {
                    val ads = SimulaApiClient.fetchFallbacks(sid)
                    if (ads.isNotEmpty()) {
                        fallbackAds = ads
                        fallbackAdIndex = 0
                        adFetched = true
                    }
                    selectedGameId = null
                }
            } else {
                selectedGameId = null
            }
        } else {
            selectedGameId = null
        }
    }

    fun handleAdIframeClose() {
        // Reveal the next fetched ad screen on each close tap; after the last one,
        // return to the catalog menu (isOpen stays true) instead of dismissing —
        // the menu is dismissed only by an explicit close/back action.
        if (fallbackAdIndex + 1 < fallbackAds.size) {
            fallbackAdIndex += 1
        } else {
            fallbackAds = emptyList()
            fallbackAdIndex = 0
        }
    }

    fun getInitials(name: String): String {
        return name.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .take(2)
            .joinToString("")
    }

    // ── Early return if nothing to show ──────────────────────────────────────
    if (!isOpen && selectedGameId == null && currentFallbackAd == null) return

    // ── Back handler chain: ad -> game -> menu ───────────────────────────────
    BackHandler(enabled = currentFallbackAd != null) {
        handleAdIframeClose()
    }
    BackHandler(enabled = selectedGameId != null && currentFallbackAd == null) {
        handleIframeClose()
    }
    BackHandler(enabled = isOpen && selectedGameId == null && currentFallbackAd == null) {
        handleClose()
    }

    // ── Dialog 1: Menu Card ──────────────────────────────────────────────
        if (isOpen && selectedGameId == null && currentFallbackAd == null) {
            val configuration = LocalConfiguration.current
            val screenWidthDp = configuration.screenWidthDp
            val isMobile = screenWidthDp < 768

            Dialog(
                onDismissRequest = { handleClose() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                FullscreenDialogWindowConfig()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x80000000))
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            handleClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Modal Card — responsive sizing matching React
                    val modalWidthFraction = if (isMobile) 0.92f else 0.95f
                    val modalHeightFraction = if (isMobile) 0.85f else 0.90f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(modalWidthFraction)
                            .fillMaxHeight(modalHeightFraction)
                            .shadow(
                                elevation = 25.dp,
                                shape = RoundedCornerShape(24.dp),
                            )
                            .clip(RoundedCornerShape(24.dp))
                            .background(appliedBackgroundColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { /* stop propagation */ },
                    ) {
                        // Radial gradient overlays matching React (3 layers)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x1C60A5FA), // rgba(96,165,250,0.11)
                                            Color.Transparent,
                                        ),
                                        center = Offset(0.12f * screenWidthDp, 0.16f * 900f),
                                        radius = 520f,
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x143B82F6), // rgba(59,130,246,0.08)
                                            Color.Transparent,
                                        ),
                                        center = Offset(0.86f * screenWidthDp, 0.24f * 900f),
                                        radius = 440f,
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x1238BDF8), // rgba(56,189,248,0.07)
                                            Color.Transparent,
                                        ),
                                        center = Offset(0.52f * screenWidthDp, 0.88f * 900f),
                                        radius = 500f,
                                    ),
                                ),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = if (isMobile) 10.dp else 20.dp,
                                    end = if (isMobile) 10.dp else 20.dp,
                                    top = if (isMobile) 12.dp else 16.dp,
                                    bottom = if (isMobile) 16.dp else 20.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(if (isMobile) 12.dp else 0.dp),
                        ) {
                            // ── Header ───────────────────────────────────────
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp,
                                        top = if (isMobile) 18.dp else 10.dp,
                                    ),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Character Avatar — draws ON TOP of game icon (zIndex 2)
                                    // Painted shadow matches CSS: boxShadow 0 16px 34px rgba(0,0,0,0.45)
                                    // Compose elevation shadow is invisible on dark backgrounds,
                                    // so we paint it manually as an overflow child (Compose doesn't
                                    // clip children by default, so it extends beyond the avatar bounds).
                                    val avatarSize = if (isMobile) 72.dp else 80.dp
                                    val avatarShape = RoundedCornerShape(if (isMobile) 16.dp else 24.dp)
                                    val avatarDensity = LocalDensity.current
                                    val blurRadiusPx = with(avatarDensity) { 34.dp.toPx() }
                                    Box(
                                        modifier = Modifier
                                            .zIndex(2f)
                                            .size(avatarSize),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        // Shadow: same size as avatar, offset 16dp down, blurred 34dp
                                        // The blur extends the shadow beyond the shape edges naturally
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .offset(y = 16.dp)
                                                .graphicsLayer {
                                                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                                                        @Suppress("NewApi")
                                                        renderEffect = android.graphics.RenderEffect
                                                            .createBlurEffect(
                                                                blurRadiusPx, blurRadiusPx,
                                                                android.graphics.Shader.TileMode.DECAL,
                                                            )
                                                            .asComposeRenderEffect()
                                                    }
                                                }
                                                .background(
                                                    Color(0x73000000), // rgba(0,0,0,0.45)
                                                    avatarShape,
                                                ),
                                        )
                                        // Actual avatar card
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(avatarShape)
                                                .border(2.dp, Color(0x1A78C8FF), avatarShape)
                                                .background(Color(0x14FFFFFF)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (!imageError && charImage.isNotBlank()) {
                                                CachedAsyncImage(
                                                    model = charImage,
                                                    contentDescription = charName,
                                                    contentScale = ContentScale.Crop,
                                                    onError = { imageError = true },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            } else {
                                                Text(
                                                    text = getInitials(charName),
                                                    fontSize = 28.sp,
                                                    fontFamily = appliedTitleFont,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = appliedTitleFontColor,
                                                )
                                            }
                                        }
                                    }

                                    // Game icon — draws BEHIND avatar (zIndex 1)
                                    // Parent is 80dp to fit glow (56dp icon + 12dp inset each side)
                                    // Offset adjusted: -36 original overlap - 12 for larger container
                                    Box(
                                        modifier = Modifier
                                            .zIndex(1f)
                                            .requiredSize(80.dp)
                                            .offset(x = (-48).dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        // Radial glow behind icon (matches React inset: -12px)
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.radialGradient(
                                                        colorStops = arrayOf(
                                                            0f to Color(0x38C084FC),    // rgba(192,132,252,0.22)
                                                            0.5f to Color(0x1FEC4899),  // rgba(236,72,153,0.12)
                                                            0.78f to Color.Transparent,
                                                        ),
                                                    ),
                                                ),
                                        )
                                        Image(
                                            painter = painterResource(R.drawable.game_icon),
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(56.dp),
                                        )
                                    }

                                    // Title text — offset compensates for game icon's 80dp glow container
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .offset(x = (-44).dp),
                                    ) {
                                        Text(
                                            text = "Play a Game with",
                                            fontSize = if (isMobile) 18.sp else 19.sp,
                                            fontFamily = appliedTitleFont,
                                            fontWeight = FontWeight.Black,
                                            color = appliedTitleFontColor,
                                            letterSpacing = (-0.3).sp,
                                            lineHeight = 22.sp,
                                        )
                                        Text(
                                            text = charName,
                                            fontSize = if (isMobile) 18.sp else 19.sp,
                                            fontFamily = appliedTitleFont,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = appliedTitleFontColor.copy(alpha = 0.78f),
                                            letterSpacing = (-0.3).sp,
                                            lineHeight = 22.sp,
                                        )
                                    }
                                }

                                // Close button — absolute top-right
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(appliedSecondaryFontColor.copy(alpha = 0.08f))
                                        .border(1.dp, appliedSecondaryFontColor.copy(alpha = 0.12f), CircleShape)
                                        .clickable { handleClose() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "✕",
                                        fontSize = 14.sp,
                                        color = appliedSecondaryFontColor.copy(alpha = 0.92f),
                                    )
                                }
                            }

                            // ── Content ──────────────────────────────────────
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = if (catalogError || catalogLoading)
                                    Alignment.Center else Alignment.TopStart,
                            ) {
                                when {
                                    catalogLoading -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(40.dp),
                                                color = appliedTitleFontColor,
                                                strokeWidth = 3.dp,
                                            )
                                            Text(
                                                text = "Loading games...",
                                                fontSize = 14.sp,
                                                fontFamily = appliedSecondaryFont,
                                                color = appliedSecondaryFontColor,
                                            )
                                        }
                                    }

                                    catalogError -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            modifier = Modifier.padding(20.dp),
                                        ) {
                                            Image(
                                                painter = painterResource(R.drawable.games_unavailable),
                                                contentDescription = "Games unavailable",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(150.dp)
                                                    .clip(CircleShape),
                                            )
                                            Text(
                                                text = "No games are available to play right now. Please check back later!",
                                                fontSize = 14.sp,
                                                fontFamily = appliedSecondaryFont,
                                                color = appliedSecondaryFontColor,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }

                                    else -> {
                                        GameGrid(
                                            games = games,
                                            charID = charID,
                                            theme = theme,
                                            onGameSelect = { gameId, gameName ->
                                                handleGameSelect(gameId, gameName)
                                            },
                                            menuId = menuId,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Dialog 2: Ad Overlay ─────────────────────────────────────────────
        if (currentFallbackAd != null) {
            Dialog(
                onDismissRequest = { handleAdIframeClose() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                FullscreenDialogWindowConfig()
                // key() so each revealed screen gets fresh overlay state (countdown, WebView).
                key(fallbackAdIndex) {
                    AdIframeOverlay(
                        url = currentFallbackAd.iframeUrl,
                        onClose = { handleAdIframeClose() },
                        playableHeightDp = if (lastGameWasBottomSheet) lastGameHeightDp else null,
                        playableBorderColor = theme.playableBorderColor ?: "#262626",
                        adId = currentFallbackAd.adId,
                    )
                }
            }
        }

        // ── Dialog 3: Game WebView ───────────────────────────────────────────
        if (selectedGameId != null) {
            Dialog(
                onDismissRequest = { handleIframeClose() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                FullscreenDialogWindowConfig()
                GameWebView(
                    gameId = selectedGameId!!,
                    charID = charID,
                    charName = charName,
                    charImage = charImage,
                    charDesc = charDesc,
                    messages = messages,
                    delegateChar = delegateChar,
                    onClose = { handleIframeClose() },
                    onServeIdReceived = { handleServeIdReceived(it) },
                    menuId = menuId,
                    playableHeight = theme.playableHeight,
                    playableBorderColor = theme.playableBorderColor ?: "#262626",
                    onDimensionsOnClose = { heightDp, isBottomSheet ->
                        lastGameHeightDp = heightDp
                        lastGameWasBottomSheet = isBottomSheet
                    },
                )
            }
        }
}

// ── Fullscreen Dialog Window Config ──────────────────────────────────────────

@Composable
private fun FullscreenDialogWindowConfig() {
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    SideEffect {
        dialogWindow?.let { window ->
            window.setDimAmount(0f)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

// ── Ad Iframe Overlay ────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AdIframeOverlay(
    url: String,
    onClose: () -> Unit,
    playableHeightDp: Float? = null,
    playableBorderColor: String = "#262626",
    adId: String = "",
) {
    val context = LocalContext.current
    val view = LocalView.current

    var adCountdown by remember { mutableStateOf(5) }
    // Ring fills clockwise from the top (right to left), unfilled → filled, over the countdown.
    val ringProgress = remember { Animatable(0f) }
    var adPageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { ringProgress.animateTo(1f, tween(5000, easing = LinearEasing)) }
        repeat(5) { delay(1000); adCountdown-- }
    }

    val isBottomSheet = playableHeightDp != null

    DisposableEffect(Unit) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val activityWindow = (view.context as? Activity)?.window
        val window = dialogWindow ?: activityWindow
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = true) {
        if (adCountdown <= 0) onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)), // rgba(0,0,0,0.8) matching React
        contentAlignment = if (isBottomSheet) Alignment.BottomCenter else Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isBottomSheet) {
                        Modifier.height(playableHeightDp!!.dp)
                    } else {
                        Modifier.fillMaxSize()
                    }
                )
                .then(
                    if (isBottomSheet) {
                        Modifier.clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    } else {
                        Modifier
                    }
                )
                .background(Color.White),
        ) {
            if (isBottomSheet) {
                val borderColor = remember(playableBorderColor) { ColorUtil.parseColor(playableBorderColor) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(borderColor)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                Color.White.copy(alpha = 0.3f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        WebViewPool.acquire(
                            context = ctx,
                            client = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                    if (finishedUrl == "about:blank") return
                                    adPageLoaded = true
                                }
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val requestUrl = request?.url?.toString() ?: return false
                                    if (requestUrl == url) return false
                                    val originalHost = Uri.parse(url).host
                                    val requestHost = Uri.parse(requestUrl).host
                                    if (originalHost == requestHost) return false
                                    // Consume the navigation either way; runCatching so a custom-scheme
                                    // link with no installed handler can't throw ActivityNotFoundException
                                    // into the host (matches the other CTA sites).
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl)))
                                    }.onFailure {
                                        Telemetry.recordError(
                                            signature = "minigame:cta_open_failed",
                                            errorCode = it::class.java.simpleName,
                                            breadcrumb = "MiniGameMenu.adOverlay",
                                        )
                                    }
                                    return true
                                }
                            },
                        ).apply { loadUrl(url) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { webView -> WebViewPool.release(webView) },
                )

                if (!adPageLoaded) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color(0xFF6B7280))
                    }
                }

                if (adCountdown <= 0) {
                    CloseButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    )
                } else {
                    // Countdown ring: a 16dp circle centered in the same 48dp footprint as the close
                    // button so nothing jumps when it unlocks.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            val strokeWidth = 2.dp.toPx()
                            val radius = size.minDimension / 2f

                            drawCircle(
                                color = Color(0x66000000),
                                radius = radius,
                                center = center,
                            )

                            val arcSize = size.minDimension - strokeWidth
                            drawArc(
                                color = Color.White,
                                startAngle = -90f,
                                sweepAngle = 360f * ringProgress.value,
                                useCenter = false,
                                topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                                size = Size(arcSize, arcSize),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                        }
                        Text(
                            text = "$adCountdown",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Persistent ad-info "i" + report sheet (required disclosure on the post-game ad).
        if (adId.isNotEmpty()) {
            AdInfoReportOverlay(adId = adId)
        }
    }
}
