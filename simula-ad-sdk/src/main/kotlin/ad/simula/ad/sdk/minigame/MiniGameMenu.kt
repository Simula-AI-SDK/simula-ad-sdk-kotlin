package ad.simula.ad.sdk.minigame

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import ad.simula.ad.sdk.R
import ad.simula.ad.sdk.model.Defaults
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.useSimula
import ad.simula.ad.sdk.util.ColorUtil
import ad.simula.ad.sdk.util.FontUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen mini game menu composable.
 * Equivalent to React's MiniGameMenu.tsx (737 lines).
 *
 * Displays a game catalog in a modal, supports search, pagination, game WebView,
 * and post-game ad display.
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

    // ── State (mirrors React useState calls exactly) ────────────────────────
    var selectedGameId by remember { mutableStateOf<String?>(null) }
    var imageError by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<GameData>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf(false) }
    var adFetched by remember { mutableStateOf(false) }
    var adIframeUrl by remember { mutableStateOf<String?>(null) }
    var currentAdId by remember { mutableStateOf<String?>(null) }
    var lastGameHeightDp by remember { mutableStateOf<Float?>(null) }
    var lastGameWasBottomSheet by remember { mutableStateOf(false) }

    // ── Derived state ───────────────────────────────────────────────────────
    val filteredGames by remember(games, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) games
            else {
                val query = searchQuery.lowercase().trim()
                games.filter { it.name.lowercase().contains(query) }
            }
        }
    }

    // ── Theme ───────────────────────────────────────────────────────────────
    val appliedTitleFont = FontUtil.parseFont(theme.titleFont ?: Defaults.MiniGameMenuTheme.TITLE_FONT)
    val appliedSecondaryFont = FontUtil.parseFont(theme.secondaryFont ?: Defaults.MiniGameMenuTheme.SECONDARY_FONT)
    val appliedTitleFontColor = ColorUtil.parseColor(
        theme.titleFontColor ?: Defaults.MiniGameMenuTheme.TITLE_FONT_COLOR
    )
    val appliedSecondaryFontColor = ColorUtil.parseColor(
        theme.secondaryFontColor ?: Defaults.MiniGameMenuTheme.SECONDARY_FONT_COLOR
    )
    val appliedBorderColor = ColorUtil.parseColor(
        theme.borderColor ?: Defaults.MiniGameMenuTheme.BORDER_COLOR
    )
    val appliedAccentColor = ColorUtil.parseColor(
        theme.accentColor ?: Defaults.MiniGameMenuTheme.ACCENT_COLOR
    )
    val appliedBackgroundColor = if (theme.backgroundColor != null)
        ColorUtil.parseColor(theme.backgroundColor) else Color.White
    val appliedHeaderColor = if (theme.headerColor != null)
        ColorUtil.parseColor(theme.headerColor) else Color.Transparent

    // ── Reset search when menu closes ───────────────────────────────────────
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            searchQuery = ""
            isSearchFocused = false
        }
    }

    // ── Fetch catalog when menu opens ───────────────────────────────────────
    LaunchedEffect(isOpen) {
        if (!isOpen) return@LaunchedEffect

        catalogLoading = true
        catalogError = false
        try {
            val result = SimulaApiClient.fetchCatalog()
            games = result.games
            menuId = result.menuId.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            catalogError = true
            games = emptyList()
            menuId = null
        } finally {
            catalogLoading = false
        }
    }

    // ── Handlers ────────────────────────────────────────────────────────────
    fun handleClose() {
        onClose()
    }

    fun handleGameSelect(gameId: String, gameName: String) {
        // Track click (best effort)
        val currentMenuId = menuId
        if (currentMenuId != null && gameName.isNotBlank()) {
            scope.launch {
                SimulaApiClient.trackMenuGameClick(currentMenuId, gameName, simulaContext.apiKey)
            }
        }
        // Menu stays open behind game Dialog to prevent black flash
        selectedGameId = gameId
        adFetched = false
        currentAdId = null
    }

    fun handleAdIdReceived(adId: String) {
        currentAdId = adId
    }

    fun handleIframeClose() {
        if (!adFetched) {
            val aid = currentAdId
            if (aid != null) {
                scope.launch {
                    try {
                        val url = SimulaApiClient.fetchAdForMinigame(aid)
                        if (url != null) {
                            adIframeUrl = url
                            adFetched = true
                        }
                    } catch (_: Exception) {
                        // Ad fetch failed -- no ad to show
                    }
                    // Close game AFTER fetch so there's no gap
                    selectedGameId = null
                    if (adIframeUrl == null) {
                        handleClose()
                    }
                }
            } else {
                selectedGameId = null
                handleClose()
            }
        } else {
            selectedGameId = null
            handleClose()
        }
    }

    fun handleAdIframeClose() {
        adIframeUrl = null
        handleClose()
    }

    // ── Character initials fallback ─────────────────────────────────────────
    fun getInitials(name: String): String {
        return name.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .take(2)
            .joinToString("")
    }

    // ── Early return if nothing to show ─────────────────────────────────────
    if (!isOpen && selectedGameId == null && adIframeUrl == null) return

    // ── Back handler chain: ad -> game -> menu ──────────────────────────────
    BackHandler(enabled = adIframeUrl != null) {
        handleAdIframeClose()
    }
    BackHandler(enabled = selectedGameId != null && adIframeUrl == null) {
        handleIframeClose()
    }
    BackHandler(enabled = isOpen && selectedGameId == null && adIframeUrl == null) {
        handleClose()
    }

    // ── Single Dialog for all screens (one window = no black flash on transitions)
    if (isOpen || selectedGameId != null || adIframeUrl != null) {
        Dialog(
            onDismissRequest = { handleClose() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            LaunchedEffect(dialogWindow) {
                dialogWindow?.let { window ->
                    window.setDimAmount(0f)
                    window.setBackgroundDrawableResource(android.R.color.transparent)
                    window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                    // Make the Dialog window truly fullscreen, including cutout/notch areas
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
                }
            }
            // Backdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000)) // rgba(0,0,0,0.5)
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
                // Modal Card
                Column(
                    modifier = Modifier
                        .widthIn(min = 320.dp, max = 600.dp)
                        .fillMaxWidth(0.95f)
                        .shadow(
                            elevation = 25.dp,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(appliedBackgroundColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { /* stop propagation */ },
                ) {
                    // ── Header ──────────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(appliedHeaderColor)
                            .border(
                                width = 0.dp,
                                color = Color.Transparent,
                            )
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Character Avatar
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(appliedBackgroundColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!imageError && charImage.isNotBlank()) {
                                AsyncImage(
                                    model = charImage,
                                    contentDescription = charName,
                                    contentScale = ContentScale.Crop,
                                    onState = { state ->
                                        if (state is AsyncImagePainter.State.Error) {
                                            imageError = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = getInitials(charName),
                                    fontSize = 16.sp,
                                    fontFamily = appliedTitleFont,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appliedTitleFontColor,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title
                        Text(
                            text = "Play a Game with $charName",
                            fontSize = 18.sp,
                            fontFamily = appliedTitleFont,
                            fontWeight = FontWeight.SemiBold,
                            color = appliedTitleFontColor,
                            modifier = Modifier.weight(1f),
                            lineHeight = 22.sp,
                        )

                        // Close button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { handleClose() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 24.sp,
                                color = appliedSecondaryFontColor,
                            )
                        }
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(appliedBorderColor),
                    )

                    // ── Content ─────────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(20.dp),
                        contentAlignment = if (catalogError || catalogLoading)
                            Alignment.Center else Alignment.TopStart,
                    ) {
                        when {
                            catalogLoading -> {
                                // Loading spinner
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
                                // Error state
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.padding(20.dp),
                                ) {
                                    // Unavailable image
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
                                Column {
                                    // Search Bar
                                    if (games.isNotEmpty()) {
                                        SearchBar(
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            onClear = { searchQuery = "" },
                                            isFocused = isSearchFocused,
                                            onFocusChange = { isSearchFocused = it },
                                            accentColor = appliedAccentColor,
                                            borderColor = appliedBorderColor,
                                            textColor = appliedTitleFontColor,
                                            secondaryColor = appliedSecondaryFontColor,
                                            backgroundColor = appliedBackgroundColor,
                                            fontFamily = appliedSecondaryFont,
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }

                                    // No Results
                                    if (filteredGames.isEmpty() && searchQuery.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 24.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "No games found for \"$searchQuery\"",
                                                fontSize = 14.sp,
                                                fontFamily = appliedSecondaryFont,
                                                color = appliedSecondaryFontColor,
                                            )
                                        }
                                    } else {
                                        GameGrid(
                                            games = filteredGames,
                                            maxGamesToShow = maxGamesToShow,
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

            // ── Game Iframe (on top of menu) ────────────────────────────────
            if (selectedGameId != null) {
                GameWebView(
                    gameId = selectedGameId!!,
                    charID = charID,
                    charName = charName,
                    charImage = charImage,
                    charDesc = charDesc,
                    messages = messages,
                    delegateChar = delegateChar,
                    onClose = { handleIframeClose() },
                    onAdIdReceived = { handleAdIdReceived(it) },
                    menuId = menuId,
                    playableHeight = theme.playableHeight,
                    playableBorderColor = theme.playableBorderColor ?: "#262626",
                    onDimensionsOnClose = { heightDp, isBottomSheet ->
                        lastGameHeightDp = heightDp
                        lastGameWasBottomSheet = isBottomSheet
                    },
                )
            }

            // ── Ad Iframe (on top of everything) ────────────────────────────
            if (adIframeUrl != null) {
                AdIframeOverlay(
                    url = adIframeUrl!!,
                    onClose = { handleAdIframeClose() },
                    playableHeightDp = if (lastGameWasBottomSheet) lastGameHeightDp else null,
                    playableBorderColor = theme.playableBorderColor ?: "#262626",
                )
            }
        }
    }
}

// ── Search Bar ──────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    accentColor: Color,
    borderColor: Color,
    textColor: Color,
    secondaryColor: Color,
    backgroundColor: Color,
    fontFamily: FontFamily = FontFamily.SansSerif,
) {
    val currentBorderColor = if (isFocused) accentColor else borderColor

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, currentBorderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Search icon
            Text(
                text = "🔍",
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 8.dp),
            )

            // Text field
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = textColor,
                    fontFamily = fontFamily,
                ),
                singleLine = true,
                cursorBrush = SolidColor(accentColor),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search games...",
                                fontSize = 14.sp,
                                fontFamily = fontFamily,
                                color = secondaryColor,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            // Clear button
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClear,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        color = secondaryColor,
                    )
                }
            }
        }
    }
}

