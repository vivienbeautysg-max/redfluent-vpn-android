package com.redfluent.vpn.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SignalWifiBad
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.redfluent.vpn.data.AccessNotice
import com.redfluent.vpn.data.Appearance
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.DeviceQuota
import com.redfluent.vpn.data.HomeFormat
import com.redfluent.vpn.data.IPv6Hint
import com.redfluent.vpn.data.LiveStats
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.data.locksConnect
import com.redfluent.vpn.data.normalizeRegionId
import com.redfluent.vpn.data.regionShown
import com.redfluent.vpn.data.text
import com.redfluent.vpn.ui.components.DotStyle
import com.redfluent.vpn.ui.components.DotTitle
import com.redfluent.vpn.ui.components.Haptic
import com.redfluent.vpn.ui.components.LandingArt
import com.redfluent.vpn.ui.components.MoreGlyph
import com.redfluent.vpn.ui.components.Motion
import com.redfluent.vpn.ui.components.OutlineButton
import com.redfluent.vpn.ui.components.RegionFlag
import com.redfluent.vpn.ui.components.RollingText
import com.redfluent.vpn.ui.components.Rule
import com.redfluent.vpn.ui.components.SegmentedChoice
import com.redfluent.vpn.ui.components.UsageLine
import com.redfluent.vpn.ui.components.Wordmark
import com.redfluent.vpn.ui.components.enterRise
import com.redfluent.vpn.ui.components.pressScale
import com.redfluent.vpn.ui.components.rememberReduceMotion
import com.redfluent.vpn.ui.components.rowClick
import com.redfluent.vpn.ui.components.softClick
import com.redfluent.vpn.ui.theme.Gutter
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfFonts
import com.redfluent.vpn.ui.theme.RfType
import com.redfluent.vpn.ui.theme.fixedSp
import kotlinx.coroutines.delay

/** 主页上要显示的一切（来自 ViewModel；截图模式里来自演示数据）。 */
data class HomeUi(
    val profile: DeviceProfile?,
    val state: TunnelState,
    val error: String?,
    val inviteCode: String?,
    val selectedRegion: String,
    val regionInUse: String,
    val activeRegion: String?,
    val accessNotice: AccessNotice?,
    val ipv6Hint: Boolean,
    val quota: DeviceQuota?,
    val stats: LiveStats,
    /** 进入 CONNECTED 的时刻（SystemClock.elapsedRealtime）。 */
    val connectedSince: Long?,
    val appearance: Appearance,
)

class HomeActions(
    val onToggle: () -> Unit,
    val onSelectRegion: (String) -> Unit,
    val onOpenAdmin: () -> Unit,
    val onAppearance: (Appearance) -> Unit,
    val onRefreshQuota: () -> Unit,
    val onSignOut: () -> Unit,
)

fun statusTitle(state: TunnelState): String = when (state) {
    TunnelState.CONNECTED -> "Connected"
    TunnelState.CONNECTING -> "Connecting"
    TunnelState.STOPPING -> "Disconnecting"
    TunnelState.ERROR -> "Needs attention"
    TunnelState.DISCONNECTED -> "Disconnected"
}

/** 中央开关的读屏名：已连 → 断开；正在连 → 取消；否则 → 连接。 */
fun heroLabel(state: TunnelState): String = when (state) {
    TunnelState.CONNECTED -> "Disconnect VPN"
    TunnelState.CONNECTING -> "Cancel connection"
    else -> "Connect VPN"
}

