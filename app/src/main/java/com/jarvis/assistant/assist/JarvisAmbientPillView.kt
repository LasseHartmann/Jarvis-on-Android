package com.jarvis.assistant.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import com.jarvis.assistant.agent.HudColorTheme
import com.jarvis.assistant.agent.JarvisState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compact, zero-distraction 144Hz passive status pill rendered via TYPE_ACCESSIBILITY_OVERLAY
 * (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE) so Jarvis stays visibly alive while listening,
 * thinking, or executing actions over any app without ever blocking touches or Accessibility.
 */
class JarvisAmbientPillView(context: Context) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density

    private var theme: HudColorTheme = HudColorTheme.JARVIS_AMBER
    private var mode: JarvisState = JarvisState.THINKING
    private var label: String = "Thinking"
    private var targetRms: Float = 0f
    private var smoothRms: Float = 0f

    private var animRunning = false
    private var lastFrameNanos = 0L
    private var elapsedSec = 0f
    private var visibilityProgress = 0f // 0 = hidden, 1 = fully shown
    private var targetVisible = false
    private var onFullyHidden: (() -> Unit)? = null

    private var currentPillWidth = 128f * density
    private var targetPillWidth = 128f * density

    private val pillRect = RectF()
    private val arcRect = RectF()
    private val clipPath = Path()
    private val sweepMatrix = Matrix()

    // Cached sweep shader rebuilt only when palette changes (zero per-frame shader allocations)
    private var cachedSweepColorKey = 0
    private var cachedSweepShader: SweepGradient? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.45f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 12.5f * density
        letterSpacing = 0.015f
        color = Color.rgb(244, 246, 249)
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fixed compact bounding box avoids WindowManager relayout jank during smooth pill width morphing
        val w = (232f * density).toInt()
        val h = (48f * density).toInt()
        setMeasuredDimension(w, h)
    }

    fun showState(
        newState: JarvisState,
        newLabel: String,
        newTheme: HudColorTheme
    ) {
        mode = newState
        label = newLabel.ifBlank { newState.label }.take(22)
        theme = newTheme
        targetVisible = true
        onFullyHidden = null

        val measuredText = labelPaint.measureText(label)
        targetPillWidth = (measuredText + 54f * density).coerceIn(106f * density, 212f * density)
        if (visibilityProgress < 0.05f) {
            currentPillWidth = targetPillWidth
        }
        startLoop()
    }

    fun setVoiceRms(rms: Float) {
        targetRms = rms.coerceIn(0f, 1f)
    }

    fun hideGracefully(onHidden: () -> Unit) {
        targetVisible = false
        onFullyHidden = onHidden
        if (visibilityProgress <= 0.01f) {
            stopLoop()
            onFullyHidden = null
            onHidden()
            return
        }
        startLoop()
    }

    fun stopImmediately() {
        targetVisible = false
        visibilityProgress = 0f
        onFullyHidden = null
        stopLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    private fun startLoop() {
        if (!animRunning) {
            animRunning = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopLoop() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animRunning) return
        val dt = if (lastFrameNanos == 0L) 0.016f
        else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = frameTimeNanos
        elapsedSec += dt

        val speed = if (targetVisible) 1f / 0.22f else 1f / 0.18f
        visibilityProgress = if (targetVisible) {
            (visibilityProgress + dt * speed).coerceAtMost(1f)
        } else {
            (visibilityProgress - dt * speed).coerceAtLeast(0f)
        }

        currentPillWidth += (targetPillWidth - currentPillWidth) * (dt * 12f).coerceAtMost(1f)

        val rmsRate = if (targetRms > smoothRms) 18f else 7f
        smoothRms += (targetRms - smoothRms) * (dt * rmsRate).coerceAtMost(1f)

        if (!targetVisible && visibilityProgress <= 0.005f) {
            stopLoop()
            val cb = onFullyHidden
            onFullyHidden = null
            cb?.invoke()
            return
        }

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || visibilityProgress <= 0.005f) return

        val eased = easeOutCubic(visibilityProgress)
        val alpha = eased
        val scale = 0.86f + 0.14f * eased
        val slideY = -(1f - eased) * 8f * density

        val (primary, secondary, highlight) = activePalette()

        val pillH = 34f * density
        val pillW = currentPillWidth.coerceAtMost(w - 14f * density)
        val cx = w * 0.5f
        val cy = h * 0.5f

        canvas.save()
        canvas.translate(0f, slideY)
        canvas.scale(scale, scale, cx, cy)

        val left = cx - pillW * 0.5f
        val top = cy - pillH * 0.5f
        val radius = pillH * 0.5f
        pillRect.set(left, top, left + pillW, top + pillH)

        // 1. Soft dark ambient drop halo for contrast on bright white apps (WhatsApp/Settings)
        shadowPaint.color = Color.argb((74 * alpha).toInt(), 0, 0, 0)
        canvas.drawRoundRect(
            left - 2f * density,
            top + 2.5f * density,
            left + pillW + 2f * density,
            top + pillH + 4.5f * density,
            radius + 2f * density,
            radius + 2f * density,
            shadowPaint
        )

        // 2. Deep smoked OLED obsidian capsule body
        bodyPaint.color = Color.argb((234 * alpha).toInt(), 11, 12, 16)
        canvas.drawRoundRect(pillRect, radius, radius, bodyPaint)

        // 3. Subtle state-driven internal glass sheen
        if (mode == JarvisState.EXECUTING) {
            drawExecutionScanSheen(canvas, pillRect, radius, primary, alpha)
        } else if (mode == JarvisState.LISTENING || mode == JarvisState.AWAITING_AUTH) {
            val glowAlpha = ((22f + smoothRms * 34f + 8f * sin(elapsedSec * 4.2f)) * alpha).toInt().coerceIn(0, 75)
            bodyPaint.shader = RadialGradient(
                left + 21f * density,
                cy,
                38f * density,
                intArrayOf(withAlpha(primary, glowAlpha), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(pillRect, radius, radius, bodyPaint)
            bodyPaint.shader = null
        }

        // 4. Hairline glass rim
        val baseRimAlpha = when (mode) {
            JarvisState.LISTENING, JarvisState.AWAITING_AUTH -> (82 + smoothRms * 80).toInt()
            JarvisState.EXECUTING -> 96
            else -> 64
        }
        borderPaint.color = withAlpha(primary, (baseRimAlpha * alpha).toInt())
        canvas.drawRoundRect(pillRect, radius, radius, borderPaint)

        // 5. Rotating liquid light shimmer along border for THINKING and EXECUTING
        if (mode == JarvisState.THINKING || mode == JarvisState.EXECUTING) {
            drawPerimeterShimmer(canvas, pillRect, radius, cx, cy, primary, highlight, alpha)
        }

        // 6. Bespoke passive micro-animation icon on the left
        val glyphCx = left + 20f * density
        when (mode) {
            JarvisState.LISTENING, JarvisState.AWAITING_AUTH ->
                drawListeningWaveform(canvas, glyphCx, cy, primary, highlight, alpha)

            JarvisState.THINKING ->
                drawThinkingOrbit(canvas, glyphCx, cy, primary, secondary, highlight, alpha)

            JarvisState.EXECUTING ->
                drawExecutingPulse(canvas, glyphCx, cy, primary, highlight, alpha)

            JarvisState.SPEAKING ->
                drawSpeakingWave(canvas, glyphCx, cy, primary, highlight, alpha)

            else ->
                drawThinkingOrbit(canvas, glyphCx, cy, primary, secondary, highlight, alpha)
        }

        // 7. Quiet sentence-case status whisper
        labelPaint.color = Color.argb((240 * alpha).toInt(), 244, 246, 249)
        val textBaseline = cy - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
        canvas.drawText(label, left + 36.5f * density, textBaseline, labelPaint)

        canvas.restore()
    }

    private fun drawExecutionScanSheen(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        primary: Int,
        alpha: Float
    ) {
        val cycle = (elapsedSec * 0.78f) % 1f
        val beamCenter = rect.left - rect.width() * 0.25f + cycle * (rect.width() * 1.5f)
        val halfBeam = rect.width() * 0.28f

        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        scanPaint.shader = LinearGradient(
            beamCenter - halfBeam, rect.top,
            beamCenter + halfBeam, rect.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(primary, (34 * alpha).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, scanPaint)
        scanPaint.shader = null
        canvas.restore()
    }

    private fun drawPerimeterShimmer(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        cx: Float,
        cy: Float,
        primary: Int,
        highlight: Int,
        alpha: Float
    ) {
        val key = primary xor (highlight shl 1)
        val shader = if (cachedSweepShader != null && cachedSweepColorKey == key) {
            cachedSweepShader!!
        } else {
            val created = SweepGradient(
                0f, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    withAlpha(primary, 165),
                    withAlpha(highlight, 235),
                    withAlpha(primary, 140),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.56f, 0.74f, 0.84f, 0.92f, 1f)
            )
            cachedSweepColorKey = key
            cachedSweepShader = created
            created
        }

        val degPerSec = if (mode == JarvisState.EXECUTING) 290f else 195f
        sweepMatrix.reset()
        sweepMatrix.setRotate((elapsedSec * degPerSec) % 360f)
        sweepMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(sweepMatrix)

        shimmerPaint.shader = shader
        shimmerPaint.alpha = (225 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, radius, radius, shimmerPaint)
        shimmerPaint.shader = null
    }

    /**
     * LISTENING: 5-bar fluid acoustic equalizer reacting to live microphone RMS with a gentle passive wave.
     */
    private fun drawListeningWaveform(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        primary: Int,
        highlight: Int,
        alpha: Float
    ) {
        val barCount = 5
        val barW = 2.0f * density
        val spacing = 3.5f * density
        val totalW = (barCount - 1) * spacing
        val startX = cx - totalW * 0.5f
        val weights = floatArrayOf(0.48f, 0.82f, 1.0f, 0.82f, 0.48f)

        for (i in 0 until barCount) {
            val phase = elapsedSec * 6.4f - i * 0.72f
            val idleWave = 0.28f + 0.24f * ((sin(phase) + 1f) * 0.5f)
            val voiceLift = smoothRms * (0.58f + 0.35f * ((sin(phase * 1.4f) + 1f) * 0.5f))
            val amp = ((idleWave + voiceLift) * weights[i]).coerceIn(0.22f, 1.15f)
            val halfH = (2.1f + amp * 5.6f) * density
            val x = startX + i * spacing

            val barColor = if (i == 2) highlight else primary
            glyphFillPaint.color = withAlpha(barColor, ((175 + amp * 75) * alpha).toInt().coerceIn(0, 255))
            canvas.drawRoundRect(
                x - barW * 0.5f,
                cy - halfH,
                x + barW * 0.5f,
                cy + halfH,
                barW * 0.5f,
                barW * 0.5f,
                glyphFillPaint
            )
        }
    }

    /**
     * THINKING: Miniature 3D orbiting neural sparks matching J.A.R.V.I.S.'s Fibonacci sphere identity.
     */
    private fun drawThinkingOrbit(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        primary: Int,
        secondary: Int,
        highlight: Int,
        alpha: Float
    ) {
        // Subtle glowing nucleus
        val corePulse = 0.85f + 0.15f * sin(elapsedSec * 4.5f)
        glyphFillPaint.color = withAlpha(primary, (75 * alpha).toInt())
        canvas.drawCircle(cx, cy, 4.4f * density * corePulse, glyphFillPaint)
        glyphFillPaint.color = withAlpha(highlight, (235 * alpha).toInt())
        canvas.drawCircle(cx, cy, 1.8f * density, glyphFillPaint)

        // 3 tilted 3D orbital sparks
        val orbitRx = 7.4f * density
        val orbitRy = 3.1f * density
        val baseAngle = elapsedSec * 3.1f
        val tilts = floatArrayOf(0.45f, 1.50f, 2.55f)

        for (i in 0 until 3) {
            val theta = baseAngle + i * (2f * PI.toFloat() / 3f)
            val lx = cos(theta) * orbitRx
            val ly = sin(theta) * orbitRy
            val depth = (sin(theta) + 1f) * 0.5f // 0 (back) .. 1 (front)

            val cosT = cos(tilts[i])
            val sinT = sin(tilts[i])
            val px = cx + lx * cosT - ly * sinT
            val py = cy + lx * sinT + ly * cosT

            val dotR = (1.15f + depth * 1.15f) * density
            val dotAlpha = ((95f + depth * 155f) * alpha).toInt().coerceIn(0, 255)
            val color = if (depth > 0.65f) highlight else if (depth > 0.3f) primary else secondary
            glyphFillPaint.color = withAlpha(color, dotAlpha)
            canvas.drawCircle(px, py, dotR, glyphFillPaint)
        }
    }

    /**
     * EXECUTING: Kinetic sonar ripple + crisp dual precision arcs signaling live phone action.
     */
    private fun drawExecutingPulse(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        primary: Int,
        highlight: Int,
        alpha: Float
    ) {
        // Expanding radar sonar ring
        val rippleT = (elapsedSec * 1.15f) % 1f
        val rippleR = (2.6f + rippleT * 6.8f) * density
        val rippleAlpha = ((1f - rippleT) * 150f * alpha).toInt().coerceIn(0, 255)
        glyphStrokePaint.strokeWidth = 1.05f * density
        glyphStrokePaint.color = withAlpha(primary, rippleAlpha)
        canvas.drawCircle(cx, cy, rippleR, glyphStrokePaint)

        // Twin rotating precision arcs
        val arcR = 6.2f * density
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        val rotDeg = (elapsedSec * 240f) % 360f
        glyphStrokePaint.strokeWidth = 1.65f * density
        glyphStrokePaint.color = withAlpha(highlight, (230 * alpha).toInt())
        canvas.drawArc(arcRect, rotDeg, 82f, false, glyphStrokePaint)
        glyphStrokePaint.color = withAlpha(primary, (155 * alpha).toInt())
        canvas.drawArc(arcRect, rotDeg + 180f, 82f, false, glyphStrokePaint)

        // Bright center execution dot
        glyphFillPaint.color = withAlpha(highlight, (245 * alpha).toInt())
        canvas.drawCircle(cx, cy, 2.2f * density, glyphFillPaint)
    }

    /**
     * SPEAKING (headless reply over target app): Soft harmonic 3-bar speech cadence pulse.
     */
    private fun drawSpeakingWave(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        primary: Int,
        highlight: Int,
        alpha: Float
    ) {
        val barW = 2.2f * density
        val spacing = 4.2f * density
        val startX = cx - spacing
        for (i in 0 until 3) {
            val wave = 0.35f + 0.65f * ((sin(elapsedSec * 9.5f + i * 1.15f) * sin(elapsedSec * 3.4f + i) + 1f) * 0.5f)
            val halfH = (2.2f + wave * 4.6f) * density
            val x = startX + i * spacing
            glyphFillPaint.color = withAlpha(if (i == 1) highlight else primary, (220 * alpha).toInt())
            canvas.drawRoundRect(
                x - barW * 0.5f, cy - halfH,
                x + barW * 0.5f, cy + halfH,
                barW * 0.5f, barW * 0.5f,
                glyphFillPaint
            )
        }
    }

    private fun activePalette(): Triple<Int, Int, Int> = when (mode) {
        JarvisState.AWAITING_AUTH -> Triple(0xFFF59E0B.toInt(), 0xFFD97706.toInt(), 0xFFFEF3C7.toInt())
        JarvisState.ERROR -> Triple(0xFFEF4444.toInt(), 0xFFB91C1C.toInt(), 0xFFFEE2E2.toInt())
        else -> Triple(theme.primaryArgb, theme.secondaryArgb, theme.accentArgb)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }
}
