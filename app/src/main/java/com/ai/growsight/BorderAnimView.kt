package com.ai.growsight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class BorderAnimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Corner radius in px — set via XML attr or setCornerRadiusDp()
    private var cornerRadiusPx: Float = 12f * resources.displayMetrics.density

    // Segment length as fraction of total perimeter
    private val segmentFraction = 0.18f

    // Core bright stroke
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND   // ← was BUTT
        color = Color.parseColor("#a8e063")
    }

    // Glow
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.ROUND   // ← was BUTT
        color = Color.parseColor("#66a8e063")
    }

    private val borderPath = Path()
    private val measure = PathMeasure()
    private val seg1 = Path()
    private val seg2 = Path()
    private var progress = 0f
    private var totalLength = 0f

    // Reusable pos/tan arrays for PathMeasure
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // Read cornerRadius from XML if declared
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.BorderAnimView)
            cornerRadiusPx = a.getDimension(
                R.styleable.BorderAnimView_cornerRadius,
                12f * resources.displayMetrics.density
            )
            a.recycle()
        }
    }

    fun setCornerRadiusDp(dp: Float) {
        cornerRadiusPx = dp * resources.displayMetrics.density
        rebuildPath()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0) rebuildPath()
    }

    private fun rebuildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        borderPath.reset()
        borderPath.addRoundRect(
            4f, 4f, w - 4f, h - 4f,
            cornerRadiusPx, cornerRadiusPx,
            Path.Direction.CW
        )
        measure.setPath(borderPath, false)
        totalLength = measure.length
    }

    private fun buildSegment(out: Path, startFraction: Float) {
        out.reset()
        if (totalLength == 0f) return
        val segLen = totalLength * segmentFraction
        val start = ((startFraction % 1f + 1f) % 1f) * totalLength
        val end = start + segLen
        if (end <= totalLength) {
            measure.getSegment(start, end, out, true)
        } else {
            measure.getSegment(start, totalLength, out, true)
            measure.getSegment(0f, end - totalLength, out, true)
        }
    }

    /**
     * Builds a LinearShader that fades from transparent at the tail
     * to the full color at the head. We approximate by sampling start/end
     * points from PathMeasure and using a linear gradient between them.
     */
    private fun makeSegmentShader(startFraction: Float, color: Int): Shader? {
        if (totalLength == 0f) return null
        val segLen = totalLength * segmentFraction
        val startDist = ((startFraction % 1f + 1f) % 1f) * totalLength
        val endDist = startDist + segLen

        // Head point (full color) — clamp to path length for end
        val headDist = minOf(endDist, totalLength)
        measure.getPosTan(headDist % totalLength, pos, tan)
        val hx = pos[0]; val hy = pos[1]

        // Tail point (transparent)
        measure.getPosTan(startDist % totalLength, pos, tan)
        val tx = pos[0]; val ty = pos[1]

        val transparentColor = color and 0x00FFFFFF  // zero alpha, keep rgb
        return LinearGradient(tx, ty, hx, hy, transparentColor, color, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (totalLength == 0f) return

        buildSegment(seg1, progress)
        buildSegment(seg2, progress + 0.5f)

        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Segment 1
        glowPaint.shader = makeSegmentShader(progress, Color.parseColor("#66a8e063"))
        corePaint.shader = makeSegmentShader(progress, Color.parseColor("#a8e063"))
        canvas.drawPath(seg1, glowPaint)
        canvas.drawPath(seg1, corePaint)

        // Segment 2 (opposite side)
        glowPaint.shader = makeSegmentShader(progress + 0.5f, Color.parseColor("#66a8e063"))
        corePaint.shader = makeSegmentShader(progress + 0.5f, Color.parseColor("#a8e063"))
        canvas.drawPath(seg2, glowPaint)
        canvas.drawPath(seg2, corePaint)

        canvas.restoreToCount(count)
    }

    fun startAnim() {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        // Force path rebuild if size is available but path wasn't built yet
        if (totalLength == 0f && width > 0 && height > 0) rebuildPath()
        if (!animator.isRunning) animator.start()
    }

    fun stopAnim() {
        animator.cancel()
        setLayerType(LAYER_TYPE_NONE, null)
        invalidate()
    }
}