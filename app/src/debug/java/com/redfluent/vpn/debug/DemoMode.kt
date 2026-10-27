package com.redfluent.vpn.debug

import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.redfluent.vpn.MainActivity
import com.redfluent.vpn.data.AccessNotice
import com.redfluent.vpn.data.ActionFeedback
import com.redfluent.vpn.data.Appearance
import com.redfluent.vpn.data.DeletedInvite
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.DeviceQuota
import com.redfluent.vpn.data.DeviceUsage
import com.redfluent.vpn.data.FeedbackMachine
import com.redfluent.vpn.data.FeedbackText
import com.redfluent.vpn.data.InviteUpdate
import com.redfluent.vpn.data.LiveStats
import com.redfluent.vpn.data.OwnerAction
import com.redfluent.vpn.data.OwnerDevice
import com.redfluent.vpn.data.OwnerInvite
import com.redfluent.vpn.data.RecentlyDeleted
import com.redfluent.vpn.data.RegionOption
import com.redfluent.vpn.data.SIGNED_OUT_NOTICE
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.data.UsagePeriod
import com.redfluent.vpn.data.buildPeople
import com.redfluent.vpn.data.inviteErrorText
import com.redfluent.vpn.data.ownerCodes
import com.redfluent.vpn.ui.AdminData
import com.redfluent.vpn.ui.AdminRoute
import com.redfluent.vpn.ui.AdminUiState
import com.redfluent.vpn.ui.AppFrame
import com.redfluent.vpn.ui.RestoreUi
import com.redfluent.vpn.ui.Screen
import com.redfluent.vpn.ui.screenOf
import com.redfluent.vpn.ui.screens.ActivationScreen
import com.redfluent.vpn.ui.screens.AdminActions
import com.redfluent.vpn.ui.screens.AdminUi
import com.redfluent.vpn.ui.screens.DashboardScreen
import com.redfluent.vpn.ui.screens.DeletedScreen
import com.redfluent.vpn.ui.screens.HomeActions
import com.redfluent.vpn.ui.screens.HomeUi
import com.redfluent.vpn.ui.screens.OnboardingScreen
import com.redfluent.vpn.ui.screens.PeopleScreen
import com.redfluent.vpn.ui.screens.PersonScreen
import com.redfluent.vpn.ui.theme.RedFluentTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 截图 / 动效演示模式（只在 debug 包里；release 源集里是返回 null 的空实现）。
 * 用编的演示数据驱动真实的界面代码：不联网、不激活、不写任何存储，也不会在生产库里留下记录。
 * 所有 IPv6 地址都在 RFC 3849 文档前缀 2001:db8::/32 里，邀请码都是 RF-DEMO-xxxx。
 *
 * adb shell am start -n com.redfluent.vpn/.MainActivity --es rfv_demo owner [其它参数]
 *   rfv_demo           member | owner | member-sg | member-expired | member-soon | member-signedout | activation | onboarding
 *   rfv_disconnected   true：主页未连接形态（默认已连接）
 *   rfv_ipv4wifi       true：当作在一个没有 IPv6 的 Wi-Fi 上（主页出现那一行提示）
 *   rfv_open           people | deleted
 *   rfv_person         直接进入这个人的个人页（Ben / Alice ...）
 *   rfv_quota          个人页里预填月额度输入框（48、48.5 ...）
 *   rfv_confirm_delete true：个人页里直接弹出 Delete 确认框
 *   rfv_appearance     light | dark
 *   rfv_fail           409 / 400：个人页保存、激活按这个状态码失败（演示失败反馈）
 */
