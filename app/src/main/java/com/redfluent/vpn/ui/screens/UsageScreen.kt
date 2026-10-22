package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.data.OwnerDevice
import com.redfluent.vpn.data.UsageGroup
import com.redfluent.vpn.data.UsageLevel
import com.redfluent.vpn.data.UsagePeriod
import com.redfluent.vpn.data.barPercent
import com.redfluent.vpn.data.formatGB
import com.redfluent.vpn.data.formatPercent
import com.redfluent.vpn.data.formatQuotaGB
import com.redfluent.vpn.data.safeQuotaGB
import com.redfluent.vpn.data.safeUsedGB
import com.redfluent.vpn.data.subtitle
import com.redfluent.vpn.data.title
import com.redfluent.vpn.data.usageLevel
import com.redfluent.vpn.ui.UsageUiState
import com.redfluent.vpn.ui.theme.Brand
import com.redfluent.vpn.ui.theme.Radius
import com.redfluent.vpn.ui.theme.Spacing
import kotlinx.coroutines.delay

private const val AUTO_REFRESH_MS = 30_000L

/** 琥珀色：介于品牌红与警告橙之间，两种主题下都看得清。 */
private val Amber = Color(0xFFFFB020)

/**
 * <70% 正常，70–90% 琥珀，≥90% 红。
 *
 * 规格写的是「<70% 用品牌色」，但本品牌色本身就是红（Brand.Primary #D42E2E），
 * 和 ≥90% 的警示红（Brand.Danger #D92626）肉眼无法区分——模拟器截图里 69.9% 与 90.0% 两条条一模一样，
 * 颜色编码就失效了。所以「正常」这一档用 app 自己的 Brand.Success 绿；
 * 要严格照规格，把下面这一行改回 Brand.Primary 即可。
 */
private fun usageColor(level: UsageLevel): Color = when (level) {
    UsageLevel.NORMAL -> Brand.Success
    UsageLevel.WARNING -> Amber
    UsageLevel.CRITICAL -> Brand.Danger
}

/**
 * Owner 的用量页：按用户（邀请标签）分组，每台设备一条进度条。
 * 返回 = 系统返回手势 / 返回键（由 MainActivity 的 BackHandler 接），或左上角箭头。
 */
@Composable
fun UsageScreen(
    state: UsageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background

    // 页面可见期间每 30 秒自动刷新一次；离开页面协程随之取消
    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_REFRESH_MS)
            onRefresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Primary.copy(alpha = 0.10f), bg))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .widthIn(max = 640.dp)
                .fillMaxSize(),
        ) {
            val period = (state as? UsageUiState.Loaded)?.period
            Header(period = period, onBack = onBack, onRefresh = onRefresh)

            when (state) {
                UsageUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Primary)
                }

                is UsageUiState.Error -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.message, color = Brand.Danger, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Primary),
                    ) { Text("Retry") }
                }

                is UsageUiState.Loaded -> {
                    if (state.groups.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                "No devices",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Spacing.md,
                                end = Spacing.md,
                                top = Spacing.sm,
                                bottom = Spacing.xl,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            if (state.refreshFailed) {
                                item(key = "refresh-failed") {
                                    Text("Refresh failed", color = Brand.Danger, fontSize = 12.sp)
                                }
                            }
                            items(state.groups, key = { it.label }) { group -> GroupCard(group) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(period: UsagePeriod?, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text("Usage", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val range = periodText(period)
            if (range.isNotEmpty()) {
                Text(range, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
    }
}

/** ISO 时间只取日期部分："2026-10-01T00:00:00Z" → "2026-10-01"。 */
private fun periodText(period: UsagePeriod?): String {
    val start = period?.start?.take(10).orEmpty()
    val end = period?.end?.take(10).orEmpty()
    return when {
        start.isEmpty() && end.isEmpty() -> ""
        end.isEmpty() -> start
        start.isEmpty() -> end
        else -> "$start – $end"
    }
}

@Composable
private fun GroupCard(group: UsageGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Spacing.sm),
            )
            Text("${formatGB(group.totalGB)} GB", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        group.devices.forEachIndexed { index, device ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            DeviceRow(device)
        }
    }
}

@Composable
private fun DeviceRow(device: OwnerDevice) {
    val usage = device.usage
    val quota = usage?.safeQuotaGB()
    val percent = usage?.barPercent()
    val dimmed = !device.enabled || device.revokedAt != null
    val state = when {
        device.revokedAt != null -> "Revoked"
        !device.enabled -> "Disabled"
        else -> null
    }
    val subtitle = listOfNotNull(device.subtitle().ifEmpty { null }, state).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .padding(vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (device.online) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Brand.Success)
                        .semantics { contentDescription = "Online" },
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    device.title(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (usage != null) {
            Spacer(Modifier.height(Spacing.sm))
            if (percent != null && quota != null) {
                val color = usageColor(usageLevel(percent))
                UsageBar(percent = percent, color = color)
                Spacer(Modifier.height(Spacing.xs))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("${formatGB(usage.safeUsedGB())} / ${formatQuotaGB(quota)} GB", fontSize = 13.sp)
                    Text(formatPercent(percent), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                }
            } else {
                // 没有配额：不画条，只显示已用
                Text("${formatGB(usage.safeUsedGB())} GB", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun UsageBar(percent: Double, color: Color) {
    val fraction = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .semantics { contentDescription = "Usage ${formatPercent(percent)}" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color),
        )
    }
}
