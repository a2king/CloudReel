package com.cloudreel.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object YingShiColors {
    val Bg = Color(0xFF0B0D10)
    val Card = Color(0xFF15191F)
    val Card2 = Color(0xFF1D232B)
    val Text = Color(0xFFF5F7FA)
    val Sub = Color(0xFF9AA4B2)
    val Line = Color(0xFF2B323C)
    val Primary = Color(0xFF5B8CFF)
    val Success = Color(0xFF39C98A)
    val Warning = Color(0xFFF5B84B)
    val Error = Color(0xFFF36C6C)
}

@Composable
fun YingShiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = YingShiColors.Primary,
            onPrimary = Color.White,
            background = YingShiColors.Bg,
            onBackground = YingShiColors.Text,
            surface = YingShiColors.Card,
            onSurface = YingShiColors.Text,
            surfaceVariant = YingShiColors.Card2,
            onSurfaceVariant = YingShiColors.Sub,
            border = YingShiColors.Line,
            error = YingShiColors.Error,
            onError = Color.White,
        ),
        content = content,
    )
}
