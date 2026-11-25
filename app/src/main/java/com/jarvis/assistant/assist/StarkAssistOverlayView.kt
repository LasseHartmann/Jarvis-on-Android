package com.jarvis.assistant.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.jarvis.assistant.agent.HudColorTheme
import com.jarvis.assistant.agent.JarvisState
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimalist, modern 144Hz 3D Fibonacci neural core wake overlay for J.A.R.V.I.S.
 * - Organic 3D particle lattice inspired by Age of Ultron's golden neural entity
 * - Subtle right-edge power-button light bloom on Xiaomi 15T Pro wake
 * - Airy shadow-soft floating captions (no clunky boxes, no corner brackets, no neon borders)
 * - Sleek top floating pilot pill when click-through phone automation is active
 */
class StarkAssistOverlayView(context: Context) : View(context), Choreographer.FrameCallback {

    private var theme: HudColorTheme = HudColorTheme.JARVIS_AMBER
    private var state: JarvisState = JarvisState.LISTENING
    private var targetRms: Float = 0f
    private var smoothRms: Float = 0f
    private var pilotMode: Boolean = false
    private var statusTag: String = "Listening"
    private var primaryCaption: String = ""
    private var secondaryCaption: String = ""

    private var isClosing: Boolean = false
    private var onDismissAnimationDone: (() -> Unit)? = null

    var onCoreTap: (() -> Unit)? = null
    var onBackdropTap: (() -> Unit)? = null

    private var animRunning = false
    private var lastFrameNanos = 0L
    private var elapsedSec = 0f
    private var wakeProgress = 0f      // 0 -> 1 over 0.38s
    private var closeProgress = 0f     // 0 -> 1 over 0.22s
    private var pilotBlend = 0f        // 0 = full floating core, 1 = top pass-through pilot pill

    private val density = resources.displayMetrics.density
    private val coreCenter = floatArrayOf(0f, 0f)
    private var coreHitRadius = 86f * density

    // Precomputed 3D Fibonacci sphere lattice (64 nodes + nearest-neighbor synaptic edges)
    private val nodeCount = 64
    private val baseX = FloatArray(nodeCount)
    private val baseY = FloatArray(nodeCount)
    private val baseZ = FloatArray(nodeCount)
    private val nodePhase = FloatArray(nodeCount)
    private val projX = FloatArray(nodeCount)
    private val projY = FloatArray(nodeCount)
    private val projZ = FloatArray(nodeCount)
    private val edgeA: IntArray
    private val edgeB: IntArray