object DemoMode {
    fun content(activity: MainActivity, intent: Intent?): (@Composable () -> Unit)? {
        val x = intent?.extras ?: return null
        val variant = x.getString("rfv_demo")?.trim()?.lowercase() ?: return null
        val cfg = DemoConfig(
            variant = variant,
            disconnected = x.getBoolean("rfv_disconnected", false),
            ipv4Wifi = x.getBoolean("rfv_ipv4wifi", false),
            open = x.getString("rfv_open")?.lowercase(),
            person = x.getString("rfv_person"),
            quota = x.getString("rfv_quota"),
            confirmDelete = x.getBoolean("rfv_confirm_delete", false),
            appearance = Appearance.from(x.getString("rfv_appearance")),
            fail = x.getString("rfv_fail")?.toIntOrNull(),
        )
        return { DemoApp(activity, cfg) }
    }
}

private data class DemoConfig(
    val variant: String,
    val disconnected: Boolean,
    val ipv4Wifi: Boolean,
    val open: String?,
    val person: String?,
    val quota: String?,
    val confirmDelete: Boolean,
    val appearance: Appearance,
    val fail: Int?,
)

private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun iso(ms: Long): String = ISO.format(Date(ms))

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val SHARED = "2001:db8:1000:5bd7:b463:af04:5e51:58ef"
private const val OWN_MAX = "2001:db8:1000:5bd7:51e5:d2bf:4175:4529"
private const val OWN_BEN = "2001:db8:1000:5bd7:9c1e:42aa:7d3:be10"
private const val OWN_PROFILE = "profile_demo00000001"
private const val OWN_CODE = "RF-DEMO-0001"

private fun demoInvites(now: Long): List<OwnerInvite> = listOf(
    OwnerInvite(OWN_CODE, "Max", 6, 1000, true, region = "tokyo", activatedAt = "2026-05-09T01:00:00.000Z"),
    OwnerInvite("RF-DEMO-0002", "Alice", 3, 200, true, region = "singapore", durationDays = 30, activatedAt = iso(now - 37 * DAY), effectiveExpiresAt = iso(now - 7 * DAY), expired = true),
    OwnerInvite("RF-DEMO-0003", "Ben", 3, 200, true, region = "tokyo", durationDays = 30, activatedAt = iso(now - DAY), effectiveExpiresAt = iso(now + 29 * DAY), daysLeft = 29),
    OwnerInvite("RF-DEMO-0004", "Clara", 2, 200, false, region = "singapore", durationDays = 60),
    OwnerInvite("RF-DEMO-0006", "Dana", 1, 200, true, region = "tokyo", durationDays = 90, activatedAt = iso(now - 84 * DAY), effectiveExpiresAt = iso(now + 6 * DAY), daysLeft = 6),
    OwnerInvite("RF-DEMO-0005", "Apple Review", 3, 200, true, region = "tokyo"),
)

private fun dev(
    n: Int, invite: Int, label: String, name: String, model: String, app: String, used: Double, lastAgoMs: Long?, now: Long,
    online: Boolean = false, enabled: Boolean = true, ipv6: String? = null, dedicated: Boolean = false,
) = OwnerDevice(
    profileId = "profile_demo%08d".format(n),
    inviteCode = "RF-DEMO-000$invite",
    inviteLabel = label,
    displayName = name,
    deviceName = model,
    appVersion = app,
    enabled = enabled,
    revokedAt = if (enabled) null else "2026-10-12T08:00:00.000Z",
    online = online,
    usage = DeviceUsage(usedGB = used),
    lastUsedAt = lastAgoMs?.let { iso(now - it) },
    ipv6Entry = ipv6,
    ipv6EntryDedicated = if (ipv6 == null) null else dedicated,
)

