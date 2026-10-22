package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.RegionOption
import com.redfluent.vpn.data.normalizeRegionId
import com.redfluent.vpn.data.regionShown
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.ui.components.TrafficOrb
import com.redfluent.vpn.ui.theme.Brand
import com.redfluent.vpn.ui.theme.Radius
import com.redfluent.vpn.ui.theme.Spacing

@Composable
fun DashboardScreen(
    profile: DeviceProfile?,
    state: TunnelState,
    error: String?,
    inviteCode: String?,
    selectedRegion: String,
    activeRegion: String?,
    onToggle: () -> Unit,
    onSignOut: () -> Unit,
    onSelectRegion: (String) -> Unit,
    onOpenUsage: () -> Unit,
) {
    val connected = state == TunnelState.CONNECTED
    val bg = MaterialTheme.colorScheme.background
    val topTint = if (connected) Brand.Success.copy(alpha = 0.18f) else Brand.Primary.copy(alpha = 0.10f)
    val regions = profile?.regions.orEmpty()
    // 只有服务器给了不止一个区域（= owner 设备）才出现区域选择；其他人的界面与 1.0 完全相同
    val multiRegion = regions.size > 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topTint, bg))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 1.0 没处理状态栏高度，顶栏会和系统状态栏叠在一起；Usage 按钮加进来后必须让开
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(
                showUsage = profile?.isOwner == true,
                onOpenUsage = onOpenUsage,
                onSignOut = onSignOut,
            )

            TrafficOrb(
                state = state,
                uplinkBps = 0L,
                downlinkBps = 0L,
                onClick = onToggle,
            )

            Spacer(Modifier.height(Spacing.lg))
            Text(
                statusText(state),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor(state),
            )
            if (error != null && state == TunnelState.ERROR) {
                Spacer(Modifier.height(Spacing.xs))
                Text(error, fontSize = 13.sp, color = Brand.Danger, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(Spacing.lg))

            InfoCard("Server") {
                if (multiRegion) {
                    RegionPicker(regions = regions, selected = selectedRegion, onSelect = onSelectRegion)
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Region", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Text(
                        regionShown(
                            profile = profile,
                            multiRegion = multiRegion,
                            connected = connected,
                            selectedRegion = selectedRegion,
                            activeRegion = activeRegion,
                        ),
                        fontSize = 15.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Config", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Text(profile?.configVersion.orEmpty().ifBlank { "—" }, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(Spacing.md))

            InfoCard("Plan") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Monthly quota", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Text(
                        profile?.monthlyQuotaGB?.let { "$it GB" } ?: "Unlimited",
                        fontSize = 15.sp,
                    )
                }
                if (!profile?.expiresAt.isNullOrBlank()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Expires", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        Text(profile.expiresAt!!.take(10), fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            InfoCard("Account") {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Owner", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Text(profile?.ownerLabel.orEmpty().ifBlank { "—" }, fontSize = 15.sp)
                }
                // 换手机时用得上：安卓没有 Sign in with Apple 那种恢复入口，
                // 所以把邀请码显示出来让用户能自己保存、换机后重输。
                if (!inviteCode.isNullOrBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    InviteCodeRow(inviteCode)
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            Text(
                "We do not sell or share your data.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun TopBar(showUsage: Boolean, onOpenUsage: () -> Unit, onSignOut: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("RedFluent VPN", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Owner 才有的入口
            if (showUsage) {
                TextButton(onClick = onOpenUsage) {
                    Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Usage")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        onClick = {
                            menuOpen = false
                            onSignOut()
                        },
                    )
                }
            }
        }
    }
}

/** Owner 专用的区域切换。只在服务器提供多个区域时出现。 */
@Composable
private fun RegionPicker(regions: List<RegionOption>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        regions.forEachIndexed { index, region ->
            SegmentedButton(
                selected = normalizeRegionId(region.id) == normalizeRegionId(selected),
                onClick = { onSelect(region.id) },
                shape = SegmentedButtonDefaults.itemShape(index, regions.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Brand.Primary.copy(alpha = 0.16f),
                    activeContentColor = Brand.Primary,
                    activeBorderColor = Brand.Primary,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                label = { Text(region.label) },
            )
        }
    }
}

@Composable
private fun InviteCodeRow(code: String) {
    val clipboard = LocalClipboardManager.current
    Column {
        Text(
            "Your invite code",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy invite code")
            }
        }
        Text(
            "Keep this. You will need it when you switch to a new phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun statusText(state: TunnelState): String = when (state) {
    TunnelState.CONNECTED -> "Connected"
    TunnelState.CONNECTING -> "Connecting"
    TunnelState.STOPPING -> "Disconnecting"
    TunnelState.ERROR -> "Connection failed"
    TunnelState.DISCONNECTED -> "Not connected"
}

private fun statusColor(state: TunnelState) = when (state) {
    TunnelState.CONNECTED -> Brand.Success
    TunnelState.CONNECTING, TunnelState.STOPPING -> Brand.Warning
    TunnelState.ERROR -> Brand.Danger
    TunnelState.DISCONNECTED -> Brand.Primary
}
