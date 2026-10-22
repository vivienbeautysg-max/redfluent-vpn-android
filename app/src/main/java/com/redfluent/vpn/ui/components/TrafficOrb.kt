package com.redfluent.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.ui.theme.Brand
import com.redfluent.vpn.ui.theme.Spacing
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 中央状态球。与 iOS 版 TrafficOrbRing.swift 逐项对齐：
 * 球体 220dp / 环 250dp / 环宽 6dp，四种状态各自的渐变与阴影色，
 * 连接后由两条弧线按上下行速率对数映射转速环绕。
 */
@Composable
fun TrafficOrb(
    state: TunnelState,
    uplinkBps: Long,
    downlinkBps: Long,
    onClick: () -> Unit,
) {
    val busy = state == TunnelState.CONNECTING || state == TunnelState.STOPPING
    val connected = state == TunnelState.CONNECTED

    // 已连接时整体放大 1.05（iOS: spring(duration: 0.6)）
    val scale by animateFloatAsState(
        targetValue = if (connected) 1.05f else 1.0f,
        animationSpec = spring(),
        label = "orbScale",
    )

    val transition = rememberInfiniteTransition(label = "orb")

    // 忙碌时的脉冲环：1.0 → 1.15 同时淡出，1.2s 循环（与 iOS 一致）
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )

    // 环绕弧线的旋转相位
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    Box(
        modifier = Modifier
            .padding(top = Spacing.lg)
            .size(260.dp)
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // 连接中也允许点（= 取消）：取配置可能要等很久（超时 45 秒），球不能死着不动。
                // 只有「正在断开」时才禁用，避免在拆除过程中反复点击。
                enabled = state != TunnelState.STOPPING,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (connected) {
            TrafficRing(uplinkBps = uplinkBps, downlinkBps = downlinkBps, phase = phase)
        }

        // 球体本身
        Canvas(modifier = Modifier.size(220.dp)) {
            drawCircle(brush = orbBrush(state, size))
        }

        if (busy) {
            val p = pulse
            Canvas(modifier = Modifier.size(240.dp)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f * (1f - p)),
                    radius = size.minDimension / 2f * (1f + 0.15f * p),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.Send,
            contentDescription = when (state) {
                TunnelState.CONNECTED -> "Disconnect VPN"
                TunnelState.CONNECTING -> "Cancel connection"
                else -> "Connect VPN"
            },
            tint = Color.White,
            modifier = Modifier
                .size(88.dp)
                .rotate(-30f),
        )
    }
}

@Composable
private fun TrafficRing(uplinkBps: Long, downlinkBps: Long, phase: Float) {
    val upFraction = arcFraction(uplinkBps.toDouble())
    val downFraction = arcFraction(downlinkBps.toDouble())
    val upPeriod = period(uplinkBps.toDouble())
    val downPeriod = period(downlinkBps.toDouble())

    // phase 是 0..1 的 6 秒基准相位；按各自周期折算成角度
    val upAngle = ((phase * 6.0 / upPeriod) % 1.0) * 360.0
    val downAngle = -((phase * 6.0 / downPeriod) % 1.0) * 360.0 - 180.0

    Canvas(modifier = Modifier.size(250.dp)) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        // 底环
        drawCircle(
            color = Color.White.copy(alpha = 0.10f),
            radius = size.minDimension / 2f - inset,
            style = Stroke(width = stroke),
        )
        // 上行：品牌绿
        if (upFraction > 0.0) {
            drawArc(
                color = Brand.Success,
                startAngle = upAngle.toFloat(),
                sweepAngle = (upFraction * 360.0).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        // 下行：青色（与 iOS 的 0.20/0.75/0.85 同值）
        if (downFraction > 0.0) {
            drawArc(
                color = Color(0xFF33BFD9),
                startAngle = downAngle.toFloat(),
                sweepAngle = (downFraction * 360.0).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 对数映射：0–10 MB/s → 0–270 度弧长（iOS 同一公式）。 */
private fun arcFraction(bps: Double): Double {
    if (bps <= 1.0) return 0.0
    val maxBps = 10_000_000.0
    val scaled = log10(min(bps, maxBps)) / log10(maxBps)
    return min(scaled * 0.75, 0.75)
}

/** 转一圈的秒数：空闲 6 秒，10 MB/s 时约 0.5 秒（iOS 同一公式）。 */
private fun period(bps: Double): Double {
    if (bps <= 1.0) return 6.0
    val maxBps = 10_000_000.0
    val scaled = log10(min(bps, maxBps)) / log10(maxBps)
    return max(0.5, 6.0 - scaled * 5.5)
}

private fun orbBrush(state: TunnelState, size: Size): Brush {
    val (a, b) = when (state) {
        TunnelState.CONNECTED -> Brand.Success to Brand.Success.copy(alpha = 0.7f)
        TunnelState.CONNECTING, TunnelState.STOPPING -> Brand.Warning to Brand.Warning.copy(alpha = 0.7f)
        TunnelState.ERROR -> Brand.Danger to Brand.Danger.copy(alpha = 0.7f)
        TunnelState.DISCONNECTED -> Brand.Primary to Brand.Secondary
    }
    return Brush.linearGradient(
        colors = listOf(a, b),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )
}