private fun demoDevices(now: Long): List<OwnerDevice> = listOf(
    dev(1, 1, "Max", "Max Pixel", "Pixel 9 Pro", "1.3 (6)", 312.4, 20_000, now, online = true, ipv6 = OWN_MAX, dedicated = true),
    dev(2, 1, "Max", "Max Desktop", "DESKTOP-DEMO", "0.1.0", 0.0, 26 * HOUR, now, ipv6 = SHARED),
    dev(3, 2, "Alice", "Alice Phone", "Galaxy S24", "1.2.1 (5)", 148.9, 20_000, now, online = true),
    dev(4, 2, "Alice", "Alice Tablet", "Galaxy Tab S9", "1.2.1 (5)", 41.2, 2 * DAY, now),
    dev(5, 3, "Ben", "Ben Phone", "Pixel 8", "1.3 (6)", 142.3, 2 * HOUR, now, ipv6 = OWN_BEN, dedicated = true),
    dev(6, 3, "Ben", "Ben Tablet", "Pixel Tablet", "1.2 (4)", 22.7, 9 * DAY, now, enabled = false, ipv6 = SHARED),
    dev(7, 4, "Clara", "Clara Phone", "Galaxy A55", "1.2.1 (5)", 96.0, 4 * DAY, now),
    dev(8, 6, "Dana", "Dana Phone", "Pixel 7a", "1.2 (4)", 0.0, null, now, ipv6 = SHARED),
    dev(9, 3, "Ben", "Ben Laptop", "MacBook Air", "1.2 (43)", 18.5, 5 * HOUR, now),
)

private fun demoDeleted(now: Long): List<DeletedInvite> = listOf(
    DeletedInvite("RF-DEMO-0007", "Evan", iso(now - 3 * DAY), 2, 100, "tokyo", 1),
    DeletedInvite("RF-DEMO-0008", "Fiona", iso(now - 12 * DAY), 1, 50, "singapore", 0),
)

private val TOKYO = RegionOption("tokyo", "Tokyo", true)
private val SINGAPORE = RegionOption("singapore", "Singapore")