@Composable
fun DashboardScreen(ui: HomeUi, actions: HomeActions) {
    val state = ui.state
    val connected = state == TunnelState.CONNECTED
    val view = LocalView.current
    // 状态变化的触感：连上 CONFIRM，失败 REJECT（只在变化时，第一次显示不响）
    var lastState by remember { mutableStateOf<TunnelState?>(null) }
    LaunchedEffect(state) {
        val prev = lastState
        lastState = state
        if (prev != null && prev != state) {
            if (state == TunnelState.CONNECTED) Haptic.CONFIRM.perform(view)
            if (state == TunnelState.ERROR) Haptic.REJECT.perform(view)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Rf.colors.paper)) {
        val compact = maxHeight < 700.dp
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = Gutter)) {
                NavRow(ui, actions)
                StatusTitle(state, compact, Modifier.padding(top = if (compact) 6.dp else 18.dp).enterRise(0))
                MetaRow(ui, actions.onSelectRegion, Modifier.padding(top = 8.dp).enterRise(1))
                Notices(ui)
            }
            Hero(ui, actions, compact, Modifier.padding(top = if (compact) 4.dp else 18.dp).enterRise(2))
            Column(Modifier.padding(horizontal = Gutter)) {
                StatsRow(if (connected) ui.stats else LiveStats(), Modifier.enterRise(3))
                Bandwidth(ui.quota, actions.onRefreshQuota, Modifier.padding(top = if (compact) 18.dp else 24.dp).enterRise(4))
                Column(Modifier.enterRise(5)) {
                    ProfileRow(ui.profile, Modifier.padding(top = if (compact) 20.dp else 26.dp))
                    if (ui.inviteCode.isNullOrBlank().not()) InviteCodeBlock(ui.inviteCode.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun NavRow(ui: HomeUi, actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        Box {
            MoreGlyph { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Rf.colors.paper) {
                if (ui.profile?.isOwner == true) {
                    MenuItem("Owner Admin") { menu = false; actions.onOpenAdmin() }
                    HorizontalDivider(color = Rf.colors.rule)
                }
                Row(
                    Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Appearance", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink)
                    Spacer(Modifier.width(14.dp))
                    SegmentedChoice(
                        options = Appearance.entries.map { it to it.title },
                        selected = ui.appearance,
                        onSelect = actions.onAppearance,
                    )
                }
                HorizontalDivider(color = Rf.colors.rule)
                MenuItem("Privacy") { menu = false; runCatching { uri.openUri(PRIVACY_URL) } }
                MenuItem("Terms") { menu = false; runCatching { uri.openUri(TERMS_URL) } }
                MenuItem("Support") { menu = false; runCatching { uri.openUri(SUPPORT_URL) } }
                HorizontalDivider(color = Rf.colors.rule)
                MenuItem("Sign out", danger = true) { menu = false; confirmSignOut = true }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = Rf.colors.paper,
            title = { Text("Sign out of this device?", style = RfType.text(18f, FontWeight.SemiBold), color = Rf.colors.ink) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; actions.onSignOut() }) {
                    Text("Sign out", style = RfType.text(15f, FontWeight.SemiBold), color = Rf.colors.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text("Cancel", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink)
                }
            },
        )
    }
}

@Composable
private fun MenuItem(title: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, style = RfType.text(15f, FontWeight.Medium), color = if (danger) Rf.colors.red else Rf.colors.ink) },
        onClick = onClick,
    )
}

/** "Connected." 大标题：状态变了就淡出旧的、新的微微上浮淡入（点的颜色跟着过渡）。 */
@Composable
private fun StatusTitle(state: TunnelState, compact: Boolean, modifier: Modifier) {
    val reduce = rememberReduceMotion()
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(Motion.EXIT_MS)) togetherWith fadeOut(tween(Motion.EXIT_MS))
            } else {
                (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 6 }) togetherWith
                    fadeOut(tween(Motion.EXIT_MS))
            }
        },
        label = "status",
        modifier = modifier.semantics { contentDescription = statusTitle(state) },
    ) { s ->
        DotTitle(
            statusTitle(s),
            size = if (compact) 44f else 50f,
            dot = if (s == TunnelState.CONNECTED || s == TunnelState.CONNECTING) DotStyle.FILLED else DotStyle.HOLLOW,
        )
    }
}

/** 旗 · 区域（owner 有多个区域时可选）· 连上多久。 */
@Composable
private fun MetaRow(ui: HomeUi, onSelectRegion: (String) -> Unit, modifier: Modifier) {
    val profile = ui.profile
    val regions = profile?.regions.orEmpty()
    val multi = regions.size > 1
    val connected = ui.state == TunnelState.CONNECTED
    val name = regionShown(profile, multi, connected, ui.selectedRegion, ui.activeRegion)
    val style = RfType.text(16f, FontWeight.Medium)
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RegionFlag(ui.regionInUse)
        if (multi) {
            var open by remember { mutableStateOf(false) }
            val busy = ui.state == TunnelState.CONNECTING || ui.state == TunnelState.STOPPING
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .softClick(enabled = busy.not()) { open = true }
                        .semantics { contentDescription = "Region: $name" },
                ) {
                    Text(name, style = style, color = Rf.colors.ink)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Rf.colors.ink, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Rf.colors.paper) {
                    regions.forEach { r ->
                        val on = normalizeRegionId(r.id) == normalizeRegionId(ui.selectedRegion)
                        DropdownMenuItem(
                            text = {
                                Text(r.label, style = RfType.text(15f, if (on) FontWeight.SemiBold else FontWeight.Medium), color = Rf.colors.ink)
                            },
                            onClick = {
                                open = false
                                onSelectRegion(r.id)
                            },
                        )
                    }
                }
            }
        } else {
            Text(name, style = style, color = Rf.colors.ink)
        }
        Box(Modifier.size(4.dp).background(Rf.colors.stone, CircleShape))
        ConnectedClock(if (connected) ui.connectedSince else null)
    }
}

