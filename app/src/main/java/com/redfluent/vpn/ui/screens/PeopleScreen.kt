package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.redfluent.vpn.data.AccessPick
import com.redfluent.vpn.data.ActionFeedback
import com.redfluent.vpn.data.DeletedInvite
import com.redfluent.vpn.data.HomeFormat
import com.redfluent.vpn.data.IPv6Display
import com.redfluent.vpn.data.InviteUpdate
import com.redfluent.vpn.data.OwnerAction
import com.redfluent.vpn.data.Person
import com.redfluent.vpn.data.PersonDevice
import com.redfluent.vpn.data.PersonDraft
import com.redfluent.vpn.data.ProtectedKind
import com.redfluent.vpn.data.REVIEW_INVITE_LABEL
import com.redfluent.vpn.data.RecentlyDeleted
import com.redfluent.vpn.data.RegionOption
import com.redfluent.vpn.data.UsagePeriod
import com.redfluent.vpn.data.buildInviteUpdate
import com.redfluent.vpn.data.edit
import com.redfluent.vpn.data.inviteStatusLine
import com.redfluent.vpn.data.regionChoices
import com.redfluent.vpn.data.sinceText
import com.redfluent.vpn.ui.AdminUiState
import com.redfluent.vpn.ui.RestoreUi
import com.redfluent.vpn.ui.components.BackButton
import com.redfluent.vpn.ui.components.BarButton
import com.redfluent.vpn.ui.components.BarRow
import com.redfluent.vpn.ui.components.DotStyle
import com.redfluent.vpn.ui.components.DotTitle
import com.redfluent.vpn.ui.components.NumberStepper
import com.redfluent.vpn.ui.components.OutlineButton
import com.redfluent.vpn.ui.components.QuotaField
import com.redfluent.vpn.ui.components.RollingText
import com.redfluent.vpn.ui.components.Rule
import com.redfluent.vpn.ui.components.SectionLabel
import com.redfluent.vpn.ui.components.SegmentedChoice
import com.redfluent.vpn.ui.components.SettingRow
import com.redfluent.vpn.ui.components.Tag
import com.redfluent.vpn.ui.components.ThemedField
import com.redfluent.vpn.ui.components.enterRise
import com.redfluent.vpn.ui.components.rowClick
import com.redfluent.vpn.ui.components.softClick
import com.redfluent.vpn.ui.theme.Gutter
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfFonts
import com.redfluent.vpn.ui.theme.RfType
import com.redfluent.vpn.ui.theme.fixedSp
import kotlinx.coroutines.delay

/** Owner Admin 三页要显示的东西。 */
data class AdminUi(
    val state: AdminUiState,
    val people: List<Person>,
    val period: UsagePeriod?,
    val deleted: List<DeletedInvite>,
    val deletedSupported: Boolean,
    val deletedError: String?,
    val feedback: ActionFeedback,
    val restore: RestoreUi,
    val ownerRegions: List<RegionOption>,
    /** 现在的时间（毫秒）；截图模式里固定，保证「2h ago」稳定。 */
    val nowMs: Long,
)

class AdminActions(
    val onClose: () -> Unit,
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenPerson: (String) -> Unit,
    val onOpenDeleted: () -> Unit,
    val onSave: (InviteUpdate) -> Unit,
    val onDelete: (String) -> Unit,
    val onLogoutAll: (String) -> Unit,
    val onLogoutDevice: (code: String, profileId: String) -> Unit,
    val onRename: (profileId: String, name: String) -> Unit,
    val onRestore: (DeletedInvite) -> Unit,
)

const val PEOPLE_REFRESH_MS = 30_000L
const val CONFIRM_ALL_MESSAGE = "Each device will need the invite code to reconnect."

// ---- People -------------------------------------------------------------------------------------

