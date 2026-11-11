package com.irvin.dacrohx.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF060608)
    val Bg2 = Color(0xFF0C0C11)
    val Panel = Color(0xFF111118)
    val Panel2 = Color(0xFF171720)
    val Line = Color(0xFF26262F)
    val Red = Color(0xFFFF2340)
    val Red2 = Color(0xFFFF5468)
    val RedDim = Color(0x24FF2340)
    val Txt = Color(0xFFF1F1F4)
    val Mut = Color(0xFF8B8B98)
    val Ok = Color(0xFF3EE08F)
    val Warn = Color(0xFFFFB020)
}

val Hud = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    letterSpacing = 3.sp,
    fontSize = 12.sp,
    color = C.Red,
)

@Composable
fun IrvinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Red,
            onPrimary = Color.White,
            secondary = C.Red2,
            background = C.Bg,
            onBackground = C.Txt,
            surface = C.Panel,
            onSurface = C.Txt,
            surfaceVariant = C.Panel2,
            onSurfaceVariant = C.Mut,
            outline = C.Line,
            error = C.Warn,
        ),
        content = content,
    )
}