/** 每秒跳一下的连接时长；只有这一小块每秒重组。 */
@Composable
private fun ConnectedClock(since: Long?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L - ((now - since) % 1_000L))
        }
    }
    val text = if (since == null) HomeFormat.NONE else HomeFormat.clock((now - since).coerceAtLeast(0) / 1_000L)
    RollingText(text, style = RfType.text(16f), color = Rf.colors.stone)
}

/** 到期 / 快到期 / 吊销、Wi-Fi 没有 IPv6、连接失败：各一行，出现时滑入淡入。 */
@Composable
private fun Notices(ui: HomeUi) {
    val notice = ui.accessNotice
    val failed = if (ui.state == TunnelState.ERROR) ui.error?.takeIf { it.isNotBlank() } else null
    Column(Modifier.padding(top = 6.dp)) {
        NoticeLine(notice != null, noticeIcon(notice), notice?.text())
        NoticeLine(ui.ipv6Hint, Icons.Filled.SignalWifiBad, IPv6Hint.TEXT)
        NoticeLine(failed != null, Icons.Outlined.ErrorOutline, failed)
    }
}

private fun noticeIcon(n: AccessNotice?): ImageVector = when (n) {
    is AccessNotice.Expired -> Icons.Filled.EventBusy
    AccessNotice.Revoked -> Icons.Filled.Block
    else -> Icons.Filled.Event
}

@Composable
private fun NoticeLine(visible: Boolean, icon: ImageVector, text: String?) {
    var last by remember { mutableStateOf(text) }
    if (text != null) last = text
    AnimatedVisibility(
        visible = visible && last != null,
        enter = fadeIn(tween(220)) + expandVertically(tween(Motion.PUSH_MS)) + slideInVertically(tween(Motion.PUSH_MS)) { -it / 3 },
        exit = fadeOut(tween(Motion.EXIT_MS)) + shrinkVertically(tween(Motion.EXIT_MS)),
        label = "notice",
    ) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Rf.colors.red, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(last.orEmpty(), style = RfType.text(13.5f, FontWeight.Medium), color = Rf.colors.red, maxLines = 2)
        }
    }
}

/** 中央开关：按下缩到 0.94 + 轻触感；到期 / 吊销时禁用（已连着的仍可断开）。 */
@Composable
private fun Hero(ui: HomeUi, actions: HomeActions, compact: Boolean, modifier: Modifier) {
    val state = ui.state
    val view = LocalView.current
    val locked = ui.accessNotice.locksConnect()
    val enabled = state != TunnelState.STOPPING && (locked.not() || state == TunnelState.CONNECTED || state == TunnelState.CONNECTING)
    val interaction = remember { MutableInteractionSource() }
    val label = heroLabel(state)
    Box(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, Motion.POWER_PRESS_SCALE)
            .clickable(interaction, indication = null, enabled = enabled) {
                Haptic.TAP.perform(view)
                actions.onToggle()
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        LandingArt(
            landed = state == TunnelState.CONNECTED || state == TunnelState.CONNECTING,
            glowing = state == TunnelState.CONNECTED,
            pulsing = state == TunnelState.CONNECTING || state == TunnelState.STOPPING,
            height = if (compact) 184f else 236f,
            modifier = Modifier.graphicsLayer { alpha = if (enabled || state == TunnelState.STOPPING) 1f else 0.45f },
        )
    }
}

/** Down / Up / Ping：大号 Fraunces 等宽数字，变了的位滚动更新。 */
@Composable
private fun StatsRow(stats: LiveStats, modifier: Modifier) {
    Column(modifier) {
        Rule()
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatCell(HomeFormat.megabytesPerSecond(stats.downBps), "Down MB/s", first = true, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(60.dp).background(Rf.colors.rule))
            StatCell(HomeFormat.megabytesPerSecond(stats.upBps), "Up MB/s", first = false, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(60.dp).background(Rf.colors.rule))
            StatCell(HomeFormat.ping(stats.pingMs), "Ping ms", first = false, Modifier.weight(1f))
        }
        Rule()
    }
}