@Composable
fun PeopleScreen(ui: AdminUi, actions: AdminActions) {
    // 开着就每 30 秒刷新一次（在线状态、用量）
    LaunchedEffect(Unit) {
        while (true) {
            delay(PEOPLE_REFRESH_MS)
            actions.onRefresh()
        }
    }
    Page(bar = {
        BarRow(
            title = "Owner Admin",
            leading = { BarButton("Done", bold = true, onClick = actions.onClose) },
            trailing = { IconAction(Icons.Filled.Refresh, "Refresh", actions.onRefresh) },
        )
    }) {
        val people = ui.people
        Row(Modifier.padding(top = 14.dp).enterRise(0), verticalAlignment = Alignment.Bottom) {
            DotTitle("People", size = 46f, dot = DotStyle.FILLED, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                CountLine(people.count { it.online }, "online")
                CountLine(people.size, "total")
            }
        }
        Rule(Modifier.padding(top = 18.dp))
        when (val s = ui.state) {
            AdminUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Rf.colors.stone, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            is AdminUiState.Error -> Text(s.message, style = RfType.text(13f, FontWeight.Medium), color = Rf.colors.red, modifier = Modifier.padding(top = 14.dp))
            is AdminUiState.Loaded -> {
                if (s.refreshFailed) {
                    Text("Couldn't refresh. Showing the last list.", style = RfType.text(12.5f, FontWeight.Medium), color = Rf.colors.red, modifier = Modifier.padding(top = 10.dp))
                }
                if (people.isEmpty()) {
                    Text("No people yet", style = RfType.text(14f), color = Rf.colors.stone, modifier = Modifier.padding(vertical = 18.dp))
                }
                Column(Modifier.enterRise(1)) {
                    people.forEach { p -> PersonRow(p, ui.nowMs) { actions.onOpenPerson(p.code) } }
                }
                if (ui.deletedSupported) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 52.dp)
                            .rowClick(onClick = actions.onOpenDeleted)
                            .enterRise(2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = Rf.colors.stone, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Recently Deleted", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.stone, modifier = Modifier.weight(1f))
                        Text("${ui.deleted.size}", style = RfType.text(14f).copy(fontFeatureSettings = "tnum"), color = Rf.colors.stone)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Rf.colors.stone, modifier = Modifier.size(20.dp))
                    }
                    Rule()
                }
            }
        }
    }
}

@Composable
private fun CountLine(n: Int, word: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Rf.colors.ink)) { append("$n ") }
            withStyle(SpanStyle(color = Rf.colors.stone)) { append(word) }
        },
        style = RfType.text(13f, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
    )
}

/** 一个人：在线点 · 名字 ……… 本周期 GB；区域 · 设备 · 最近使用；Paused / Expired。 */
@Composable
private fun PersonRow(person: Person, nowMs: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().rowClick(onClick = onClick)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
            OnlineDot(person.online, Modifier.padding(top = 6.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            person.label,
                            style = RfType.text(16.5f, FontWeight.SemiBold),
                            color = if (person.paused) Rf.colors.stone else Rf.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (person.paused) {
                            Spacer(Modifier.width(8.dp))
                            Tag("Paused", Rf.colors.stone)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    GbText(person.usedGB)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(person.summary(nowMs), style = RfType.text(12.5f), color = Rf.colors.stone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (person.isOwner.not() && person.hasAccessInfo) {
                        val inv = person.invite
                        if (inv.expired) {
                            Tag("Expired")
                        } else {
                            val left = inv.daysLeft
                            if (left != null && left <= 7) Text(HomeFormat.daysPhrase(left), style = RfType.text(12f, FontWeight.SemiBold), color = Rf.colors.red)
                        }
                    }
                }
            }
        }
        Rule()
    }
}

@Composable
private fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .background(if (online) Rf.colors.red else Color.Transparent, CircleShape)
            .border(1.5.dp, if (online) Color.Transparent else Rf.colors.stone, CircleShape),
    )
}

@Composable
private fun GbText(gb: Double?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(HomeFormat.gigabytes(gb), style = TextStyle(fontFamily = RfFonts.serif, fontSize = fixedSp(19f), fontFeatureSettings = "tnum"), color = Rf.colors.ink)
        Text(" GB", style = RfType.text(11f, FontWeight.Medium), color = Rf.colors.stone, modifier = Modifier.padding(bottom = 3.dp))
    }
}

// ---- 个人页 -------------------------------------------------------------------------------------

private fun isReview(person: Person): Boolean = person.label.trim().equals(REVIEW_INVITE_LABEL, ignoreCase = true)

