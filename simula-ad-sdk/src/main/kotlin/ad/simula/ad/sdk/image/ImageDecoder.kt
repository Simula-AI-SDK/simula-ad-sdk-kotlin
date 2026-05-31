package ad.simula.ad.sdk.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import android.graphics.ImageDecoder as PlatformImageDecoder

/**
 * Native image/GIF decoding — no third-party library.
 *
 * On API 28+ this uses the platform [android.graphics.ImageDecoder], which
 * handles GIF and animated WebP, EXIF rotation, and exact target sizing. Below
 * 28 it falls back to [BitmapFactory] (static first frame only). Long edge is
 * capped at [MAX_LONG_EDGE] to bound decoded memory.
 */
internal object ImageDecoder {

    const val MAX_LONG_EDGE = 800

    /**
     * Decode raw bytes into a [DecodedImage]. Animated sources (GIF / animated
     * WebP) are kept as encoded bytes on API 28+ so the render layer can build a
     * streaming [AnimatedImageDrawable] per use; everything else is rasterized to
     * a downsampled bitmap.
     */
    fun decode(bytes: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): DecodedImage {
        if (bytes.isEmpty()) return DecodedImage.Failed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isAnimatedFormat(bytes)) {
            return DecodedImage.Animated(bytes)
        }
        val bmp = decodeStatic(bytes, maxLongEdge) ?: return DecodedImage.Failed
        return DecodedImage.Static(bmp)
    }

    /**
     * Decode a single (first) frame as a downsampled [Bitmap]. Used for static
     * avatars/backgrounds and as the API 24-27 fallback for animated sources.
     * Runs the blocking [BitmapFactory] work on the caller's (background) thread.
     */
    fun decodeStatic(bytes: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /**
     * Build a live [AnimatedImageDrawable] (or a static drawable for single-frame
     * sources) from encoded bytes, downsampled to [maxLongEdge]. API 28+ only;
     * returns null on failure so the caller can fall back to a static frame.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun buildAnimatedDrawable(bytes: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): Drawable? =
        runCatching {
            val source = PlatformImageDecoder.createSource(ByteBuffer.wrap(bytes))
            PlatformImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                if (longEdge > maxLongEdge) {
                    decoder.setTargetSampleSize(
                        sampleSizeFor(info.size.width, info.size.height, maxLongEdge)
                    )
                }
            }
        }.getOrNull()

    /** Largest power-of-two sample size that keeps the long edge at or above [maxLongEdge]. */
    private fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    // ── Format sniffing (magic bytes) ────────────────────────────────────────

    private fun isAnimatedFormat(b: ByteArray): Boolean = isGif(b) || isAnimatedWebp(b)

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    /** RIFF…WEBP container carrying an ANIM chunk. */
    private fun isAnimatedWebp(b: ByteArray): Boolean {
        if (b.size < 16) return false
        if (b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte() ||
            b[2] != 'F'.code.toByte() || b[3] != 'F'.code.toByte()
        ) return false
        if (b[8] != 'W'.code.toByte() || b[9] != 'E'.code.toByte() ||
            b[10] != 'B'.code.toByte() || b[11] != 'P'.code.toByte()
        ) return false
        val limit = minOf(b.size - 4, 64)
        for (i in 12..limit) {
            if (b[i] == 'A'.code.toByte() && b[i + 1] == 'N'.code.toByte() &&
                b[i + 2] == 'I'.code.toByte() && b[i + 3] == 'M'.code.toByte()
            ) return true
        }
        return false
    }
}
