package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.AdReportReason
import ad.simula.ad.sdk.network.SimulaApiClient
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Persistent in-ad info ("i") affordance + report sheet — required ad disclosure on every ad surface
 * (Apple/Google expectations; advertisers expect a working report path). The small "i" sits in a
 * corner; tapping it opens an AppLovin-style menu (Interested / Not interested / Report) plus an
 * "About Simula Ads" link. Feedback and report selections post to `POST /impressions/{adId}/report`,
 * tagged by impression id.
 *
 * Reusable across surfaces — call it inside any ad's `Box` (last, so the sheet covers the rest).
 */
@Composable
internal fun BoxScope.AdInfoReportOverlay(
    adId: String,
    apiKey: String? = null,
    closeAtBottomLeft: Boolean = false,
) {
    var sheetVisible by remember { mutableStateOf(false) }

    // Persistent "i"-in-a-circle — bottom-left (AdChoices convention), tight to the edge. The visible
    // glyph stays 16dp; the hit area is enlarged (up, plus right when there's room) with the glyph
    // pinned to the corner. When the close button is also bottom-left, the hit area stays vertical-
    // only so it doesn't swallow taps meant for the close button beside it.
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(6.dp)
            .height(48.dp)
            .width(if (closeAtBottomLeft) 16.dp else 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { sheetVisible = true },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("i", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (sheetVisible) {
        AdReportSheet(
            onReport = { flag ->
                val key = apiKey ?: SimulaAds.apiKey
                SimulaScope.launch { SimulaApiClient.reportAd(adId, flag, null, key) }
            },
            onClose = { sheetVisible = false },
        )
    }
}

/**
 * AppLovin-style ad-feedback menu: Interested / Not interested / Report (which expands to reason
 * codes), plus a separate "About Simula Ads" link to simula.ad. [onReport] posts the chosen flag.
 */
@Composable
private fun AdReportSheet(
    onReport: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // 0 = menu, 1 = reasons, 2 = done
    var phase by remember { mutableStateOf(0) }

    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 12.dp)
                // Size the menu to its widest row (+ padding), left-aligned, not the full width.
                .width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The menu / reasons / done card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C2C2E))
                    // Consume taps on the card so they don't fall through and dismiss the sheet.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                when (phase) {
                    0 -> {
                        MenuRow(glyph = "✓", text = "Interested") { onReport("interested"); phase = 2 }
                        MenuDivider()
                        // "Not interested" maps to the existing `dislike` flag (BE adds `interested` later).
                        MenuRow(glyph = "✕", text = "Not interested") { onReport("dislike"); phase = 2 }
                        MenuDivider()
                        MenuRow(glyph = "⚑", text = "Report", tint = Color(0xFFFF453A)) { phase = 1 }
                    }
                    1 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "‹",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { phase = 0 }
                                    .padding(end = 10.dp),
                            )
                            Text("Report this ad", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        MenuDivider()
                        val reasons = AdReportReason.values()
                        reasons.forEachIndexed { index, reason ->
                            MenuRow(glyph = null, text = reason.label) { onReport(reason.flag); phase = 2 }
                            if (index < reasons.size - 1) MenuDivider()
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("✓", color = Color(0xFF4ADE80), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Text("Thanks for your feedback", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "We use it to show you better ads.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                            )
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onClose,
                                    )
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Done", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Separate "About Simula Ads" pill (only on the top-level menu), opens simula.ad.
            if (phase == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2C2C2E))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://simula.ad")))
                            }
                            onClose()
                        }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ⓘ", color = Color.White, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                    Text("About Simula Ads", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

@Composable
private fun MenuRow(glyph: String?, text: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp)) {
            if (glyph != null) Text(glyph, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