@Composable
private fun DemoApp(activity: MainActivity, cfg: DemoConfig) {
    val now = remember { System.currentTimeMillis() }
    val owner = cfg.variant == "owner"
    val scope = rememberCoroutineScope()
    var appearance by remember { mutableStateOf(cfg.appearance) }
    LaunchedEffect(appearance) { activity.applySystemBars(appearance) }

    val profileValue = DeviceProfile(
        profileId = if (owner) OWN_PROFILE else "profile_demo00000000",
        token = "demo-not-a-token",
        ownerLabel = "Owner",
        serverRegion = if (cfg.variant == "member-sg") "Singapore" else "Tokyo",
        configVersion = "2026-05-09.1",
        monthlyQuotaGB = 200,
        isOwner = owner,
        regions = when (cfg.variant) {
            "owner" -> listOf(TOKYO, SINGAPORE)
            "member-sg" -> listOf(SINGAPORE.copy(isDefault = true))
            else -> listOf(TOKYO)
        },
    )
    var onboarded by remember { mutableStateOf(cfg.variant != "onboarding") }
    var signedIn by remember { mutableStateOf(cfg.variant !in setOf("activation", "member-signedout", "onboarding")) }
    var activating by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(if (cfg.disconnected) TunnelState.DISCONNECTED else TunnelState.CONNECTED) }
    var since by remember { mutableStateOf<Long?>(if (cfg.disconnected) null else SystemClock.elapsedRealtime() - 3_720_000L) }
    var selected by remember { mutableStateOf(if (owner || cfg.variant == "member-sg") "singapore" else "tokyo") }

    var invites by remember { mutableStateOf(demoInvites(now)) }
    var devices by remember { mutableStateOf(demoDevices(now)) }
    var deleted by remember { mutableStateOf(demoDeleted(now)) }
    var feedback by remember { mutableStateOf(ActionFeedback()) }
    var restore by remember { mutableStateOf(RestoreUi()) }
    var adminOpen by remember { mutableStateOf(owner && cfg.open != null || cfg.person != null) }
    var route by remember {
        mutableStateOf<AdminRoute>(
            when {
                cfg.open == "deleted" -> AdminRoute.Deleted
                cfg.person != null -> demoInvites(now).firstOrNull { it.label.equals(cfg.person, true) }?.let { AdminRoute.Person(it.code) } ?: AdminRoute.People
                else -> AdminRoute.People
            },
        )
    }
    val people = buildPeople(invites, devices, ownerCodes(devices, OWN_PROFILE, OWN_CODE), "Owner")

    // 演示用的「服务器」：等一会儿，再按 rfv_fail 成功或失败
    fun run(action: OwnerAction, success: () -> String, failText: (Int) -> String = { inviteErrorText(it, "") }, apply: () -> Unit = {}) {
        val started = FeedbackMachine.start(feedback, action) ?: return
        feedback = started
        scope.launch {
            delay(900)
            val code = cfg.fail
            feedback = if (code != null) {
                FeedbackMachine.fail(feedback, failText(code))
            } else {
                apply()
                FeedbackMachine.succeed(feedback, success())
            }
        }
    }

    fun connectToggle() {
        when (state) {
            TunnelState.CONNECTED -> scope.launch {
                state = TunnelState.STOPPING
                delay(350)
                state = TunnelState.DISCONNECTED
                since = null
            }
            TunnelState.CONNECTING -> state = TunnelState.DISCONNECTED
            else -> scope.launch {
                state = TunnelState.CONNECTING
                delay(1_800)
                if (state == TunnelState.CONNECTING) {
                    state = TunnelState.CONNECTED
                    since = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    val back: () -> Unit = {
        if (route == AdminRoute.People) adminOpen = false else route = AdminRoute.People
    }
    val adminActions = AdminActions(
        onClose = { adminOpen = false },
        onBack = back,
        onRefresh = {},
        onOpenPerson = { route = AdminRoute.Person(it) },
        onOpenDeleted = { route = AdminRoute.Deleted },
        onSave = { u: InviteUpdate ->
            run(OwnerAction.SAVE, { FeedbackText.SAVED }, failText = { inviteErrorText(it, if (it == 409) "invite code already exists" else "") }) {
                invites = invites.map {
                    if (it.code == u.code) {
                        it.copy(code = u.newCode, label = u.label, maxDevices = u.maxDevices, monthlyQuotaGB = u.monthlyQuotaGB, enabled = u.enabled, region = u.region ?: it.region)
                    } else {
                        it
                    }
                }
                devices = devices.map { d -> if (d.inviteCode == u.code) d.copy(inviteCode = u.newCode) else d }
                if (route == AdminRoute.Person(u.code)) route = AdminRoute.Person(u.newCode)
            }
        },
        onDelete = { code ->
            val label = invites.firstOrNull { it.code == code }?.label.orEmpty()
            run(OwnerAction.DELETE, { FeedbackText.deleted(label) }) {
                val inv = invites.first { it.code == code }
                invites = invites.filterNot { it.code == code }
                deleted = RecentlyDeleted.sorted(deleted + DeletedInvite(inv.code, inv.label, iso(System.currentTimeMillis()), inv.maxDevices, inv.monthlyQuotaGB, inv.region, 0))
                route = AdminRoute.People
            }
        },
        onLogoutAll = { code ->
            val n = devices.count { it.inviteCode == code && it.enabled && it.revokedAt == null }
            run(OwnerAction.LOG_OUT_ALL, { FeedbackText.loggedOutAll(n) }) {
                devices = devices.map { if (it.inviteCode == code) it.copy(revokedAt = iso(System.currentTimeMillis()), online = false) else it }
            }
        },
        onLogoutDevice = { _, id ->
            val name = devices.firstOrNull { it.profileId == id }?.displayName.orEmpty()
            run(OwnerAction.LOG_OUT_DEVICE, { FeedbackText.loggedOutDevice(name) }) {
                devices = devices.map { if (it.profileId == id) it.copy(revokedAt = iso(System.currentTimeMillis()), online = false) else it }
            }
        },
        onRename = { id, name ->
            run(OwnerAction.RENAME, { FeedbackText.RENAMED }) {
                devices = devices.map { if (it.profileId == id) it.copy(displayName = name.trim()) else it }
            }
        },
        onRestore = { item ->
            restore = restore.copy(working = restore.working + item.key)
            run(OwnerAction.RESTORE, { FeedbackText.restored(item.label) }, failText = { RecentlyDeleted.restoreFailureText(it, "") }) {
                deleted = deleted.filterNot { it.key == item.key }
                invites = invites + OwnerInvite(item.code, item.label, item.maxDevices, item.monthlyQuotaGB, true, region = item.region)
                restore = restore.copy(working = restore.working - item.key, restored = restore.restored + item.label)
            }
            scope.launch {
                delay(950)
                restore = restore.copy(working = restore.working - item.key)
            }
        },
    )
    val homeActions = HomeActions(
        onToggle = ::connectToggle,
        onSelectRegion = { selected = it },
        onOpenAdmin = { route = AdminRoute.People; adminOpen = true },
        onAppearance = { appearance = it },
        onRefreshQuota = {},
        onSignOut = { signedIn = false },
    )

    RedFluentTheme(appearance) {
        val screen = screenOf(onboarded, signedIn, adminOpen && owner, route)
        val adminUi = AdminUi(
            state = AdminUiState.Loaded(AdminData(invites, devices, UsagePeriod("2026-10-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z"), deleted, true)),
            people = people,
            period = UsagePeriod("2026-10-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z"),
            deleted = deleted,
            deletedSupported = true,
            deletedError = null,
            feedback = feedback,
            restore = restore,
            ownerRegions = profileValue.regions,
            nowMs = now,
        )
        AppFrame(screen, onBack = back, banner = feedback.banner, onDismissBanner = { feedback = FeedbackMachine.dismiss(feedback, it) }) { s ->
            when (s) {
                Screen.Onboarding -> OnboardingScreen(onDone = { onboarded = true })
                Screen.Activation -> ActivationScreen(
                    activating = activating,
                    error = activationError,
                    onActivate = {
                        val started = FeedbackMachine.start(feedback, OwnerAction.ACTIVATE)
                        if (started != null) {
                            feedback = started
                            activating = true
                            activationError = null
                            scope.launch {
                                delay(900)
                                activating = false
                                if (cfg.fail != null) {
                                    activationError = "Invalid invite code"
                                    feedback = FeedbackMachine.fail(feedback, "Invalid invite code")
                                } else {
                                    signedIn = true
                                    feedback = FeedbackMachine.succeed(feedback, FeedbackText.ACTIVATED)
                                }
                            }
                        }
                    },
                    notice = if (cfg.variant == "member-signedout") SIGNED_OUT_NOTICE else null,
                )
                Screen.Home -> DashboardScreen(
                    HomeUi(
                        profile = profileValue,
                        state = state,
                        error = null,
                        inviteCode = if (owner) OWN_CODE else "RF-DEMO-0003",
                        selectedRegion = selected,
                        regionInUse = selected,
                        activeRegion = if (state == TunnelState.CONNECTED) profileValue.regions.firstOrNull { it.id == selected }?.label else null,
                        accessNotice = when (cfg.variant) {
                            "member-expired" -> AccessNotice.Expired("3 Oct 2026")
                            "member-soon" -> AccessNotice.DaysLeft(5)
                            else -> null
                        },
                        ipv6Hint = cfg.ipv4Wifi && selected == "tokyo",
                        quota = DeviceQuota(200.0, 63.4, 12, 2_640_000_000L, 486_000_000L, false),
                        stats = if (state == TunnelState.CONNECTED) LiveStats(9_620_000L, 1_840_000L, 38) else LiveStats(),
                        connectedSince = since,
                        appearance = appearance,
                    ),
                    homeActions,
                )
                Screen.People -> PeopleScreen(adminUi, adminActions)
                is Screen.Person -> PersonScreen(
                    people.firstOrNull { it.code == s.code },
                    adminUi,
                    adminActions,
                    demoQuota = cfg.quota,
                    demoConfirmDelete = cfg.confirmDelete,
                )
                Screen.Deleted -> DeletedScreen(adminUi, adminActions)
            }
        }
    }
}
