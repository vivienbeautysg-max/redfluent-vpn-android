package com.redfluent.vpn.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.R
import com.redfluent.vpn.data.Appearance

/**
 * "01 Soft Landing"：Paper / Ink / Red / Stone，Fraunces + Archivo + IBM Plex Mono。
 * 与 iOS 版 App/Theme.swift（feat/soft-landing）逐值对齐。明暗由 app 内开关决定，不跟随系统。
 */
@Immutable
data class RfColors(
    val paper: Color,
    val ink: Color,
    val stone: Color,
    /** 1 px 分隔线。 */
    val rule: Color,
    /** 很淡的底色（用量条底、头像底）。 */
    val soft: Color,
    val red: Color,
    /** 已连接时红点下的光晕。 */
    val glow: Color,
    val onRed: Color,
    val isDark: Boolean,
)

private const val RED = 0xFFC62A22

val LightRf = RfColors(
    paper = Color(0xFFF6F3EE),
    ink = Color(0xFF141414),
    stone = Color(0xFF8A867E),
    rule = Color(0xFF141414).copy(alpha = 0.11f),
    soft = Color(0xFF141414).copy(alpha = 0.06f),
    red = Color(RED),
    glow = Color(RED).copy(alpha = 0.16f),
    onRed = Color.White,
    isDark = false,
)

val DarkRf = RfColors(
    paper = Color(0xFF141414),
    ink = Color(0xFFF6F3EE),
    stone = Color(0xFF9C988F),
    rule = Color(0xFFF6F3EE).copy(alpha = 0.12f),
    soft = Color(0xFFF6F3EE).copy(alpha = 0.08f),
    red = Color(RED),
    glow = Color(0xFFD6342B).copy(alpha = 0.30f),
    onRed = Color.White,
    isDark = true,
)

/** 内置字体（res/font，全部 SIL OFL 1.1，许可证在 assets/licenses）。与 iOS 用的是同一批静态字体文件。 */
object RfFonts {
    /** Fraunces 展示切（opsz 48）：大标题、大数字。 */
    val display = FontFamily(Font(R.font.fraunces_display, FontWeight.Normal))

    /** Fraunces 小切（opsz 21）：字标、行内数字。 */
    val serif = FontFamily(Font(R.font.fraunces_text, FontWeight.Normal))

    /** Archivo：所有界面文字。 */
    val text = FontFamily(
        Font(R.font.archivo_regular, FontWeight.Normal),
        Font(R.font.archivo_medium, FontWeight.Medium),
        Font(R.font.archivo_semibold, FontWeight.SemiBold),
    )

    /** IBM Plex Mono：邀请码、IP、版本号。 */
    val mono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )
}

/** 左右页边距。 */
val Gutter = 24.dp

val LocalRf = staticCompositionLocalOf { LightRf }

object Rf {
    val colors: RfColors
        @Composable @ReadOnlyComposable get() = LocalRf.current
}

/** 大标题 / 大数字是按版面画的，不随系统字号缩放（同 iOS 的 fixedSize）。 */
@Composable
@ReadOnlyComposable
fun fixedSp(value: Float): TextUnit = with(LocalDensity.current) { value.dp.toSp() }

object RfType {
    fun text(size: Float, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = RfFonts.text, fontWeight = weight, fontSize = size.sp)

    fun mono(size: Float, medium: Boolean = false) =
        TextStyle(fontFamily = RfFonts.mono, fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal, fontSize = size.sp)

    /** 小号大写标签：Archivo 600，字距 .14em，Stone。 */
    fun label(size: Float = 10.5f) =
        TextStyle(fontFamily = RfFonts.text, fontWeight = FontWeight.SemiBold, fontSize = size.sp, letterSpacing = 0.14.em)
}

@Composable
fun RedFluentTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val target = if (appearance == Appearance.DARK) DarkRf else LightRf
    // 换明暗：每个颜色 250ms 渐变过去（不闪白 / 不闪黑）；系统「移除动画」时直接换。
    val spec = tween<Color>(250)
    val rf = RfColors(
        paper = animateColorAsState(target.paper, spec, label = "paper").value,
        ink = animateColorAsState(target.ink, spec, label = "ink").value,
        stone = animateColorAsState(target.stone, spec, label = "stone").value,
        rule = animateColorAsState(target.rule, spec, label = "rule").value,
        soft = animateColorAsState(target.soft, spec, label = "soft").value,
        red = animateColorAsState(target.red, spec, label = "red").value,
        glow = animateColorAsState(target.glow, spec, label = "glow").value,
        onRed = animateColorAsState(target.onRed, spec, label = "onRed").value,
        isDark = target.isDark,
    )
    // Material 组件（对话框、菜单、进度圈）也用同一套颜色和字体。
    val scheme = if (rf.isDark) {
        darkColorScheme(
            primary = rf.red, onPrimary = rf.onRed, background = rf.paper, onBackground = rf.ink,
            surface = rf.paper, onSurface = rf.ink, surfaceVariant = rf.paper, onSurfaceVariant = rf.stone,
            surfaceContainer = Color(0xFF1E1E1E), surfaceContainerHigh = Color(0xFF242424),
            surfaceContainerHighest = Color(0xFF2A2A2A), surfaceContainerLow = rf.paper,
            outline = rf.stone, outlineVariant = rf.rule, error = rf.red, secondary = rf.ink, tertiary = rf.red,
        )
    } else {
        lightColorScheme(
            primary = rf.red, onPrimary = rf.onRed, background = rf.paper, onBackground = rf.ink,
            surface = rf.paper, onSurface = rf.ink, surfaceVariant = rf.paper, onSurfaceVariant = rf.stone,
            surfaceContainer = Color(0xFFFBF9F6), surfaceContainerHigh = Color(0xFFFBF9F6),
            surfaceContainerHighest = Color(0xFFFFFFFF), surfaceContainerLow = rf.paper,
            outline = rf.stone, outlineVariant = rf.rule, error = rf.red, secondary = rf.ink, tertiary = rf.red,
        )
    }
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = RfFonts.display),
        displayMedium = base.displayMedium.copy(fontFamily = RfFonts.display),
        displaySmall = base.displaySmall.copy(fontFamily = RfFonts.display),
        headlineLarge = base.headlineLarge.copy(fontFamily = RfFonts.text),
        headlineMedium = base.headlineMedium.copy(fontFamily = RfFonts.text),
        headlineSmall = base.headlineSmall.copy(fontFamily = RfFonts.text, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = RfFonts.text, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = RfFonts.text, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = RfFonts.text),
        bodyLarge = base.bodyLarge.copy(fontFamily = RfFonts.text),
        bodyMedium = base.bodyMedium.copy(fontFamily = RfFonts.text),
        bodySmall = base.bodySmall.copy(fontFamily = RfFonts.text),
        labelLarge = base.labelLarge.copy(fontFamily = RfFonts.text, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = RfFonts.text),
        labelSmall = base.labelSmall.copy(fontFamily = RfFonts.text),
    )
    CompositionLocalProvider(LocalRf provides rf) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
