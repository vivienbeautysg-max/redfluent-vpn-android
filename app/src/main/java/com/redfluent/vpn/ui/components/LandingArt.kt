package com.redfluent.vpn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.redfluent.vpn.ui.theme.Rf

/**
 * 开关：一条线，和落在线上的红点（iOS LandingArt，按设计稿 390 x 236 的网格画，再按 height 缩放）。
 * landed（已连接 / 正在连）：红点落进凹里；否则悬在一条直线上方，下面一团淡影。glowing：红点周围的光晕。
 */
@Composable
fun LandingArt(
    landed: Boolean,
    glowing: Boolean = false,
    pulsing: Boolean = false,
    height: Float = 236f,
    showsPower: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ink = Rf.colors.ink
    val red = Rf.colors.red
    val glow = Rf.colors.glow
    val onRed = Rf.colors.onRed

    val reduce = rememberReduceMotion()
    // 落下：带一点回弹（iOS spring(response 0.62, damping 0.62)）；断开时 250ms 平稳抬起。移除动画时直接到位。
    val progress = remember { Animatable(if (landed) 1f else 0f) }
    LaunchedEffect(landed) {
        val to = if (landed) 1f else 0f
        when {
            reduce -> progress.snapTo(to)
            landed -> progress.animateTo(to, spring(dampingRatio = 0.62f, stiffness = 103f))
            else -> progress.animateTo(to, tween(Motion.RELEASE_MS, easing = FastOutSlowInEasing))
        }
    }
    // 连上：光晕 400ms 晕开；断开：250ms 收回
    val glowAlpha = animateFloatAsState(
        if (glowing) 1f else 0f,
        tween(if (glowing) Motion.GLOW_IN_MS else Motion.RELEASE_MS, easing = FastOutSlowInEasing),
        label = "glow",
    )
    // 正在连：1.2s 一次的平缓呼吸（ease-in-out 来回），光晕和外圈一起起伏。移除动画时不呼吸，只静静亮着。
    val breathing = pulsing && reduce.not()
    val breath = if (breathing) {
        rememberInfiniteTransition(label = "breath")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    } else {
        null
    }
    val showsRing = pulsing
    val power = rememberVectorPainter(Icons.Filled.PowerSettingsNew)

    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val k = size.height / 236f
        val cx = size.width / 2f
        val p = progress.value
        val dotY = (62f + (116f - 62f) * p) * k
        val dotR = 54f * k

        // 动画值都在这里（draw 阶段）读：呼吸、光晕变化时只重画这块画布，不重组页面
        val b = breath?.value ?: 0f
        val ga = maxOf(glowAlpha.value, if (breath != null) 0.25f + 0.45f * b else 0f)
        if (ga > 0f) {
            drawCircle(
                brush = Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center = Offset(cx, 116f * k), radius = 112f * k),
                radius = 112f * k,
                center = Offset(cx, 116f * k),
                alpha = ga.coerceIn(0f, 1f),
            )
        }

        // 悬空时的淡影（红点越落越淡）
        val shadowAlpha = 0.18f * (1f - p).coerceIn(0f, 1f)
        if (shadowAlpha > 0.005f) {
            scale(scaleX = 1f, scaleY = 0.16f, pivot = Offset(cx, 136f * k)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(ink.copy(alpha = shadowAlpha), ink.copy(alpha = 0f)),
                        center = Offset(cx, 136f * k),
                        radius = 52f * k,
                    ),
                    radius = 52f * k,
                    center = Offset(cx, 136f * k),
                )
            }
        }

        // 带凹的线
        val y = 141.7f * k
        val d = 34.7f * k * p
        val line = Path().apply {
            moveTo(-10f, y)
            lineTo(cx - 77.1f * k, y)
            cubicTo(cx - 48.9f * k, y, cx - 43.7f * k, y + d, cx, y + d)
            cubicTo(cx + 43.7f * k, y + d, cx + 48.9f * k, y, cx + 77.1f * k, y)
            lineTo(size.width + 10f, y)
        }
        drawPath(line, ink, style = Stroke(width = 8f * k, cap = StrokeCap.Round))

        if (showsRing) {
            drawCircle(
                color = red.copy(alpha = 0.18f + 0.32f * (1f - b)),
                radius = dotR * (1.08f + 0.16f * b),
                center = Offset(cx, dotY),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        drawCircle(red, radius = dotR, center = Offset(cx, dotY))

        if (showsPower) {
            val s = 40f * k
            translate(left = cx - s / 2f, top = dotY - s / 2f) {
                with(power) { draw(Size(s, s), colorFilter = ColorFilter.tint(onRed)) }
            }
        }
    }
}
