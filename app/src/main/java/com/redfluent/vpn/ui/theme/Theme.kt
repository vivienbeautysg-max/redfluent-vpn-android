package com.redfluent.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 与 iOS 版 App/Theme.swift 逐值对齐，两端视觉保持一致。 */
object Brand {
    val Primary = Color(0xFFD42E2E)
    val Secondary = Color(0xFF9E1A1A)
    val Tertiary = Color(0xFFF26666)
    val Success = Color(0xFF33A64D)
    val Warning = Color(0xFFF28C1A)
    val Danger = Color(0xFFD92626)
}

object Spacing {
    val xs = 4.dp; val sm = 8.dp; val md = 16.dp
    val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp
}

object Radius {
    val sm = 8.dp; val md = 14.dp; val lg = 20.dp; val xl = 28.dp
}

private val DarkColors = darkColorScheme(
    primary = Brand.Primary,
    secondary = Brand.Secondary,
    tertiary = Brand.Tertiary,
    error = Brand.Danger,
)

private val LightColors = lightColorScheme(
    primary = Brand.Primary,
    secondary = Brand.Secondary,
    tertiary = Brand.Tertiary,
    error = Brand.Danger,
)

@Composable
fun RedFluentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
