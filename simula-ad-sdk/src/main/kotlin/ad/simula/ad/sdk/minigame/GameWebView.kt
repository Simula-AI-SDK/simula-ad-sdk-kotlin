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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ad.simula.ad.sdk.model.Message
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.useSimula
import ad.simula.ad.sdk.util.ColorUtil

/**
 * Full-screen WebView composable for game iframes.
 * Equivalent to React's GameIframe.tsx.
 *
 * Fetches game URL via getMinigame() API, displays in a WebView.
 * Supports bottom sheet mode via playableHeight.
 */
@Composable
fun GameWebView(
    gameId: String,
    charID: String,
    charName: String,
    charImage: String,
    messages: List<Message> = emptyList(),
    delegateChar: Boolean = true,
    onClose: () -> Unit,
    onAdIdReceived: ((adId: String) -> Unit)? = null,
    charDesc: String? = null,
    menuId: String? = null,
    playableHeight: Any? = null,
    playableBorderColor: String = "#262626",
) {
    val simulaContext = useSimula()
    val context = LocalContext.current
    val config = LocalConfiguration.current

    var iframeUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fetch the minigame iframe URL
    LaunchedEffect(gameId, charID, simulaContext.sessionId) {
        val sessionId = simulaContext.sessionId
        if (sessionId.isNullOrBlank()) {
            error = "Session invalid, cannot initialize minigame"
            loading = false
            return@LaunchedEffect
        }

        loading = true
        try {
            val result = SimulaApiClient.getMinigame(
                gameType = gameId,
                sessionId = sessionId,
                currencyMode = false,
                screenWidth = config.screenWidthDp,
                screenHeight = config.screenHeightDp,
                charId = charID,
                charName = charName,
                charImage = charImage,
                charDesc = charDesc,
                messages = messages,
                delegateChar = delegateChar,
                menuId = menuId,
            )
            iframeUrl = result.iframeUrl
            if (result.adId.isNotBlank()) {
                onAdIdReceived?.invoke(result.adId)
            }
        } catch (e: Exception) {
            error = "Failed to load game. Please try again."
        } finally {
            loading = false
        }
    }

    // Back button closes the game
    BackHandler(enabled = true) {
        onClose()
    }

    val isBottomSheet = playableHeight != null
    val borderColor = ColorUtil.parseColor(playableBorderColor)

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
            contentAlignment = if (isBottomSheet) Alignment.BottomCenter else Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isBottomSheet) {
                            val heightDp = calculatePlayableHeight(playableHeight, config.screenHeightDp)
                            Modifier.height(heightDp.dp)
                        } else {
                            Modifier.fillMaxSize()
                        }
                    ),
            ) {
                // Bottom sheet drag handle
                if (isBottomSheet) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                borderColor,
                                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            )
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
                    when {
                        loading -> {
                            // Loading state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                    Text(
                                        text = "Loading game...",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        error != null -> {
                            // Error state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = error!!,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        iframeUrl != null -> {
                            // WebView
                            GameWebViewContent(url = iframeUrl!!)
                        }
                    }

                    // Close button — top right
                    CloseButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * WebView content composable. Manages WebView lifecycle properly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameWebViewContent(url: String) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val requestUrl = request?.url?.toString() ?: return false
                        // Allow the initial URL
                        if (requestUrl == url) return false
                        // Allow same-origin navigation
                        val originalHost = Uri.parse(url).host
                        val requestHost = Uri.parse(requestUrl).host
                        if (originalHost == requestHost) return false
                        // Open external URLs in system browser
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl))
                        ctx.startActivity(intent)
                        return true
                    }
                }

                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { webView ->
            webView.destroy()
        },
    )
}

/**
 * Circular close button matching the React SDK's close button style.
 */
@Composable
internal fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 48,
    backgroundColor: Color = Color.White.copy(alpha = 0.9f),
    contentColor: Color = Color(0xFF1F2937),
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            color = contentColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Calculate effective height for bottom sheet mode.
 * Mirrors React's getIframeHeight logic.
 */
private fun calculatePlayableHeight(playableHeight: Any?, screenHeightDp: Int): Int {
    if (playableHeight == null) return screenHeightDp

    if (playableHeight is Number) {
        val minHeight = 500
        return maxOf(playableHeight.toInt(), minHeight)
    }

    if (playableHeight is String && playableHeight.endsWith("%")) {
        val percent = playableHeight.removeSuffix("%").toFloatOrNull()
        if (percent != null) {
            return (screenHeightDp * percent / 100f).toInt().coerceAtLeast(500)
        }
    }

    return screenHeightDp
}
