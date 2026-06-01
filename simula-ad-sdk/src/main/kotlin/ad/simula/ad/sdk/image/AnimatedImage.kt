package ad.simula.ad.sdk.image

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders an animated source (GIF / animated WebP) by handing a platform
 * [AnimatedImageDrawable] to a native [ImageView] inside an [AndroidView]. The
 * ImageView + OS RenderThread own the entire frame loop, so there is zero
 * per-frame Compose recomposition. [isAnimating] maps to `start()`/`stop()` —
 * peripheral carousel cards are stopped, not redrawn.
 *
 * On API 24-27 (no `AnimatedImageDrawable`) or on decode failure, falls back to
 * a static first frame.
 */
@Composable
internal fun AnimatedImage(
    source: ByteArray,
    isAnimating: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        StaticFromBytes(source, modifier, contentScale)
        return
    }
    // Pause decoding when the host app is backgrounded (lifecycle below RESUMED),
    // so an off-screen GIF never keeps advancing frames. (A Dialog covering the
    // menu does not drop the Activity below RESUMED — but in that case the menu
    // is removed from composition anyway, so its drawables are already stopped.)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AnimatedDrawableView(source, isAnimating && resumed, modifier, contentScale)
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AnimatedDrawableView(
    source: ByteArray,
    isAnimating: Boolean,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val drawable = remember(source) { ImageDecoder.buildAnimatedDrawable(source) }
    if (drawable == null) {
        StaticFromBytes(source, modifier, contentScale)
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = contentScale.toScaleType()
                (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                setImageDrawable(drawable)
            }
        },
        update = {
            val anim = drawable as? AnimatedImageDrawable ?: return@AndroidView
            if (isAnimating && !anim.isRunning) anim.start()
            else if (!isAnimating && anim.isRunning) anim.stop()
        },
    )

    DisposableEffect(drawable) {
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
}

@Composable
private fun StaticFromBytes(
    bytes: ByteArray,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap = withContext(Dispatchers.IO) { ImageDecoder.decodeStatic(bytes) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/**
 * Cover renderer with the `gifCover → iconUrl → emoji` fallback chain, decoded
 * through [ImageCache]. Animated covers animate only when [isAnimating] is true.
 */
@Composable
internal fun CachedCoverImage(
    gifCover: String?,
    iconUrl: String,
    fallbackEmoji: String,
    isAnimating: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var decoded by remember(gifCover, iconUrl) { mutableStateOf<DecodedImage?>(null) }
    var failed by remember(gifCover, iconUrl) { mutableStateOf(false) }

    LaunchedEffect(gifCover, iconUrl) {
        decoded = null
        failed = false
        var result: DecodedImage? = null
        gifCover?.takeIf { it.isNotBlank() }?.let { url ->
            val r = ImageCache.load(context, url)
            if (r !is DecodedImage.Failed) result = r
        }
        if (result == null) {
            iconUrl.takeIf { it.isNotBlank() }?.let { url ->
                val r = ImageCache.load(context, url)
                if (r !is DecodedImage.Failed) result = r
            }
        }
        if (result == null) failed = true else decoded = result
    }

    when {
        failed -> EmojiFallback(fallbackEmoji, modifier)
        else -> when (val d = decoded) {
            is DecodedImage.Static ->
                Image(d.bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
            is DecodedImage.Animated ->
                AnimatedImage(d.bytes, isAnimating, modifier, ContentScale.Crop)
            DecodedImage.Failed -> EmojiFallback(fallbackEmoji, modifier)
            null -> Box(modifier) // loading: keep the card background
        }
    }
}

@Composable
private fun EmojiFallback(emoji: String, modifier: Modifier) {
    Box(
        modifier = modifier.background(Color(0x0AFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 48.sp)
    }
}

private fun ContentScale.toScaleType(): ImageView.ScaleType = when (this) {
    ContentScale.Crop -> ImageView.ScaleType.CENTER_CROP
    ContentScale.Fit -> ImageView.ScaleType.FIT_CENTER
    ContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
    ContentScale.Inside -> ImageView.ScaleType.CENTER_INSIDE
    else -> ImageView.ScaleType.CENTER_CROP
}