    private val pillRect = RectF()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }
    private val statusLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 12.5f * density
        letterSpacing = 0.04f
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        textSize = 13f * density
        color = Color.rgb(244, 245, 247)
    }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textSize = 18f * density
        color = Color.rgb(245, 246, 248)
        setShadowLayer(12f * density, 0f, 2f * density, Color.argb(170, 0, 0, 0))
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textSize = 13f * density
        color = Color.rgb(175, 180, 190)
        setShadowLayer(8f * density, 0f, 1f * density, Color.argb(160, 0, 0, 0))
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        val goldenAngle = PI * (3.0 - sqrt(5.0))
        for (i in 0 until nodeCount) {
            val y = 1.0 - (i.toDouble() / (nodeCount - 1)) * 2.0
            val radiusAtY = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = goldenAngle * i
            baseX[i] = (cos(theta) * radiusAtY).toFloat()
            baseY[i] = y.toFloat()
            baseZ[i] = (sin(theta) * radiusAtY).toFloat()
            nodePhase[i] = (i * 0.67f) % (2f * PI.toFloat())
        }

        val aList = ArrayList<Int>(nodeCount * 3)
        val bList = ArrayList<Int>(nodeCount * 3)
        val maxDistSq = 0.56f * 0.56f
        for (i in 0 until nodeCount) {
            for (j in i + 1 until nodeCount) {
                val dx = baseX[i] - baseX[j]
                val dy = baseY[i] - baseY[j]
                val dz = baseZ[i] - baseZ[j]
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) {
                    aList.add(i)
                    bList.add(j)
                }
            }
        }
        edgeA = aList.toIntArray()
        edgeB = bList.toIntArray()
    }

    fun configureTheme(newTheme: HudColorTheme) {
        theme = newTheme
        invalidate()
    }

    fun updateTelemetry(@Suppress("UNUSED_PARAMETER") rightReadout: String) {
        // Minimalist overlay intentionally keeps corners spotless
    }

    fun updateState(
        newState: JarvisState,
        tag: String = newState.label,
        caption: String = primaryCaption,
        subCaption: String = secondaryCaption
    ) {
        state = newState
        statusTag = tag
        primaryCaption = caption
        secondaryCaption = subCaption
        invalidate()
    }

    fun setVoiceRms(rms: Float) {
        targetRms = rms.coerceIn(0f, 1f)
    }

    fun setPilotPassThrough(active: Boolean) {
        pilotMode = active
    }

    fun startWakeAnimation() {
        isClosing = false
        closeProgress = 0f
        wakeProgress = 0f
        elapsedSec = 0f
        pilotBlend = 0f
        lastFrameNanos = 0L
        startLoop()
    }

    fun playDismissAnimation(onComplete: () -> Unit) {
        if (isClosing) return
        isClosing = true
        closeProgress = 0f
        onDismissAnimationDone = onComplete
        startLoop()
    }

    fun stopAnimation() {
        animRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun startLoop() {
        if (!animRunning) {
            animRunning = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!animRunning) return
        val dt = if (lastFrameNanos == 0L) 0.016f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = frameTimeNanos
        elapsedSec += dt

        wakeProgress = (wakeProgress + dt / 0.38f).coerceAtMost(1f)
        val pilotTarget = if (pilotMode) 1f else 0f
        pilotBlend += (pilotTarget - pilotBlend) * (dt * 9f).coerceAtMost(1f)

        val rmsSmoothing = if (targetRms > smoothRms) 16f else 6.5f
        smoothRms += (targetRms - smoothRms) * (dt * rmsSmoothing).coerceAtMost(1f)

        if (isClosing) {
            closeProgress = (closeProgress + dt / 0.22f).coerceAtMost(1f)
            if (closeProgress >= 1f) {
                stopAnimation()
                val cb = onDismissAnimationDone
                onDismissAnimationDone = null
                cb?.invoke()
                return
            }
        }

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pilotMode || isClosing) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val dist = hypot((event.x - coreCenter[0]).toDouble(), (event.y - coreCenter[1]).toDouble())
            if (dist <= coreHitRadius) {
                onCoreTap?.invoke()
            } else {
                onBackdropTap?.invoke()
            }
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val easeWake = easeOutQuint(wakeProgress)
        val lifeAlpha = easeWake * (1f - easeInCubic(closeProgress))
        if (lifeAlpha <= 0.004f) return

        val (primary, secondary, highlight) = activePalette()

        // 1. Atmospheric soft obsidian bottom fade (fades completely in pass-through Pilot Mode)
        val mainVis = lifeAlpha * (1f - pilotBlend)
        if (mainVis > 0.01f) {
            drawAtmosphericFade(canvas, w, h, primary, mainVis)
        }

        // 2. Subtle power-button origin caustic light bloom (right bezel ~44% height on Xiaomi 15T Pro)
        if (wakeProgress < 0.95f && !isClosing) {
            drawPowerButtonLightFlow(canvas, w, h, primary, highlight, wakeProgress)
        }

        // 3. Living 3D Fibonacci Neural Core (dissolves cleanly when piloting underlying apps)
        if (mainVis > 0.02f) {
            val cx = w * 0.5f
            val cy = h * 0.80f + (1f - easeWake) * 34f * density
            coreCenter[0] = cx
            coreCenter[1] = cy

            val scale = (0.45f + 0.55f * easeWake) * (1f - closeProgress * 0.55f)
            val sphereR = min(w, h) * 0.145f * scale
            coreHitRadius = maxOf(sphereR * 1.65f, 76f * density)

            draw3dNeuralCore(canvas, cx, cy, sphereR, primary, secondary, highlight, mainVis)
            drawFloatingTypography(canvas, w, h, cx, cy, sphereR, primary, mainVis)
        }

        // 4. Sleek Dynamic-Island-style top pass-through Pilot Pill when automating another app
        if (pilotBlend > 0.02f) {
            drawTopPilotPill(canvas, w, primary, highlight, lifeAlpha * pilotBlend)
        }
    }

    private fun drawAtmosphericFade(canvas: Canvas, w: Float, h: Float, primary: Int, alpha: Float) {
        // Rich obsidian atmospheric scrim across the lower region for crisp contrast over any app
        backdropPaint.shader = LinearGradient(
            0f, h * 0.25f, 0f, h,
            intArrayOf(
                Color.argb((40 * alpha).toInt(), 0, 0, 0),
                Color.argb((175 * alpha).toInt(), 0, 0, 0),
                Color.argb((244 * alpha).toInt(), 2, 3, 5)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, backdropPaint)

        // Subtle warm optical reflection pool beneath the floating sphere
        val poolR = w * (0.56f + smoothRms * 0.14f)
        backdropPaint.shader = RadialGradient(
            w * 0.5f, h * 0.84f, poolR,
            intArrayOf(
                withAlpha(primary, ((48 + smoothRms * 50) * alpha).toInt()),
                withAlpha(primary, (12 * alpha).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.42f, w, h, backdropPaint)
        backdropPaint.shader = null
    }

    private fun drawPowerButtonLightFlow(
        canvas: Canvas,
        w: Float,
        h: Float,
        primary: Int,
        highlight: Int,
        t: Float
    ) {
        val fade = sin(t * PI.toFloat()).coerceIn(0f, 1f)
        // Soft light origin on the right power-button bezel gliding slightly inward
        val bx = w - t * w * 0.12f
        val by = h * 0.44f + t * h * 0.14f
        val br = (48f + t * 180f) * density
        fillPaint.shader = RadialGradient(
            bx, by, br,
            intArrayOf(
                withAlpha(highlight, (150 * fade).toInt()),
                withAlpha(primary, (85 * fade).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bx, by, br, fillPaint)
        fillPaint.shader = null
    }

    private fun draw3dNeuralCore(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseR: Float,
        primary: Int,
        secondary: Int,
        highlight: Int,
        alpha: Float
    ) {
        val rotSpeed = when (state) {
            JarvisState.THINKING -> 1.45f
            JarvisState.SPEAKING -> 0.85f
            JarvisState.AWAITING_AUTH -> 0.55f
            else -> 0.62f
        }
        val rotY = elapsedSec * rotSpeed
        val rotX = 0.32f + sin(elapsedSec * 0.45f) * 0.18f
        val cosY = cos(rotY)
        val sinY = sin(rotY)
        val cosX = cos(rotX)
        val sinX = sin(rotX)

        val breath = when (state) {
            JarvisState.LISTENING -> 1f + smoothRms * 0.22f + sin(elapsedSec * 3.2f) * 0.03f
            JarvisState.SPEAKING -> 1f + 0.08f * sin(elapsedSec * 11f) * sin(elapsedSec * 4.1f)
            JarvisState.THINKING -> 0.90f + 0.05f * sin(elapsedSec * 5.2f)
            else -> 0.96f + 0.03f * sin(elapsedSec * 2.2f)
        }

        // Soft internal core luminosity (no hard circle edge)
        val innerAuraR = baseR * (1.28f * breath)
        fillPaint.shader = RadialGradient(
            cx, cy, innerAuraR,
            intArrayOf(
                withAlpha(highlight, ((85 + smoothRms * 60) * alpha).toInt().coerceAtMost(210)),
                withAlpha(primary, ((52 + smoothRms * 38) * alpha).toInt().coerceAtMost(155)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, innerAuraR, fillPaint)
        fillPaint.shader = null

        // Project all 64 3D Fibonacci nodes with organic liquid displacement
        val cameraDist = 3.2f
        for (i in 0 until nodeCount) {
            val rippleAmp = when (state) {
                JarvisState.LISTENING -> 0.03f + smoothRms * 0.20f
                JarvisState.SPEAKING -> 0.09f
                JarvisState.THINKING -> 0.05f
                else -> 0.02f
            }
            val wave = sin(elapsedSec * 5.5f + nodePhase[i] * 2f) * rippleAmp
            val rScale = baseR * breath * (1f + wave)

            val x0 = baseX[i]
            val y0 = baseY[i]
            val z0 = baseZ[i]

            // Rotate around Y then X
            val rx = x0 * cosY + z0 * sinY
            val rz1 = -x0 * sinY + z0 * cosY
            val ry = y0 * cosX - rz1 * sinX
            val rz = y0 * sinX + rz1 * cosX

            val persp = cameraDist / (cameraDist - rz)
            projX[i] = cx + rx * rScale * persp
            projY[i] = cy + ry * rScale * persp
            projZ[i] = rz // -1 (far) .. +1 (near)
        }

        // Draw delicate synaptic filaments connecting neighboring nodes
        linePaint.strokeWidth = 0.85f * density
        for (e in edgeA.indices) {
            val a = edgeA[e]
            val b = edgeB[e]
            val avgDepth = ((projZ[a] + projZ[b]) * 0.5f + 1f) * 0.5f // 0..1
            val edgeAlpha = ((20f + avgDepth * 95f + smoothRms * 45f) * alpha).toInt().coerceIn(0, 175)
            linePaint.color = withAlpha(if (avgDepth > 0.62f) primary else secondary, edgeAlpha)
            canvas.drawLine(projX[a], projY[a], projX[b], projY[b], linePaint)
        }

        // Draw luminous particles (depth-scaled and depth-faded for genuine 3D elegance)
        for (i in 0 until nodeCount) {
            val depth = (projZ[i] + 1f) * 0.5f // 0 (back) .. 1 (front)
            val nodeR = (1.15f + depth * 2.1f + smoothRms * 0.7f) * density
            val nodeAlpha = ((42f + depth * 208f) * alpha).toInt().coerceIn(0, 255)
            val color = if (depth > 0.76f) highlight else if (depth > 0.38f) primary else secondary
            fillPaint.color = withAlpha(color, nodeAlpha)
            canvas.drawCircle(projX[i], projY[i], nodeR, fillPaint)
        }

        // Subtle luminous inner heart dot
        val heartR = (4.5f + smoothRms * 3.5f) * density
        fillPaint.shader = RadialGradient(
            cx, cy, heartR * 2.4f,
            intArrayOf(
                withAlpha(Color.WHITE, (210 * alpha).toInt()),
                withAlpha(primary, (120 * alpha).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, heartR * 2.4f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawFloatingTypography(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        sphereR: Float,
        primary: Int,
        alpha: Float
    ) {
        // Quiet sentence-case state whisper beneath the sphere
        statusLabelPaint.color = withAlpha(
            if (state == JarvisState.AWAITING_AUTH) 0xFFF59E0B.toInt() else primary,
            (195 * alpha).toInt()
        )
        val statusY = (cy + sphereR * 1.24f + 16f * density).coerceAtMost(h - 24f * density)
        canvas.drawText(statusTag, cx, statusY, statusLabelPaint)

        // Minimalist floating spoken captions above the sphere
        val textW = (w * 0.80f).toInt().coerceAtLeast(120)
        val anchorY = cy - sphereR * 1.32f - 18f * density

        var nextBottomY = anchorY
        if (secondaryCaption.isNotBlank()) {
            hintPaint.color = Color.argb((185 * alpha).toInt(), 175, 182, 194)
            val subLayout = StaticLayout.Builder
                .obtain(secondaryCaption, 0, secondaryCaption.length, hintPaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .build()
            val y = nextBottomY - subLayout.height
            canvas.save()
            canvas.translate((w - textW) * 0.5f, y)
            subLayout.draw(canvas)
            canvas.restore()
            nextBottomY = y - 10f * density
        }

        if (primaryCaption.isNotBlank()) {
            captionPaint.color = Color.argb((242 * alpha).toInt(), 245, 246, 248)
            val mainLayout = StaticLayout.Builder
                .obtain(primaryCaption, 0, primaryCaption.length, captionPaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(4)
                .build()
            val y = (nextBottomY - mainLayout.height).coerceAtLeast(h * 0.24f)
            canvas.save()
            canvas.translate((w - textW) * 0.5f, y)
            mainLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawTopPilotPill(canvas: Canvas, w: Float, primary: Int, highlight: Int, alpha: Float) {
        val label = secondaryCaption.ifBlank { statusTag }.ifBlank { "Jarvis piloting" }.take(34)
        val textWidth = pillTextPaint.measureText(label)
        val pillH = 36f * density
        val pillW = (textWidth + 52f * density).coerceIn(136f * density, w * 0.82f)
        val left = (w - pillW) * 0.5f
        val top = 46f * density
        pillRect.set(left, top, left + pillW, top + pillH)

        // Obsidian capsule
        fillPaint.color = Color.argb((230 * alpha).toInt(), 14, 15, 18)
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, fillPaint)

        // Hairline rim
        pillBorderPaint.color = withAlpha(primary, (110 * alpha).toInt())
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, pillBorderPaint)

        // Breathing living dot
        val dotPulse = 0.7f + 0.3f * sin(elapsedSec * 5f)
        val dotCx = left + 20f * density
        val dotCy = top + pillH * 0.5f
        fillPaint.color = withAlpha(primary, (90 * dotPulse * alpha).toInt())
        canvas.drawCircle(dotCx, dotCy, 7.5f * density * dotPulse, fillPaint)
        fillPaint.color = withAlpha(highlight, (240 * alpha).toInt())
        canvas.drawCircle(dotCx, dotCy, 3.6f * density, fillPaint)

        // Clean label
        pillTextPaint.color = Color.argb((238 * alpha).toInt(), 244, 245, 247)
        val baseline = dotCy - (pillTextPaint.descent() + pillTextPaint.ascent()) * 0.5f
        canvas.drawText(label, left + 34f * density, baseline, pillTextPaint)
    }

    private fun activePalette(): Triple<Int, Int, Int> = when (state) {
        JarvisState.AWAITING_AUTH -> Triple(0xFFF59E0B.toInt(), 0xFFD97706.toInt(), 0xFFFEF3C7.toInt())
        JarvisState.ERROR -> Triple(0xFFEF4444.toInt(), 0xFFB91C1C.toInt(), 0xFFFEE2E2.toInt())
        else -> Triple(theme.primaryArgb, theme.secondaryArgb, theme.accentArgb)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun easeOutQuint(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv * inv * inv
    }

    private fun easeInCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x
    }
}
