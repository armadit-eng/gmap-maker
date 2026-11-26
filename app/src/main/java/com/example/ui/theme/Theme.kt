package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val GmapDarkColorScheme = darkColorScheme(
    primary = Emerald600,
    onPrimary = Slate200,
    primaryContainer = Emerald600.copy(alpha = 0.2f),
    onPrimaryContainer = Emerald100,
    secondary = Blue600,
    onSecondary = Slate200,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate200,
    background = Slate900,
    onBackground = Slate200,
    surface = Slate900,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Slate800,
    surfaceContainerLowest = Slate950,
    outline = Slate700,
    outlineVariant = Slate700.copy(alpha = 0.6f),
    error = Red600,
    onError = Slate200
)

private val GmapShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp)
)

@Composable
fun GmapMakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GmapDarkColorScheme,
        typography = GmapTypography,
        shapes = GmapShapes,
        content = content
    )
}
