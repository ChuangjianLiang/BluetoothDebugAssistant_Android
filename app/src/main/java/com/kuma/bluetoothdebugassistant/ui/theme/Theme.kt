package com.kuma.bluetoothdebugassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val BluetoothDebugColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = CardBackground,
    background = AppBackground,
    onBackground = TitleColor,
    surface = CardBackground,
    onSurface = TitleColor,
    outline = BorderColor,
)

@Composable
fun BluetoothDebugTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BluetoothDebugColors,
        content = content,
    )
}
