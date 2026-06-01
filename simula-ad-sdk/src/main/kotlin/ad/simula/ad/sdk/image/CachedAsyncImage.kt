package ad.simula.ad.sdk.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drop-in replacement for Coil's `AsyncImage`, backed by [ImageCache].
 *
 * Loads through the shared cache (so the same URL is decoded once and reused
 * across the menu, invitations and interstitials) and renders a still image.
 * Animated sources surface their first frame — these call sites (avatars,
 * backgrounds) are intentionally static. Decode happens off the main thread;
 * the state write resumes on the composition (main) dispatcher.
 */
@Composable
internal fun CachedAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onError: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(model) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(model) {
        bitmap = null
        val url = model?.takeIf { it.isNotBlank() }
        if (url == null) {
            onError?.invoke()
            return@LaunchedEffect
        }
        when (val result = ImageCache.load(context, url)) {
            is DecodedImage.Static -> bitmap = result.bitmap
            is DecodedImage.Animated -> {
                val frame = withContext(Dispatchers.IO) { ImageDecoder.decodeStatic(result.bytes) }
                if (frame != null) bitmap = frame else onError?.invoke()
            }
            DecodedImage.Failed -> onError?.invoke()
        }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}
