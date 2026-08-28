package ad.simula.ad.sdk.minigame

import android.view.View
import android.webkit.WebView
import androidx.annotation.MainThread

/**
 * Force a stale hardware layer to repaint on the next frame.
 *
 * A hardware-accelerated [WebView] discards its render-node / draw functor when it loses its
 * drawing surface — on detach (scroll-out) or when the window loses visibility (the app is
 * backgrounded, `ON_STOP`). On the next composited frame the content can come back black/blank
 * until a real redraw is forced ([WebView.onResume] resumes JS/timers, not drawing; a bare
 * [View.invalidate] is unreliable for the stale-layer case). Starting the view INVISIBLE and
 * flipping it back to VISIBLE on the next frame — once it is back on the window — guarantees the
 * visibility transition that recreates the hardware layer.
 *
 * INVISIBLE (not GONE) preserves the view's measured size so its host doesn't reflow; the view is
 * hidden for at most one frame, showing whatever (transparent) background is behind it — never
 * black — in the meantime.
 *
 * Call from the main thread (lifecycle observers / Compose effects). [shouldReveal] lets a terminal
 * renderer-death callback cancel a reveal that was already queued for the next frame.
 */
@MainThread
internal fun WebView.repaintOnNextFrame(shouldReveal: () -> Boolean = { true }) {
    visibility = View.INVISIBLE
    val reveal = Runnable {
        if (!runCatching(shouldReveal).getOrDefault(false)) return@Runnable
        visibility = View.VISIBLE
        invalidate()
    }
    if (isAttachedToWindow) {
        postOnAnimation(reveal)
        return
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            v.postOnAnimation(reveal)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
        }
    })
}
