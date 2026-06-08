package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.AdReportReason
import ad.simula.ad.sdk.network.SimulaApiClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Persistent in-ad info ("i") affordance + report sheet — required ad disclosure on every ad surface
 * (Apple/Google expectations; advertisers expect a working report path). The small "i" sits in a
 * corner; tapping it opens a sheet with "Why this ad?", "About this advertiser", and a report flow
 * whose submission posts to `POST /impressions/{adId}/report`, tagged by impression id.
 *
 * Reusable across surfaces — call it inside any ad's `Box` (last, so the sheet covers the rest).
 */
@Composable
internal fun BoxScope.AdInfoReportOverlay(
    adId: String,
    apiKey: String? = null,
    advertiser: String? = null,
) {
    var sheetVisible by remember { mutableStateOf(false) }

    // Persistent "i"-in-a-circle — bottom-left (AdChoices convention), tight to the edge.
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { sheetVisible = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }

    if (sheetVisible) {
        AdReportSheet(
            advertiser = advertiser,
            onSubmit = { reason, note ->
                val key = apiKey ?: SimulaAds.apiKey
                SimulaScope.launch { SimulaApiClient.reportAd(adId, reason.flag, note, key) }
            },
            onClose = { sheetVisible = false },
        )
    }
}

@Composable
private fun AdReportSheet(
    advertiser: String?,
    onSubmit: (AdReportReason, String?) -> Unit,
    onClose: () -> Unit,
) {
    // 0 = info, 1 = reasons, 2 = done
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
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                // Consume taps on the card so they don't fall through and dismiss the sheet.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                )
            }

            when (phase) {
                0 -> {
                    Text("About this ad", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoRow("Why this ad?", "This ad was selected for you by Simula based on the app you're using.")
                    InfoRow(
                        "About this advertiser",
                        advertiser?.takeIf { it.isNotBlank() } ?: "Advertiser details aren't available for this ad.",
                    )
                    ActionRow(text = "Report this ad", chevron = true) { phase = 1 }
                }
                1 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                .padding(end = 8.dp),
                        )
                        Text("Report this ad", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    AdReportReason.values().forEach { reason ->
                        ActionRow(text = reason.label, chevron = false) {
                            onSubmit(reason, null)
                            phase = 2
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("✓", color = Color(0xFF4ADE80), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Text("Report received", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Thanks — our team will review this ad.",
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
        }
    }
}

@Composable
private fun InfoRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}

@Composable
private fun ActionRow(text: String, chevron: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (chevron) Text("›", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