@Composable
private fun StatCell(value: String, label: String, first: Boolean, modifier: Modifier) {
    Column(
        modifier
            .padding(start = if (first) 0.dp else 14.dp)
            .clearAndSetSemantics { contentDescription = "$label $value" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RollingText(
            value,
            style = TextStyle(fontFamily = RfFonts.display, fontSize = fixedSp(32f), letterSpacing = (-0.02).em),
            color = if (value == HomeFormat.NONE) Rf.colors.stone else Rf.colors.ink,
        )
        Text(label.uppercase(), style = RfType.label(), color = Rf.colors.stone, maxLines = 1)
    }
}

/** 本设备本周期用量：GET /device/quota。点一下刷新。 */
@Composable
private fun Bandwidth(quota: DeviceQuota?, onRefresh: () -> Unit, modifier: Modifier) {
    val fraction = quota?.let { HomeFormat.fraction(it.usedGB, it.monthlyQuotaGB) } ?: 0.0
    Column(
        modifier
            .fillMaxWidth()
            .rowClick(onClick = onRefresh)
            .semantics { contentDescription = "Bandwidth, refresh" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BANDWIDTH", style = RfType.label(), color = Rf.colors.stone)
            Spacer(Modifier.weight(1f))
            val days = quota?.daysUntilReset
            if (days != null) Text(HomeFormat.daysPhrase(days), style = RfType.text(13f, FontWeight.Medium), color = Rf.colors.ink)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            RollingText(
                HomeFormat.gigabytes(quota?.usedGB),
                style = TextStyle(fontFamily = RfFonts.display, fontSize = fixedSp(60f), letterSpacing = (-0.035).em),
                color = if (quota == null) Rf.colors.stone else Rf.colors.ink,
            )
            if (quota != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (quota.unlimited) "/ Unlimited" else "/ ${HomeFormat.quota(quota.monthlyQuotaGB)} GB",
                    style = RfType.text(17f, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
                    color = Rf.colors.stone,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        UsageLine(fraction, Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            IoText("Out", quota?.let { HomeFormat.bytesAsGB(it.outgoingBytes) })
            IoText("In", quota?.let { HomeFormat.bytesAsGB(it.incomingBytes) })
        }
    }
}

@Composable
private fun IoText(label: String, value: String?) {
    Row {
        Text("$label ", style = RfType.text(13.5f), color = Rf.colors.stone)
        Text(value ?: HomeFormat.NONE, style = RfType.text(13.5f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = Rf.colors.ink)
    }
}

/** 头像点 · 名字 ……… Profile xxxxxxxx · Config v…。 */
@Composable
private fun ProfileRow(profile: DeviceProfile?, modifier: Modifier) {
    Column(modifier) {
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(Rf.colors.soft, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).background(Rf.colors.red, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                profile?.ownerLabel?.ifBlank { null } ?: HomeFormat.NONE,
                style = RfType.text(15f, FontWeight.SemiBold),
                color = Rf.colors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                val id = profile?.profileId?.takeLast(8) ?: HomeFormat.NONE
                val cfg = profile?.configVersion?.ifBlank { null } ?: HomeFormat.NONE
                Text("Profile $id", style = RfType.mono(12f), color = Rf.colors.stone, maxLines = 1)
                Text("Config v$cfg", style = RfType.mono(12f), color = Rf.colors.stone, maxLines = 1)
            }
        }
    }
}

/** 安卓独有：换手机时要再输一次邀请码（没有 Apple 那样的恢复入口），所以把它显示出来，可复制。 */
@Composable
private fun InviteCodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    Column(Modifier.padding(top = 26.dp)) {
        Text("YOUR INVITE CODE", style = RfType.label(), color = Rf.colors.stone)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code, style = RfType.mono(16f, medium = true), color = Rf.colors.ink, modifier = Modifier.weight(1f))
            OutlineButton("Copy") {
                clipboard.setText(AnnotatedString(code))
                Haptic.CONFIRM.perform(view)
            }
        }
        Text(
            "Keep this. You will need it when you switch to a new phone.",
            style = RfType.text(12f),
            color = Rf.colors.stone,
        )
    }
}
