package ad.simula.ad.sdk.character

import android.app.Activity
import android.graphics.BlurMaskFilter
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ad.simula.ad.sdk.R
import ad.simula.ad.sdk.model.CharacterData
import ad.simula.ad.sdk.model.CharacterPickerTheme
import ad.simula.ad.sdk.model.ResolvedCharacterPickerTheme
import ad.simula.ad.sdk.model.resolve
import ad.simula.ad.sdk.network.SimulaApiClient
import ad.simula.ad.sdk.provider.useSimula

/** The character grid maxes out at 4 cards (2×2). */
private const val MAX_CHARACTERS = 4

/**
 * Full-screen "Select Your Game Partner" character picker.
 *
 * Renders a 2-column grid of selectable character cards over a black backdrop and a
 * "Launch Game" button that activates once a character is chosen. On launch it fires
 * [onLaunch] with the selected character — the picker does not launch a game itself;
 * the host wires the character into the minigame flow.
 *
 * Characters come from the `/character-selector` endpoint, with an instant fallback
 * to bundled placeholders so the grid never shows a spinner or empty state. Pass
 * [characters] to supply them directly and skip the fetch.
 *
 * Must be hosted within a [ad.simula.ad.sdk.provider.SimulaProvider] — the fetch uses
 * the provider's apiKey + session.
 *
 * Pixel-mapped from the reference HTML; presentation mirrors [ad.simula.ad.sdk.minigame.MiniGameInterstitial].
 *
 * @param isOpen      Whether the picker is shown.
 * @param onClose     Invoked when dismissed (close button / system Back).
 * @param onLaunch    Invoked with the chosen character when "Launch Game" is tapped.
 * @param title       Header text. Default "Select Your Game Partner".
 * @param launchText  Launch button label. Default "🚀 Launch Game".
 * @param characters  Optional explicit character list; null ⇒ fetch + fallback.
 * @param theme       Visual overrides; defaults match the reference HTML.
 */
