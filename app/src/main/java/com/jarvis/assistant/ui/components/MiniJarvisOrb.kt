package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.agent.HudColorTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class MiniSphereGeometry(val count: Int = 52) {
    val bx = FloatArray(count)
    val by = FloatArray(count)
    val bz = FloatArray(count)
    val edgeA: IntArray
    val edgeB: IntArray

    init {
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1.0 - (i.toDouble() / (count - 1)) * 2.0
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = goldenAngle * i
            bx[i] = (cos(theta) * r).toFloat()
            by[i] = y.toFloat()
            bz[i] = (sin(theta) * r).toFloat()
        }
        val aList = ArrayList<Int>(count * 3)
        val bList = ArrayList<Int>(count * 3)
        val maxD2 = 0.62f * 0.62f
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val dx = bx[i] - bx[j]
                val dy = by[i] - by[j]
                val dz = bz[i] - bz[j]
                if (dx * dx + dy * dy + dz * dz <= maxD2) {
                    aList.add(i)
                    bList.add(j)
                }
            }
        }
        edgeA = aList.toIntArray()
        edgeB = bList.toIntArray()
    }
}

@Composable
fun MiniJarvisOrb(
    finish: HudColorTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 116.dp
) {
    val geo = remember { MiniSphereGeometry() }
    val px = remember { FloatArray(geo.count) }
    val py = remember { FloatArray(geo.count) }
    val pz = remember { FloatArray(geo.count) }

    val primary = Color(finish.primaryArgb)
    val secondary = Color(finish.secondaryArgb)
    val highlight = Color(finish.accentArgb)

    val transition = rememberInfiniteTransition(label = "mini_orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing)
        ),
        label = "orb_rot"
    )

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = this.size.width * 0.5f
            val cy = this.size.height * 0.5f
            val r = minOf(cx, cy) * 0.68f
            val center = Offset(cx, cy)

            // Soft luminous radial warmth on pure OLED black
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        highlight.copy(alpha = 0.24f),
                        primary.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = r * 1.45f
                ),
                radius = r * 1.45f,
                center = center
            )

            val rotY = phase
            val rotX = 0.34f + sin(phase * 2f) * 0.14f
            val cosY = cos(rotY)
            val sinY = sin(rotY)
            val cosX = cos(rotX)
            val sinX = sin(rotX)
            val cam = 3.1f

            for (i in 0 until geo.count) {
                val x0 = geo.bx[i]
                val y0 = geo.by[i]
                val z0 = geo.bz[i]
                val rx = x0 * cosY + z0 * sinY
                val rz1 = -x0 * sinY + z0 * cosY
                val ry = y0 * cosX - rz1 * sinX
                val rz = y0 * sinX + rz1 * cosX
                val persp = cam / (cam - rz)
                px[i] = cx + rx * r * persp
                py[i] = cy + ry * r * persp
                pz[i] = rz
            }

            // Synaptic threads
            for (e in geo.edgeA.indices) {
                val a = geo.edgeA[e]
                val b = geo.edgeB[e]
                val depth = ((pz[a] + pz[b]) * 0.5f + 1f) * 0.5f
                drawLine(
                    color = (if (depth > 0.6f) primary else secondary).copy(alpha = 0.08f + depth * 0.30f),
                    start = Offset(px[a], py[a]),
                    end = Offset(px[b], py[b]),
                    strokeWidth = 0.75.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Luminous nodes
            for (i in 0 until geo.count) {
                val depth = (pz[i] + 1f) * 0.5f
                val nodeR = (0.95f + depth * 1.55f).dp.toPx()
                val col = if (depth > 0.78f) highlight else if (depth > 0.38f) primary else secondary
                drawCircle(
                    color = col.copy(alpha = 0.20f + depth * 0.78f),
                    radius = nodeR,
                    center = Offset(px[i], py[i])
                )
            }

            // Subtle inner core point
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f), primary.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = r * 0.28f
                ),
                radius = r * 0.28f,
                center = center
            )
        }
    }
}
