// Location: app/src/main/java/com/example/simplelauncher/DepthCurvePageTransformer.kt
package com.example.simplelauncher

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Subtle 3D "curved depth" page transition for a ViewPager2 home screen.
 *
 * While you swipe, the two visible pages:
 *   - shrink slightly            (depth zoom-out)
 *   - turn a few degrees away    (the outer edges recede, like pages on a curved carousel)
 *   - fade a little              (more depth)
 * Each page "hinges" on the edge that touches its neighbour, so the pages stay joined in the
 * middle and look like one gently bent surface.
 *
 * "position" (given by ViewPager2 for every page, every frame):
 *    0   = page is centred on screen
 *   -1   = page is one full screen to the left (just left the screen)
 *   +1   = page is one full screen to the right (about to enter)
 *
 * PERFORMANCE (aimed at 120Hz, where one frame is only ~8.3 ms):
 *   - transformPage() runs for each visible page on every frame, so it allocates NOTHING
 *     and only sets a handful of float properties.
 *   - While a page is mid-swipe it is turned into a GPU "hardware layer": the page is drawn
 *     into a texture once, and each frame the GPU only moves, scales, rotates and fades that
 *     texture. Without this, fading a page made of many icons would re-draw all of them
 *     every frame.
 *   - The layer is removed as soon as the page is settled or off-screen, so it uses no
 *     extra memory at rest.
 *
 * Tuning knobs are constructor arguments.
 */
class DepthCurvePageTransformer(
    private val minScale: Float = 0.88f,        // scale of a page that is fully "away" (1 = no zoom-out)
    private val minAlpha: Float = 0.55f,        // opacity of a page that is fully "away"
    private val maxRotationDeg: Float = 14f,    // how far the outer edge turns away (0 = flat)
    private val cameraDistanceFactor: Float = 9000f // bigger = flatter, subtler perspective
) : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val a = abs(position)

        // Settled, or completely off-screen: put the page back to normal and stop.
        if (a >= 1f || a < 0.001f) {
            reset(page)
            return
        }

        // Ease-out curve: the effect appears quickly at the start of a swipe and then
        // softens, which feels more fluid than a straight line.
        val e = a * (2f - a)

        // First frame of this page's transition: switch to a GPU layer and set perspective.
        // (Done once, not every frame.)
        if (page.layerType != View.LAYER_TYPE_HARDWARE) {
            page.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            page.cameraDistance = cameraDistanceFactor * page.resources.displayMetrics.density
        }

        // Hinge on the edge facing the centre of the screen
        val pivotX = if (position < 0f) page.width.toFloat() else 0f
        if (page.pivotX != pivotX) page.pivotX = pivotX
        val pivotY = page.height * 0.5f
        if (page.pivotY != pivotY) page.pivotY = pivotY

        // Depth zoom-out
        val scale = 1f - (1f - minScale) * e
        page.scaleX = scale
        page.scaleY = scale

        // Curve: the right page turns its right edge away, the left page its left edge
        page.rotationY = if (position > 0f) -maxRotationDeg * e else maxRotationDeg * e

        // Depth fade
        page.alpha = 1f - (1f - minAlpha) * e
    }

    /** Puts a page back to its normal look. Only touches properties that actually differ. */
    private fun reset(page: View) {
        if (page.layerType != View.LAYER_TYPE_NONE) page.setLayerType(View.LAYER_TYPE_NONE, null)
        if (page.scaleX != 1f) { page.scaleX = 1f; page.scaleY = 1f }
        if (page.rotationY != 0f) page.rotationY = 0f
        if (page.alpha != 1f) page.alpha = 1f
    }

    /**
     * One-call setup for a ViewPager2: installs this transformer and applies the settings
     * that keep swiping smooth.
     */
    fun installOn(viewPager: ViewPager2) {
        // Keep the neighbouring pages created and laid out, so nothing has to be inflated
        // in the middle of a swipe (that is what causes dropped frames).
        viewPager.offscreenPageLimit = 1
        viewPager.setPageTransformer(this)

        // ViewPager2's internal list: no stretchy edge glow, and let pages draw outside
        // their bounds a little during the 3D rotation.
        (viewPager.getChildAt(0) as? RecyclerView)?.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
        }
        viewPager.clipChildren = false
    }
}