@Composable
fun PersonScreen(person: Person?, ui: AdminUi, actions: AdminActions, demoQuota: String? = null, demoConfirmDelete: Boolean = false) {
    if (person == null) {
        Page(bar = { BarRow(leading = { BackButton("People", actions.onBack) }) }) {
            Text("Not found", style = RfType.text(15f), color = Rf.colors.stone, modifier = Modifier.padding(top = 24.dp))
        }
        return
    }
    val showsAccess = person.hasAccessInfo && person.isOwner.not()
    val kind = if (person.isOwner) ProtectedKind.OWNER else if (isReview(person)) ProtectedKind.REVIEW else null
    var original by remember(person.code) { mutableStateOf(PersonDraft.from(person.invite)) }
    var draft by remember(person.code) { mutableStateOf(original.let { if (demoQuota != null) it.copy(quotaText = demoQuota) else it }) }
    // 保存成功（同一个人、数据刷新后）：以服务器的新值为基准重新开始
    LaunchedEffect(person.invite) {
        val fresh = PersonDraft.from(person.invite)
        if (draft == original) draft = fresh
        original = fresh
    }
    val busy = ui.feedback.busy
    val saving = busy == OwnerAction.SAVE
    val dirty = draft != original
    val update: InviteUpdate? = draft.edit(showsRegion = showsAccess, showsAccess = showsAccess && isReview(person).not())
        ?.let { buildInviteUpdate(person.invite, it, kind) }
    val focus = LocalFocusManager.current

    var confirmDelete by remember { mutableStateOf(demoConfirmDelete) }
    var confirmAll by remember { mutableStateOf(false) }
    var deviceMenu by remember { mutableStateOf<PersonDevice?>(null) }
    var deviceLogout by remember { mutableStateOf<PersonDevice?>(null) }
    var renaming by remember { mutableStateOf<PersonDevice?>(null) }

    // 顶行固定在上面（不跟着滚）：键盘弹着、滚到下面改额度时，Save 和结果横幅仍然看得见
    Page(bar = {
        BarRow(
            leading = { BackButton("People", actions.onBack) },
            trailing = {
                when {
                    saving -> CircularProgressIndicator(color = Rf.colors.stone, strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                    dirty -> BarButton("Save", bold = true, enabled = update != null && busy == null) {
                        // 先结束输入法的拼写（把正在拼的字定下来），再读字段：看到的就是要保存的
                        focus.clearFocus()
                        update?.let(actions.onSave)
                    }
                }
            },
        )
    }) {
        DotTitle(
            person.label,
            size = 46f,
            dot = if (person.online) DotStyle.FILLED else DotStyle.HOLLOW,
            maxLines = 2,
            modifier = Modifier.padding(top = 14.dp).enterRise(0),
        )
        Row(Modifier.padding(top = 10.dp).enterRise(0), verticalAlignment = Alignment.CenterVertically) {
            Text(person.summary(ui.nowMs), style = RfType.text(14f, FontWeight.Medium), color = Rf.colors.stone, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            if (person.paused) {
                Spacer(Modifier.width(8.dp))
                Tag("Paused", Rf.colors.stone)
            }
        }
        if (showsAccess) {
            val line = inviteStatusLine(person.invite)
            if (line != "No limit") {
                val warn = person.invite.expired || (person.invite.daysLeft ?: Int.MAX_VALUE) <= 7
                Text(line, style = RfType.text(13f, FontWeight.Medium), color = if (warn) Rf.colors.red else Rf.colors.stone, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // This period
        Column(Modifier.padding(top = 24.dp).enterRise(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("This period")
                Spacer(Modifier.weight(1f))
                sinceText(ui.period)?.let { Text(it, style = RfType.text(13f, FontWeight.Medium), color = Rf.colors.ink) }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                RollingText(
                    HomeFormat.gigabytes(person.usedGB),
                    style = TextStyle(fontFamily = RfFonts.display, fontSize = fixedSp(60f), letterSpacing = (-0.035).em),
                    color = if (person.usedGB == null) Rf.colors.stone else Rf.colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                Text("GB", style = RfType.text(17f, FontWeight.Medium), color = Rf.colors.stone, modifier = Modifier.padding(bottom = 12.dp))
            }
        }

        // Devices
        Column(Modifier.enterRise(2)) {
            SectionLabel("Devices · ${person.devices.size}", Modifier.padding(top = 28.dp, bottom = 6.dp))
            Rule()
            if (person.devices.isEmpty()) {
                Text("No devices", style = RfType.text(14f), color = Rf.colors.stone, modifier = Modifier.padding(vertical = 14.dp))
            }
            person.devices.forEach { row -> DeviceRow(row, ui.nowMs) { deviceMenu = row } }
        }

        // Settings
        Column(Modifier.enterRise(3)) {
            SectionLabel("Settings", Modifier.padding(top = 28.dp, bottom = 2.dp))
            Rule()
            SettingRow("Name") {
                ThemedField(draft.label, { draft = draft.copy(label = it.take(60)) }, placeholder = "Name", modifier = Modifier.widthIn(min = 120.dp, max = 220.dp))
            }
            SettingRow("Code") {
                ThemedField(
                    draft.code,
                    { draft = draft.copy(code = it.take(40)) },
                    placeholder = "Code",
                    mono = true,
                    enabled = person.isOwner.not(),
                    modifier = Modifier.widthIn(min = 120.dp, max = 220.dp).alpha(if (person.isOwner) 0.6f else 1f),
                )
            }
            QuotaField(draft.quotaText) { draft = draft.copy(quotaText = it) }
            SettingRow("Device limit") {
                NumberStepper(draft.maxDevices, PersonDraft.DEVICE_LIMIT) { draft = draft.copy(maxDevices = it) }
            }
            if (showsAccess) {
                val regions = regionChoices(ui.ownerRegions, person.invite.region)
                SettingRow("Region") {
                    if (regions.size > 1) {
                        SegmentedChoice(regions.map { it.id to it.label }, draft.region) { draft = draft.copy(region = it) }
                    } else {
                        Text(regions.firstOrNull()?.label ?: "Tokyo", style = RfType.text(15f), color = Rf.colors.stone)
                    }
                }
                if (isReview(person).not()) {
                    AccessRow(draft.access) { draft = draft.copy(access = it) }
                    if (draft.access == AccessPick.Custom) {
                        SettingRow("Days") {
                            ThemedField(
                                draft.customDaysText,
                                { draft = draft.copy(customDaysText = it.filter(Char::isDigit).take(4)) },
                                placeholder = "30",
                                numeric = true,
                                style = TextStyle(fontFamily = RfFonts.serif, fontSize = fixedSp(20f), fontFeatureSettings = "tnum"),
                                modifier = Modifier.widthIn(min = 60.dp, max = 100.dp),
                            )
                        }
                    }
                }
            }
            if (person.isOwner.not()) {
                SettingRow("Status") {
                    SegmentedChoice(listOf(true to "Active", false to "Paused"), draft.enabled) { draft = draft.copy(enabled = it) }
                }
            }
        }

        // Actions
        if (person.isOwner.not()) {
            Column(Modifier.padding(top = 28.dp).enterRise(4)) {
                if (person.devicesInUse > 0) {
                    ActionRow("Log out all devices", Icons.AutoMirrored.Filled.Logout, busy == OwnerAction.LOG_OUT_ALL, busy == null) { confirmAll = true }
                }
                ActionRow("Delete", Icons.Filled.DeleteOutline, busy == OwnerAction.DELETE, busy == null) { confirmDelete = true }
                Rule()
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${person.label}?",
            message = if (ui.deletedSupported) RecentlyDeleted.DELETE_MESSAGE else RecentlyDeleted.DELETE_MESSAGE_OLD_BACKEND,
            confirm = "Delete",
            onConfirm = { confirmDelete = false; actions.onDelete(person.code) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmAll) {
        ConfirmDialog(
            title = com.redfluent.vpn.data.confirmAllTitle(person.devicesInUse, person.label),
            message = CONFIRM_ALL_MESSAGE,
            confirm = "Log out",
            onConfirm = { confirmAll = false; actions.onLogoutAll(person.code) },
            onDismiss = { confirmAll = false },
        )
    }
    deviceMenu?.let { row ->
        DeviceActionsDialog(
            title = deviceName(row),
            canLogOut = row.signedIn && person.isOwner.not(),
            onRename = { deviceMenu = null; renaming = row },
            onLogOut = { deviceMenu = null; deviceLogout = row },
            onDismiss = { deviceMenu = null },
        )
    }
    deviceLogout?.let { row ->
        ConfirmDialog(
            title = com.redfluent.vpn.data.confirmDeviceTitle(deviceName(row)),
            message = com.redfluent.vpn.data.CONFIRM_DEVICE_MESSAGE,
            confirm = "Log out",
            onConfirm = { deviceLogout = null; actions.onLogoutDevice(person.code, row.device.profileId) },
            onDismiss = { deviceLogout = null },
        )
    }
    renaming?.let { row ->
        RenameDialog(
            initial = deviceName(row),
            onSave = { name -> renaming = null; if (name.trim() != deviceName(row)) actions.onRename(row.device.profileId, name) },
            onDismiss = { renaming = null },
        )
    }
}

internal fun deviceName(row: PersonDevice): String =
    row.device.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: row.device.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Device"

@Composable
private fun AccessRow(selected: AccessPick, onPick: (AccessPick) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow("Access") {
        Box {
            Row(
                Modifier.heightIn(min = 48.dp).softClick { open = true }.semantics { contentDescription = "Access: ${selected.label}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.label, style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Rf.colors.ink, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Rf.colors.paper) {
                AccessPick.ALL.forEach { pick ->
                    DropdownMenuItem(
                        text = { Text(pick.label, style = RfType.text(15f, if (pick == selected) FontWeight.SemiBold else FontWeight.Medium), color = Rf.colors.ink) },
                        onClick = { open = false; onPick(pick) },
                    )
                }
            }
        }
    }
}

/** 一台设备：名字 ……… 本周期 GB；版本 · 最近使用（· Signed out）；IPv6 入口（Dedicated）。 */
@Composable
private fun DeviceRow(row: PersonDevice, nowMs: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().rowClick(onClick = onClick)) {
        Row(Modifier.padding(vertical = 14.dp).alpha(if (row.signedIn) 1f else 0.55f), verticalAlignment = Alignment.Top) {
            OnlineDot(row.online, Modifier.padding(top = 6.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceName(row), style = RfType.text(16.5f, FontWeight.SemiBold), color = Rf.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    GbText(row.usedGB)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.detail(nowMs), style = RfType.text(12.5f), color = Rf.colors.stone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row.dedicated) Tag("Dedicated")
                }
                when (val v = row.ipv6) {
                    IPv6Display.IPv4Only -> Text(IPv6Display.IPV4_ONLY_TEXT, style = RfType.mono(11.5f), color = Rf.colors.stone)
                    is IPv6Display.Address -> Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Rf.colors.stone)) { append(v.prefix) }
                            withStyle(SpanStyle(color = Rf.colors.ink)) { append(v.host) }
                        },
                        style = RfType.mono(11.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Rule()
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, working: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column {
        Rule()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .rowClick(enabled = enabled, onClick = onClick)
                .alpha(if (enabled || working) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Rf.colors.red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = RfType.text(15f, FontWeight.SemiBold), color = Rf.colors.red, modifier = Modifier.weight(1f))
            if (working) CircularProgressIndicator(color = Rf.colors.stone, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
    }
}

// ---- Recently Deleted ---------------------------------------------------------------------------

@Composable
fun DeletedScreen(ui: AdminUi, actions: AdminActions) {
    Page(bar = { BarRow(leading = { BackButton("People", actions.onBack) }) }) {
        DotTitle("Recently Deleted", size = 46f, dot = DotStyle.FILLED, maxLines = 2, modifier = Modifier.padding(top = 14.dp).enterRise(0))
        ui.deletedError?.let {
            Text(it, style = RfType.text(13f, FontWeight.Medium), color = Rf.colors.red, modifier = Modifier.padding(top = 12.dp))
        }
        ui.restore.restored.forEach { name ->
            Column(Modifier.padding(top = 14.dp)) {
                Text("$name restored", style = RfType.text(14f, FontWeight.SemiBold), color = Rf.colors.ink)
                Text(RecentlyDeleted.RESTORED_NOTE, style = RfType.text(13f, FontWeight.Medium), color = Rf.colors.red)
            }
        }
        Rule(Modifier.padding(top = 20.dp))
        if (ui.deleted.isEmpty()) {
            Text("Nothing here", style = RfType.text(14f), color = Rf.colors.stone, modifier = Modifier.padding(vertical = 16.dp))
        }
        Column(Modifier.enterRise(1)) {
            ui.deleted.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, style = RfType.text(16.5f, FontWeight.SemiBold), color = Rf.colors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                        if (ui.restore.working.contains(item.key)) {
                            CircularProgressIndicator(color = Rf.colors.stone, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(20.dp))
                        } else {
                            OutlineButton("Restore") { if (ui.feedback.busy == null) actions.onRestore(item) }
                        }
                    }
                    Text(RecentlyDeleted.detail(item, ui.nowMs), style = RfType.text(12.5f), color = Rf.colors.stone)
                    ui.restore.errors[item.key]?.let {
                        Text(it, style = RfType.text(12.5f, FontWeight.Medium), color = Rf.colors.red, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Rule()
            }
        }
    }
}

// ---- 共用 ---------------------------------------------------------------------------------------

/**
 * 一页：Paper 底、留出系统栏、左右 24dp。顶行（bar）固定不滚，下面的内容可滚动。
 * 内边距用 safeDrawing（系统栏 + 挖孔 + 键盘）：键盘弹起时页面跟着让开，顶行的 Save / 返回始终在键盘上方看得见。
 */
@Composable
private fun Page(bar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Rf.colors.paper)
            .safeDrawingPadding()
    ) {
        Box(Modifier.padding(horizontal = Gutter)) { bar() }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter)
                    .padding(bottom = 40.dp),
            ) { content() }
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).softClick(onClick = onClick).clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Rf.colors.ink, modifier = Modifier.size(22.dp))
    }
}
