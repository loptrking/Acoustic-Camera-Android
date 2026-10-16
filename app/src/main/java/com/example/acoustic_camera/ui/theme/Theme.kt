package com.example.acoustic_camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AcousticColors = darkColorScheme(
    primary = Color(0xFF74E4CF),
    onPrimary = Color(0xFF07131B),
    background = Color(0xFF07131B),
    onBackground = Color.White,
    surface = Color(0xFF10232D),
    onSurface = Color.White,
    outline = Color(0xFF47636F),
)

@Composable
fun Acoustic_CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AcousticColors, typography = Typography, content = content)
}
