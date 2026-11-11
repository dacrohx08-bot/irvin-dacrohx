package com.irvin.dacrohx.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/** El núcleo animado de Irvin: gira más rápido al pensar y late al escuchar o hablar. */
@Composable
fun Orb(mode: Mode, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val speed = when (mode) {
        Mode.THINKING -> 700
        Mode.LISTENING -> 1800
        Mode.SPEAKING -> 1500
        Mode.IDLE -> 3600
    }
    val a1 by t.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "a1")
    val a2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween((speed * 0.7f).toInt(), easing = LinearEasing)), label = "a2")
    val a3 by t.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "a3")
    val pulse by t.animateFloat(
        0.86f, 1.12f,
        infiniteRepeatable(tween(if (mode == Mode.SPEAKING) 320 else if (mode == Mode.LISTENING) 600 else 1700), RepeatMode.Reverse),
        label = "pulse",
    )
    val lv by animateFloatAsState(level, tween(90), label = "lv")

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        val w = r * 0.045f

        // halo
        drawCircle(
            Brush.radialGradient(listOf(C.Red.copy(alpha = 0.28f), Color.Transparent), c, r * 1.0f),
            r, c,
        )
        // anillo exterior punteado
        rotate(a3, c) {
            drawCircle(
                C.Red.copy(alpha = 0.35f), r * 0.96f, c,
                style = Stroke(w * 0.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.06f, r * 0.05f))),
            )
        }
        // anillo 1
        rotate(a1, c) {
            val d = r * 0.84f
            drawArc(C.Red, 0f, 110f, false, Offset(c.x - d, c.y - d), Size(d * 2, d * 2), style = Stroke(w, cap = StrokeCap.Round))
            drawArc(C.Red, 180f, 110f, false, Offset(c.x - d, c.y - d), Size(d * 2, d * 2), style = Stroke(w, cap = StrokeCap.Round))
        }
        // anillo 2
        rotate(a2, c) {
            val d = r * 0.66f
            drawArc(C.Red2, 30f, 80f, false, Offset(c.x - d, c.y - d), Size(d * 2, d * 2), style = Stroke(w * 0.8f, cap = StrokeCap.Round))
            drawArc(C.Red2.copy(alpha = 0.5f), 200f, 120f, false, Offset(c.x - d, c.y - d), Size(d * 2, d * 2), style = Stroke(w * 0.8f, cap = StrokeCap.Round))
        }
        // núcleo
        val scale = (if (mode == Mode.LISTENING) 0.9f + lv * 0.6f else 1f) * (if (mode == Mode.IDLE || mode == Mode.THINKING) pulse.coerceAtMost(1.02f) else pulse)
        val cr = r * 0.36f * scale
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, Color(0xFFFF8090), C.Red, Color(0xFF5A0010)),
                Offset(c.x - cr * 0.25f, c.y - cr * 0.3f), cr * 1.2f,
            ),
            cr, c,
        )
        drawCircle(C.Red.copy(alpha = 0.25f), cr * 1.35f, c)
    }
}
