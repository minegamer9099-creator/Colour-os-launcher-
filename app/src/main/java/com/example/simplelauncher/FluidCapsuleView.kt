// Location: app/src/main/java/com/example/simplelauncher/FluidCapsuleView.kt
package com.example.simplelauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import kotlin.math.min

/**
 * The black "Dynamic Island" capsule.
 *
 * It owns the SPRING ANIMATIONS: the width, height and corner roundness each have their own
 * spring, so the capsule stretches and settles with a soft, elastic overshoot instead of
 * moving linearly.
 *
 * Behaviour:
 *   - long-press  -> expands
 *   - tap (when expanded) -> collapses
 *   - auto-collapses after a few seconds without touches
 */
class FluidCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // ---- Sizes (must match the 120dp x 34dp declared in view_fluid_cloud.xml) ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val collapsedW = dp(120)
    private val collapsedH = dp(34)
    private val expandedH = dp(200)
    // 340dp wide, but never wider than the screen
    private val expandedW = min(dp(340), resources.displayMetrics.widthPixels - dp(24))

    private val collapsedRadius = collapsedH / 2f   // half the height = perfect pill
    private val expandedRadius = dp(36).toFloat()

    private val autoCollapseDelayMs = 6000L

    // ---- Public state ----
    var isExpanded = false
        private set

    /** The service listens to this to refresh data when the capsule opens. */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    // ---- Black rounded background + matching clip ----
    private val pillBackground = GradientDrawable().apply { setColor(Color.BLACK) }

    /** Current corner radius. Changing it redraws the background AND the clip outline. */
    var cornerRadiusPx: Float = collapsedRadius
        set(value) {
            field = value
            pillBackground.cornerRadius = value
            invalidateOutline()
        }

    init {
        setBackground(pillBackground)
        pillBackground.cornerRadius = cornerRadiusPx
        clipToOutline = true     // content is cut to the rounded shape
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    // ---- Content (found after the XML is inflated) ----
    private lateinit var collapsedContent: View
    private lateinit var expandedContent: View
    private lateinit var collapsedFade: SpringAnimation
    private lateinit var expandedFade: SpringAnimation

    override fun onFinishInflate() {
        super.onFinishInflate()
        collapsedContent = findViewById(R.id.collapsedContent)
        expandedContent = findViewById(R.id.expandedContent)

        // Each content block has a FIXED size and is centred at the top. As the capsule grows
        // and shrinks, it simply reveals / hides the content (like a window opening).
        collapsedContent.layoutParams = LayoutParams(collapsedW, collapsedH,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        expandedContent.layoutParams = LayoutParams(expandedW, expandedH,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL)

        expandedContent.alpha = 0f
        expandedContent.visibility = INVISIBLE

        collapsedFade = fadeSpring(collapsedContent)
        expandedFade = fadeSpring(expandedContent).apply {
            // Once fully faded out, hide it so its (invisible) buttons can't catch touches
            addEndListener { _, _, value, _ ->
                if (!isExpanded && value <= 0.01f) expandedContent.visibility = INVISIBLE
            }
        }
    }

    // ---- THE SPRINGS ----
    // Stiffness = how hard the spring pulls (higher = faster).
    // DampingRatio = how quickly the wobble dies out (1 = no bounce, lower = bouncier).
    // Width, height and corner use slightly different values so the motion feels organic.

    private val widthSpring = SpringAnimation(this, WIDTH, collapsedW.toFloat()).apply {
        spring.setStiffness(380f)
        spring.setDampingRatio(0.62f)
    }
    private val heightSpring = SpringAnimation(this, HEIGHT, collapsedH.toFloat()).apply {
        spring.setStiffness(320f)
        spring.setDampingRatio(0.58f)
    }
    private val cornerSpring = SpringAnimation(this, CORNER, collapsedRadius).apply {
        spring.setStiffness(420f)
        spring.setDampingRatio(0.9f)
    }

    // Little "squish" when you touch the capsule
    private val pressX = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
        spring.setStiffness(600f); spring.setDampingRatio(0.5f)
    }
    private val pressY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
        spring.setStiffness(600f); spring.setDampingRatio(0.5f)
    }

    /** Fade spring: no bounce (alpha must stay between 0 and 1). */
    private fun fadeSpring(target: View) = SpringAnimation(target, DynamicAnimation.ALPHA).apply {
        setSpring(androidx.dynamicanimation.animation.SpringForce().apply {
            setStiffness(1200f)
            setDampingRatio(1f)
        })
        setMinValue(0f)
        setMaxValue(1f)
    }

    // ---- Expand / collapse ----

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        expandedContent.visibility = VISIBLE
        widthSpring.animateToFinalPosition(expandedW.toFloat())
        heightSpring.animateToFinalPosition(expandedH.toFloat())
        cornerSpring.animateToFinalPosition(expandedRadius)

        collapsedFade.animateToFinalPosition(0f)
        // Let the capsule start growing first, then fade the content in
        postDelayed({ if (isExpanded) expandedFade.animateToFinalPosition(1f) }, 90)

        scheduleAutoCollapse()
        onExpandedChanged?.invoke(true)
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        removeCallbacks(autoCollapse)

        expandedFade.animateToFinalPosition(0f)
        collapsedFade.animateToFinalPosition(1f)

        widthSpring.animateToFinalPosition(collapsedW.toFloat())
        heightSpring.animateToFinalPosition(collapsedH.toFloat())
        cornerSpring.animateToFinalPosition(collapsedRadius)

        onExpandedChanged?.invoke(false)
    }

    // ---- Auto-collapse ----
    private val autoCollapse = Runnable { collapse() }

    private fun scheduleAutoCollapse() {
        removeCallbacks(autoCollapse)
        if (isExpanded) postDelayed(autoCollapse, autoCollapseDelayMs)
    }

    // ---- Touch handling ----
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true            // we want the whole gesture
        override fun onLongPress(e: MotionEvent) { expand() }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isExpanded) collapse()
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Any touch inside the capsule restarts the auto-collapse countdown
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) {
            scheduleAutoCollapse()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressTo(if (isExpanded) 0.98f else 0.94f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(1f)
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun pressTo(scale: Float) {
        pressX.animateToFinalPosition(scale)
        pressY.animateToFinalPosition(scale)
    }

    // Scale around the top-centre so it "squishes" toward the top of the screen
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoCollapse)
        widthSpring.cancel(); heightSpring.cancel(); cornerSpring.cancel()
        pressX.cancel(); pressY.cancel()
    }

    // ---- Custom properties so SpringAnimation can animate width / height / corners ----
    companion object {
        val WIDTH = object : FloatPropertyCompat<FluidCapsuleView>("capsuleWidth") {
            override fun getValue(v: FluidCapsuleView) = v.layoutParams.width.toFloat()
            override fun setValue(v: FluidCapsuleView, value: Float) {
                v.layoutParams.width = maxOf(1, value.toInt())
                v.requestLayout()
            }
        }
        val HEIGHT = object : FloatPropertyCompat<FluidCapsuleView>("capsuleHeight") {
            override fun getValue(v: FluidCapsuleView) = v.layoutParams.height.toFloat()
            override fun setValue(v: FluidCapsuleView, value: Float) {
                v.layoutParams.height = maxOf(1, value.toInt())
                v.requestLayout()
            }
        }
        val CORNER = object : FloatPropertyCompat<FluidCapsuleView>("capsuleCorner") {
            override fun getValue(v: FluidCapsuleView) = v.cornerRadiusPx
            override fun setValue(v: FluidCapsuleView, value: Float) { v.cornerRadiusPx = value }
        }
    }
}