// ── Ad Iframe Overlay ───────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AdIframeOverlay(
    url: String,
    onClose: () -> Unit,
    playableHeightDp: Float? = null,
    playableBorderColor: String = "#262626",
) {
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalConfiguration.current

    // ── Countdown timer state ────────────────────────────────────────────
    var adCountdown by remember { mutableStateOf(5) }
    val ringProgress = remember { Animatable(1f) }
    var adPageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { ringProgress.animateTo(0f, tween(5000, easing = LinearEasing)) }
        repeat(5) { delay(1000); adCountdown-- }
    }

    val isBottomSheet = playableHeightDp != null
    val shouldHideStatusBar = if (isBottomSheet) {
        playableHeightDp!! >= config.screenHeightDp * 0.95f
    } else {
        true
    }

    // Hide system bars when full-screen or near-full
    if (shouldHideStatusBar) {
        DisposableEffect(Unit) {
            // Use the Dialog's window (not the Activity's) since we're inside a Dialog
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
    }

    BackHandler(enabled = true) {
        if (adCountdown <= 0) onClose()
    }

    Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .navigationBarsPadding(),
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
                // Visual-only drag handle for bottom sheet mode (no gesture)
                if (isBottomSheet) {
                    val borderColor = ColorUtil.parseColor(playableBorderColor)
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

                // Main content
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    // WebView
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
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
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl))
                                        ctx.startActivity(intent)
                                        return true
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { webView -> webView.destroy() },
                    )

                    // Loading overlay until ad page finishes painting
                    if (!adPageLoaded) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color(0xFF6B7280))
                        }
                    }

                    // Close button or countdown ring
                    if (adCountdown <= 0) {
                        CloseButton(
                            onClick = onClose,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 3.dp.toPx()
                                val radius = size.minDimension / 2f

                                // Semi-transparent black circle background
                                drawCircle(
                                    color = Color(0x66000000),
                                    radius = radius,
                                    center = center,
                                )

                                // White arc that drains from full to empty
                                val arcSize = size.minDimension - strokeWidth
                                drawArc(
                                    color = Color.White,
                                    startAngle = -90f + 360f * (1f - ringProgress.value),
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
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
}
