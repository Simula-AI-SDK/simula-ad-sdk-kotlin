package ad.simula.ad.sdk.minigame

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import ad.simula.ad.sdk.model.Defaults
import ad.simula.ad.sdk.model.GameData
import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.model.MiniGameTheme
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.useSimula
import ad.simula.ad.sdk.util.ColorUtil
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
    val appliedTitleFont = theme.titleFont ?: Defaults.MiniGameMenuTheme.TITLE_FONT
    val appliedSecondaryFont = theme.secondaryFont ?: Defaults.MiniGameMenuTheme.SECONDARY_FONT
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
        handleClose()
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
                        // If ad fetch fails, just close
                    }
                }
            }
            selectedGameId = null
        } else {
            selectedGameId = null
        }
    }

    fun handleAdIframeClose() {
        adIframeUrl = null
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

    // ── Game Iframe ─────────────────────────────────────────────────────────
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
        )
    }

    // ── Ad Iframe ───────────────────────────────────────────────────────────
    if (adIframeUrl != null) {
        AdIframeOverlay(
            url = adIframeUrl!!,
            onClose = { handleAdIframeClose() },
        )
    }

    // ── Menu Modal ──────────────────────────────────────────────────────────
    if (isOpen) {
        Dialog(
            onDismissRequest = { handleClose() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            // Backdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000)) // rgba(0,0,0,0.5)
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(appliedBackgroundColor)
                        .shadow(
                            elevation = 25.dp,
                            shape = RoundedCornerShape(16.dp),
                        )
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
                                    // Unavailable image placeholder (circle)
                                    Box(
                                        modifier = Modifier
                                            .size(150.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (theme.backgroundColor != null)
                                                    appliedBackgroundColor
                                                else Color(0xFFF3F4F6)
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "🎮",
                                            fontSize = 48.sp,
                                        )
                                    }
                                    Text(
                                        text = "No games are available to play right now. Please check back later!",
                                        fontSize = 14.sp,
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
                ),
                singleLine = true,
                cursorBrush = SolidColor(accentColor),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search games...",
                                fontSize = 14.sp,
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
) {
    val context = LocalContext.current

    BackHandler(enabled = true) {
        onClose()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)), // rgba(0,0,0,0.8)
        ) {
            // WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                        webViewClient = object : WebViewClient() {
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

            // Close button
            CloseButton(
                onClick = onClose,
                size = 44,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            )
        }
    }
}