@Composable
fun CharacterPicker(
    isOpen: Boolean,
    onClose: () -> Unit,
    onLaunch: (CharacterData) -> Unit,
    title: String = "Select Your Game Partner",
    launchText: String = "🚀 Launch Game",
    characters: List<CharacterData>? = null,
    theme: CharacterPickerTheme = CharacterPickerTheme(),
) {
    var closedInternally by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val resolved = remember(theme) { theme.resolve() }
    val simula = useSimula()

    // Host roster is the source of truth, capped at the 4 grid slots; the backend
    // backfills only the gap (fill = 4 - host).
    val fallback = remember { fallbackCharacterEntries() }
    val host = remember(characters) {
        characters.orEmpty().take(MAX_CHARACTERS).map { CharacterPickerEntry(it) }
    }
    val fill = MAX_CHARACTERS - host.size

    // Seed instantly so the grid is never empty: host cards render for real, the gap
    // shows loading skeletons (swapped for backend results, or bundled placeholders if
    // the fetch comes back empty) — never the placeholder characters mid-load.
    var entries by remember(characters) {
        mutableStateOf(host + loadingEntries(fill))
    }

    val isVisible = isOpen && !closedInternally

    LaunchedEffect(isOpen) {
        if (isOpen) {
            closedInternally = false
            selectedId = null
            entries = host + loadingEntries(fill) // back to the loading state on reopen
            if (fill > 0) {
                // Backfill the gap from /character-selector (needs the publisher apiKey
                // + a session). Resolve the loading state either way: real results when
                // we got any, else bundled placeholders.
                val sessionId = simula.ensureSession()
                val fetched = if (sessionId.isNullOrBlank()) emptyList()
                    else SimulaApiClient
                        .fetchCharacters(simula.apiKey, sessionId, fill)
                        .map { CharacterPickerEntry(it) }
                entries = mergeRoster(host, fetched, fallback)
            }
        }
    }

    if (!isVisible) return

    BackHandler(enabled = true) {
        closedInternally = true
        onClose()
    }

    // Shared idle-pulse clock; ignored once a selection is made (HTML stops the animation).
    val infinite = rememberInfiniteTransition(label = "charPulse")
    val pulseAnim by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = EaseInOut), RepeatMode.Reverse),
        label = "charPulsePhase",
    )
    val pulse = if (selectedId == null) pulseAnim else 0f

    // Hide system bars while the picker is up (restored on dispose) — like MiniGameInterstitial.
    val activityView = LocalView.current
    val activityWindow = (LocalContext.current as? Activity)?.window
    DisposableEffect(Unit) {
        if (activityWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(activityWindow, false)
            val controller = WindowCompat.getInsetsController(activityWindow, activityView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (activityWindow != null) {
                WindowCompat.getInsetsController(activityWindow, activityView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Dialog(
        onDismissRequest = {
            closedInternally = true
            onClose()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                window.setDimAmount(0f)
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(resolved.backgroundColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    color = resolved.titleColor,
                    fontSize = resolved.titleFontSize.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = resolved.fontFamily,
                    textAlign = TextAlign.Center,
                    lineHeight = (resolved.titleFontSize * 1.2f).sp,
                    letterSpacing = (resolved.titleFontSize * 0.01f).sp,
                )

                CharacterGrid(
                    entries = entries,
                    selectedId = selectedId,
                    pulse = pulse,
                    theme = resolved,
                    onSelect = { id -> if (selectedId != id) selectedId = id },
                )

                LaunchButton(
                    text = launchText,
                    active = selectedId != null,
                    theme = resolved,
                    onClick = {
                        val chosen = entries.firstOrNull { it.data.id == selectedId }?.data
                        if (chosen != null) {
                            closedInternally = true
                            onLaunch(chosen)
                        }
                    },
                )
            }

            // Close button — the HTML page has none, but a modal needs an escape.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(16.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        closedInternally = true
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = resolved.titleColor, fontSize = 16.sp)
            }
        }
    }
}

/** Two-column grid; the odd trailing card stays half-width (matches the HTML grid). */
@Composable
private fun CharacterGrid(
    entries: List<CharacterPickerEntry>,
    selectedId: String?,
    pulse: Float,
    theme: ResolvedCharacterPickerTheme,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { entry ->
                    if (entry.loading) {
                        CharacterSkeletonCard(theme = theme, modifier = Modifier.weight(1f))
                    } else {
                        CharacterCard(
                            entry = entry,
                            selected = entry.data.id == selectedId,
                            selectionMade = selectedId != null,
                            pulse = pulse,
                            theme = theme,
                            onClick = { onSelect(entry.data.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** "Launch Game" button — disabled until a selection, green + glowing when active. */
@Composable
private fun LaunchButton(
    text: String,
    active: Boolean,
    theme: ResolvedCharacterPickerTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(theme.launchCornerRadius.dp)
    val bg by animateColorAsState(
        if (active) theme.selectedColor else theme.launchDisabledColor,
        tween(250), label = "launchBg",
    )
    val borderColor by animateColorAsState(
        if (active) theme.selectedColor else Color(0xFF4B4B4B),
        tween(250), label = "launchBorder",
    )
    val textColor by animateColorAsState(
        if (active) theme.launchTextColor else Color(0x8CFFFFFF), // rgba(255,255,255,0.55)
        tween(250), label = "launchText",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (active && pressed) 0.99f else 1f,
        tween(120), label = "launchScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (active) {
                    Modifier.drawBehind {
                        // Green glow: box-shadow 0 0 18px rgba(61,154,102,0.32).
                        drawIntoCanvas { canvas ->
                            val r = theme.launchCornerRadius.dp.toPx()
                            val paint = Paint().apply {
                                color = theme.selectedColor.copy(alpha = 0.32f)
                                asFrameworkPaint().maskFilter =
                                    BlurMaskFilter(18.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                            }
                            canvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
                        }
                    }
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = theme.fontFamily,
        )
    }
}

/**
 * Builds the grid (≤ [MAX_CHARACTERS] cards) from the host roster and the backend
 * backfill: host cards lead, the backend fills the gap, and any slot the backend
 * didn't fill keeps its bundled placeholder so the grid is never short. Pure/testable.
 *
 * Backend items whose id already appears in the host roster are dropped (and the
 * backend list is kept distinct), so a character never shows up twice — a dropped
 * duplicate is topped up by a placeholder.
 *
 * Used for both the instant seed (`fetched` empty → host + placeholders) and the
 * post-fetch swap (`fetched` non-empty → host + results + leftover placeholders).
 *
 * @param host     host-supplied entries (capped here as a safety net).
 * @param fetched  backend results (≤ fill); empty on a no-fill or failure.
 * @param fallback bundled placeholder entries used to pad unfilled slots.
 */
internal fun mergeRoster(
    host: List<CharacterPickerEntry>,
    fetched: List<CharacterPickerEntry>,
    fallback: List<CharacterPickerEntry>,
): List<CharacterPickerEntry> {
    val capped = host.take(MAX_CHARACTERS)
    val fill = MAX_CHARACTERS - capped.size
    val seen = capped.mapTo(mutableSetOf()) { it.data.id }
    val deduped = fetched.filter { seen.add(it.data.id) }
    val filled = deduped.take(fill)
    val padding = fallback.take(fill).drop(filled.size)
    return capped + filled + padding
}

/**
 * A picker row item: the public [CharacterData] plus an optional bundled drawable used
 * by the fallback placeholders (so they render with no network). [loading] marks a
 * skeleton slot shown while the backend roster is in flight.
 */
internal data class CharacterPickerEntry(
    val data: CharacterData,
    @DrawableRes val localRes: Int? = null,
    val loading: Boolean = false,
)

/**
 * Placeholder skeleton slots for the gap while the backend roster is fetched. Synthetic
 * ids keep them distinct in the grid; they are never selectable.
 */
internal fun loadingEntries(count: Int): List<CharacterPickerEntry> =
    List(count.coerceAtLeast(0)) {
        CharacterPickerEntry(CharacterData("loading-$it", "", ""), loading = true)
    }

/**
 * Bundled placeholder characters shown until the companions endpoint returns data.
 * Images ship in `res/drawable-nodpi`.
 */
internal fun fallbackCharacterEntries(): List<CharacterPickerEntry> = listOf(
    CharacterPickerEntry(CharacterData("superman", "Superman", ""), R.drawable.char_superman),
    CharacterPickerEntry(CharacterData("hammy", "Hammy", ""), R.drawable.char_hammy),
    CharacterPickerEntry(CharacterData("maya", "Maya", ""), R.drawable.char_maya),
    CharacterPickerEntry(CharacterData("charles", "Charles", ""), R.drawable.char_charles),
)
