package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.StoreOpen
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Routes a creative's call-to-action tap to its destination.
 *
 * - `destination == "web"`: open the tracking URL directly in a browser.
 * - `destination == "appstore"` (default): the tracking URL is typically a click
 *   tracker that 30x-redirects to a Play Store page. We resolve the redirect chain
 *   off the main thread, and if it lands on a Play link, launch the Play Store app
 *   via a `market://` intent (falling back to the https URL). Otherwise we open the
 *   resolved URL in a browser.
 *
 * All work is launched on [SimulaScope] so it survives the host Activity being
 * auto-dismissed, and every `startActivity` is wrapped in [runCatching].
 */
internal object CreativeCtaRouter {

    private const val MAX_REDIRECTS = 8
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * Opens the advertiser destination for a creative CTA.
     *
     * [storeOpen] selects the store surface (server-driven A/B config, PRD Section 6):
     * - `INLINE_INSTALL` → try the Play in-app install overlay (half-sheet) first, degrading
     *   to the normal external Play Store app if it isn't available.
     * - `EXTERNAL` / `SKSTOREPRODUCT` (iOS-only, mapped to native store) / `null` → today's
     *   behavior: the external Play Store app for store links, a browser otherwise.
     */
    fun open(
        context: Context,
        trackingUrl: String?,
        destination: String,
        storeOpen: StoreOpen? = null,
    ) {
        val url = trackingUrl?.takeIf { it.isNotBlank() } ?: return
        val appContext = context.applicationContext
        val inlineInstall = storeOpen == StoreOpen.INLINE_INSTALL

        SimulaScope.launch {
            when (destination) {
                "web" -> openWeb(appContext, url)
                else -> openAppStore(appContext, url, inlineInstall) // "appstore" default
            }
        }
    }

    private fun openWeb(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun openAppStore(context: Context, originalUrl: String, inlineInstall: Boolean = false) {
        val resolved = resolveRedirects(originalUrl)

        // Prefer the resolved URL, but also check the original in case it was already
        // a Play link (e.g. a market:// or play.google.com link with no redirect).
        val playPackage = extractPlayPackage(resolved) ?: extractPlayPackage(originalUrl)
        if (playPackage != null) {
            // `inline_install`: ask Play for the in-app install overlay (half-sheet) first.
            // `overlay`/`callerId` are undocumented extras; if Play ignores them we still get
            // the standard store page, and if Play is absent the launch throws and we fall
            // through to the external market / https intents below (degrade to external).
            if (inlineInstall) {
                val launchedOverlay = runCatching {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$playPackage"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("overlay", true)
                        .putExtra("callerId", context.packageName)
                    context.startActivity(intent)
                }
                if (launchedOverlay.isSuccess) return
            }

            val launchedMarket = runCatching {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$playPackage"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            if (launchedMarket.exceptionOrNull() is ActivityNotFoundException) {
                runCatching {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$playPackage"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            return
        }

        // Not a Play link — open the resolved URL in a browser.
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Follow up to [MAX_REDIRECTS] 30x hops manually (so we can inspect the final
     * URL) and return the last URL reached. Returns the input URL on any failure.
     */
    private fun resolveRedirects(startUrl: String): String {
        var current = startUrl
        repeat(MAX_REDIRECTS) {
            val next = runCatching {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        if (location.isNullOrBlank()) null
                        else URL(URL(current), location).toString()
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()

            if (next == null) return current
            current = next
        }
        return current
    }

    /**
     * If [url] is a Google Play link (host contains play.google.com, or a
     * market:// scheme) return its `id` query param (the package name), else null.
     */
    private fun extractPlayPackage(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host ?: ""
        val isPlay = host.contains("play.google.com", ignoreCase = true) ||
            uri.scheme.equals("market", ignoreCase = true)
        if (!isPlay) return null
        return runCatching { uri.getQueryParameter("id") }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
