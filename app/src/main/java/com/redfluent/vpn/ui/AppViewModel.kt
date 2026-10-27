package com.redfluent.vpn.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redfluent.vpn.BuildConfig
import com.redfluent.vpn.api.ApiClient
import com.redfluent.vpn.api.ApiException
import com.redfluent.vpn.api.LogoutAllResult
import com.redfluent.vpn.api.OwnerLogoutApi
import com.redfluent.vpn.api.RuntimeConfig
import com.redfluent.vpn.api.logOutAll
import com.redfluent.vpn.data.ActionFeedback
import com.redfluent.vpn.data.Appearance
import com.redfluent.vpn.data.FeedbackMachine
import com.redfluent.vpn.data.FeedbackText
import com.redfluent.vpn.data.OwnerAction
import com.redfluent.vpn.data.DEFAULT_REGIONS
import com.redfluent.vpn.data.DeletedInvite
import com.redfluent.vpn.data.DeviceQuota
import com.redfluent.vpn.data.IPv6Hint
import com.redfluent.vpn.data.LiveStats
import com.redfluent.vpn.data.OwnerDevice
import com.redfluent.vpn.data.Person
import com.redfluent.vpn.data.RecentlyDeleted
import com.redfluent.vpn.data.buildPeople
import com.redfluent.vpn.data.ownerCodes
import com.redfluent.vpn.data.resolveRegion
import com.redfluent.vpn.net.WifiIpv6Watcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.redfluent.vpn.data.AccessNotice
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.FollowAction
import com.redfluent.vpn.data.FollowOutcome
import com.redfluent.vpn.data.InviteUpdate
import com.redfluent.vpn.data.OwnerInvite
import com.redfluent.vpn.data.ProfileStore
import com.redfluent.vpn.data.ProfileSyncResult
import com.redfluent.vpn.data.REGION_TOKYO
import com.redfluent.vpn.data.StopKind
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.data.UsageGroup
import com.redfluent.vpn.data.UsagePeriod
import com.redfluent.vpn.data.accessNotice as computeAccessNotice
import com.redfluent.vpn.data.decideFollow
import com.redfluent.vpn.data.inviteErrorText
import com.redfluent.vpn.data.isSignedOutByProfile
import com.redfluent.vpn.data.locksConnect
import com.redfluent.vpn.data.logoutErrorText
import com.redfluent.vpn.data.normalizeRegionId
import com.redfluent.vpn.data.runtimeRegionParam
import com.redfluent.vpn.data.shouldDisconnectForRegions
import com.redfluent.vpn.debug.DebugHooks
import com.redfluent.vpn.vpn.RedFluentVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "RfvViewModel"

/** Owner Admin（People）要用的全部数据。 */
data class AdminData(
    val invites: List<OwnerInvite> = emptyList(),
    val devices: List<OwnerDevice> = emptyList(),
    val period: UsagePeriod? = null,
    val deleted: List<DeletedInvite> = emptyList(),
    /** 后端有 Recently Deleted（GET /owner/invites/deleted 不是 404）。 */
    val deletedSupported: Boolean = false,
    val deletedError: String? = null,
) {
    /** 一个邀请码一个人（owner 自己的那个认得出来）。 */
    fun people(ownProfileId: String?, storedCode: String?, ownerLabel: String?): List<Person> =
        buildPeople(invites, devices, ownerCodes(devices, ownProfileId, storedCode), ownerLabel)
}

/** Owner Admin 的界面状态。刷新失败时保留上一份数据，同时标出「这份数据可能过期」。 */
sealed interface AdminUiState {
    data object Loading : AdminUiState
    data class Error(val message: String) : AdminUiState
    data class Loaded(val data: AdminData, val refreshFailed: Boolean = false) : AdminUiState
}

/** Owner Admin 里现在在哪一页。 */
sealed interface AdminRoute {
    data object People : AdminRoute
    data class Person(val code: String) : AdminRoute
    data object Deleted : AdminRoute
}

/** Recently Deleted 页：正在恢复的、恢复失败的原因、刚恢复的人名。 */
data class RestoreUi(
    val working: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val restored: List<String> = emptyList(),
)

/** 界面的唯一状态源。所有显示都基于真实状态，不做乐观更新。 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    /** 兜底：后台协程里任何没想到的异常只记日志，不能把进程带崩。 */
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled failure in coroutine: ${e.javaClass.simpleName}")
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(errorHandler, block = block)

    /** 后台的 DataStore 读写失败只记日志（只记异常类型，不含内容）；取消信号必须原样抛出。 */
    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    val profile: StateFlow<DeviceProfile?> =
        store.profileFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboarded: StateFlow<Boolean> =
        store.onboardedFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val inviteCode: StateFlow<String?> =
        store.lastInviteFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 本地资料和引导标记都读到了：之前界面只画一块底色，不会先闪一下引导页 / 激活页。 */
    val loaded: StateFlow<Boolean> =
        combine(store.profileFlow, store.onboardedFlow) { _, _ -> true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 用户选的区域（owner 才有得选；其他人永远是东京）。 */
    val selectedRegion: StateFlow<String> =
        store.selectedRegionFlow.stateIn(viewModelScope, SharingStarted.Eagerly, REGION_TOKYO)

    /** 服务里隧道的真实状态。 */
    private val serviceState: StateFlow<TunnelState> = RedFluentVpnService.state

    /** 取配置（还没交给服务）这段时间是否在进行中。 */
    private val _connecting = MutableStateFlow(false)

    /**
     * 界面看到的隧道状态 = 服务里的真实状态；唯一的加工：取配置期间服务还没起来，
     * 此时显示 CONNECTING（否则界面写着 Not connected，用户再点一下就会发出第二次连接）。
     */
    val tunnelState: StateFlow<TunnelState> =
        combine(serviceState, _connecting) { state, fetching ->
            if (fetching && (state == TunnelState.DISCONNECTED || state == TunnelState.ERROR)) {
                TunnelState.CONNECTING
            } else {
                state
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, serviceState.value)

    val tunnelError: StateFlow<String?> = RedFluentVpnService.lastError

    /** 隧道实际连着的区域名（服务器确认的）；没连上时为 null。 */
    val activeRegion: StateFlow<String?> = RedFluentVpnService.activeRegion

    private val _activating = MutableStateFlow(false)
    val activating = _activating.asStateFlow()

    private val _activationError = MutableStateFlow<String?>(null)
    val activationError = _activationError.asStateFlow()

    /** 需要向系统申请 VPN 授权时置位，由界面弹出系统对话框。 */
    private val _needsVpnPermission = MutableStateFlow(false)
    val needsVpnPermission = _needsVpnPermission.asStateFlow()

    private val _showAdmin = MutableStateFlow(false)
    val showAdmin = _showAdmin.asStateFlow()

    private val _adminRoute = MutableStateFlow<AdminRoute>(AdminRoute.People)
    val adminRoute = _adminRoute.asStateFlow()

    private val _admin = MutableStateFlow<AdminUiState>(AdminUiState.Loading)
    val admin = _admin.asStateFlow()

    /** 所有要等服务器的操作共用的反馈：哪个在忙 + 顶部横幅（见 [FeedbackMachine]）。 */
    private val _feedback = MutableStateFlow(ActionFeedback())
    val feedback = _feedback.asStateFlow()

    /** 开始一个操作；已有操作在进行时返回 false（不发第二次请求）。 */
    private fun startAction(action: OwnerAction): Boolean {
        val next = FeedbackMachine.start(_feedback.value, action) ?: return false
        _feedback.value = next
        return true
    }

    private fun succeed(text: String) {
        _feedback.value = FeedbackMachine.succeed(_feedback.value, text)
    }

    private fun fail(text: String) {
        _feedback.value = FeedbackMachine.fail(_feedback.value, text)
    }

    fun dismissBanner(seq: Long) {
        _feedback.value = FeedbackMachine.dismiss(_feedback.value, seq)
    }

    private val _restore = MutableStateFlow(RestoreUi())
    val restore = _restore.asStateFlow()

    // ---- 1.3 主页 ----

    /** app 内选的明暗。初值由 MainActivity 在第一帧前读好传进来，避免先白后黑闪一下。 */
    val appearance: StateFlow<Appearance> =
        store.appearanceFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance.LIGHT)

    /** 本设备本周期用量（Bandwidth）；拿不到时为 null，主页显示 "—"。 */
    private val _quota = MutableStateFlow<DeviceQuota?>(null)
    val quota = _quota.asStateFlow()

    /** Down / Up MB/s 与 Ping（只在前台且已连接时测）。 */
    private val _stats = MutableStateFlow(LiveStats())
    val stats = _stats.asStateFlow()

    /** 进入 CONNECTED 的时刻（elapsedRealtime）；没连着为 null。 */
    val connectedSince: StateFlow<Long?> = RedFluentVpnService.connectedSince

    private val wifi = WifiIpv6Watcher(app)
    private val foreground = MutableStateFlow(false)

    /** 正在用（或要用）的区域：连着时以隧道确认的区域为准，否则按选择 / 服务器分配。 */
    val regionInUse: StateFlow<String> =
        combine(profile, selectedRegion, RedFluentVpnService.activeRegionId, serviceState) { p, sel, active, st ->
            if (st == TunnelState.CONNECTED && !active.isNullOrBlank()) {
                normalizeRegionId(active)
            } else {
                resolveRegion(sel, p?.regions ?: DEFAULT_REGIONS)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, REGION_TOKYO)

    /** "Wi-Fi has no IPv6 — switch to cellular"（规则同 iOS IPv6Hint，另按上一次运行配置的 runtimeMeta.ipv4Blocked 开关）。 */
    val ipv6Hint: StateFlow<Boolean> =
        combine(wifi.state, regionInUse, store.ipv4BlockedFlow) { w, region, blocked ->
            IPv6Hint.shows(w.onWiFi, w.wifiHasIPv6, region, blocked)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 这台设备被 owner 登出后，激活页上显示 "Signed out. …"（不是吓人的错误）。 */
    private val _signedOut = MutableStateFlow(false)
    val signedOutNotice = _signedOut.asStateFlow()

    /**
     * 主页上的到期提示（Expired · 3 Nov 2026 / N days left / Revoked）：来自资料里的到期状态，
     * 以及"无感"跟随刚发现的叫停原因（比资料刷新更早知道）。
     */
    val accessNotice: StateFlow<AccessNotice?> =
        combine(profile, RedFluentVpnService.stopReason) { p, stop -> computeAccessNotice(p, stop) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var lastProfileRefreshAt = 0L
    private var refreshJob: Job? = null
    private var adminJob: Job? = null
    private var actionJob: Job? = null
    private var quotaJob: Job? = null

    // 「连接 / 断开 / 切区域 / 退出登录」同一时刻只能有一个在进行：
    // 新的操作先取消旧的（包括取配置到一半的连接——它迟到的结果会被丢弃），再开始自己。
    private var opJob: Job? = null
    private var opGen = 0L

    /** 正在取配置的区域 id（用于「服务器不再提供这个区域」时判断是否要断开）。 */
    @Volatile
    private var pendingRegionId: String? = null

    init {
        // 后端在 token 快过期时回发新 token：只有本地 token 仍是发请求时那个才替换。
        ApiClient.onTokenRenewed = { oldToken, newToken ->
            safely("token renewal") {
                store.updateProfile { it.withRenewedToken(oldToken, newToken) }
            }
        }

        // "无感"跟随（服务里）发现账号到期 / 被登出而停了隧道：
        //  - 被登出（401 / 其它 403）→ 直接回到激活页，提示 "Signed out. …"；
        //  - 到期 → 刷新资料，让主页显示 Expired
        launchSafe {
            RedFluentVpnService.stopReason.filterNotNull().collect { stop ->
                if (stop.kind == StopKind.REVOKED) handleSignedOut(null) else refreshProfile(force = true)
            }
        }

        // 账号处于到期 / 吊销状态期间：定期刷新资料。管理员把时长改长或恢复后，界面自动解除，无需手动操作
        launchSafe {
            accessNotice.map { it.locksConnect() }.distinctUntilChanged().collectLatest { locked ->
                if (locked) {
                    val interval = if (BuildConfig.DEBUG) {
                        DebugHooks.followIntervalMs(getApplication<Application>()) ?: EXPIRED_POLL_MS
                    } else {
                        EXPIRED_POLL_MS
                    }
                    while (true) {
                        delay(interval)
                        refreshProfile(force = true)
                    }
                }
            }
        }
    }

    init {
        startStatsLoop()
    }

    fun finishOnboarding() {
        launchSafe { safely("save onboarding flag") { store.setOnboarded() } }
    }

    fun activate(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || _activating.value) return
        if (startAction(OwnerAction.ACTIVATE).not()) return
        launchSafe {
            _activating.value = true
            _activationError.value = null
            try {
                val deviceId = store.devicePublicId()
                val profile = ApiClient.redeemInvite(
                    code = trimmed,
                    devicePublicId = deviceId,
                    deviceName = android.os.Build.MODEL ?: "Android",
                    appVersion = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                )
                store.saveProfile(profile)
                _signedOut.value = false
                store.saveInviteCode(trimmed)
                // 激活响应里不一定带 owner / 区域信息，立刻再取一次 /profile。
                refreshProfile(force = true)
                refreshQuota()
                succeed(FeedbackText.ACTIVATED)
            } catch (e: CancellationException) {
                _feedback.value = _feedback.value.copy(busy = null)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Activation failed"
                _activationError.value = message
                fail(message)
            } finally {
                _activating.value = false
            }
        }
    }

    /**
     * 退出登录：取消一切在途的操作（连接 / 刷新 / 用量），拆掉隧道，清空本地资料与用量页状态。
     * signedOut = true：不是用户自己点的，而是服务器已把这台设备登出——激活页上显示 "Signed out. …"。
     */
    fun signOut(signedOut: Boolean = false) {
        _signedOut.value = signedOut
        refreshJob?.cancel()
        adminJob?.cancel()
        actionJob?.cancel()
        quotaJob?.cancel()
        _showAdmin.value = false
        _adminRoute.value = AdminRoute.People
        _admin.value = AdminUiState.Loading
        _feedback.value = _feedback.value.copy(busy = null, banner = null)
        _restore.value = RestoreUi()
        _quota.value = null
        RedFluentVpnService.clearFailure()
        RedFluentVpnService.clearStopReason()
        beginOp {
            _connecting.value = false
            pendingRegionId = null
            sendStop()
            safely("clear profile") { store.clearProfile() }
        }
    }

    /**
     * 服务器已把这台设备登出（/profile 说 enabled = false，或隧道被 401 / 403 叫停）：
     * 回到激活页，邀请码仍然有效，再输入一次就能重新激活。
     * expectedProfileId：请求在途时设备可能已换成别的账号——对不上就什么也不做。
     */
    private fun handleSignedOut(expectedProfileId: String?) {
        launchSafe {
            val current = store.profile() ?: return@launchSafe
            if (expectedProfileId != null && current.profileId != expectedProfileId) return@launchSafe
            Log.i(TAG, "signed out by the server: back to the activation screen")
            signOut(signedOut = true)
        }
    }

    // ---- 资料刷新 -------------------------------------------------------

    /**
     * 从服务器刷新 isOwner / regions，并在已选区域不再被提供时静默退回默认区域；
     * 若隧道正在用的区域被收回，则断开。失败只记日志，保留本地已有的值，不打扰用户。
     */
    fun refreshProfile(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProfileRefreshAt < PROFILE_REFRESH_MIN_MS) return
        lastProfileRefreshAt = now
        refreshJob?.cancel()
        refreshJob = launchSafe { refreshProfileNow() }
    }

    private suspend fun refreshProfileNow() {
        val before = store.profile() ?: return
        val status = try {
            ApiClient.fetchProfile(before.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile refresh failed: ${e.javaClass.simpleName}")
            return
        }
        // 服务器明确说这台设备已被登出（enabled = false）：回到激活页
        if (isSignedOutByProfile(store.profile(), before.profileId, status)) {
            handleSignedOut(before.profileId)
            return
        }
        // 请求在途时设备可能已被换成别的账号：只有 profileId 仍对得上才写回；
        // isOwner / regions 与「区域重置」在同一次 DataStore 写入里完成。
        val applied: ProfileSyncResult? = try {
            store.applyServerProfile(before.profileId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile write failed: ${e.javaClass.simpleName}")
            null
        }
        if (applied == null) return

        // 不再是 owner：收起 Owner Admin
        if (!applied.profile.isOwner) {
            _showAdmin.value = false
        }

        // 资料已刷新：以资料为准，清掉服务给的"刚被叫停"提示（到期以资料为准；吊销要等设备恢复启用才解除）
        RedFluentVpnService.stopReason.value?.let { stop ->
            if (stop.kind == StopKind.EXPIRED || status.enabled) RedFluentVpnService.clearStopReason()
        }

        val running = tunnelState.value == TunnelState.CONNECTED || tunnelState.value == TunnelState.CONNECTING

        // 账号已到期但隧道还连着（刚好过期、下一次跟随检查还没到）：立刻断开
        if (running && applied.profile.expired && !applied.profile.isOwner) {
            stopTunnel()
            return
        }

        // 服务器不再提供隧道正在用的区域：owner 必须断开，不能界面写着东京、隧道却还悬在新加坡。
        // 普通用户不在此列——他们的区域由服务器分配，换了区域由服务里的"无感"跟随自动热重载。
        val inUse = RedFluentVpnService.activeRegionId.value ?: pendingRegionId
        if (running && applied.profile.isOwner && shouldDisconnectForRegions(inUse, applied.profile.regions)) {
            val id = normalizeRegionId(inUse)
            val label = before.regions.firstOrNull { normalizeRegionId(it.id) == id }?.label ?: id
            disconnectBecauseRegionGone(label)
        }
    }

    private fun disconnectBecauseRegionGone(label: String) = beginOp {
        _connecting.value = false
        pendingRegionId = null
        sendStop()
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            serviceState.first { it == TunnelState.DISCONNECTED || it == TunnelState.ERROR }
        }
        RedFluentVpnService.reportFailure("$label is no longer available.")
        safely("save connection intent") { store.setWantConnected(false) }
    }

    // ---- 连接 / 断开 / 切区域 ----------------------------------------------

    /** 点中央环：已连接（或正在连）就断开，否则连接。 */
    fun toggleTunnel() {
        when (tunnelState.value) {
            TunnelState.CONNECTED, TunnelState.CONNECTING -> stopTunnel()
            else -> {
                // 账号到期 / 被吊销期间不发起连接（界面上按钮也是禁用的）
                if (!accessNotice.value.locksConnect()) connect()
            }
        }
    }

    /**
     * 切换区域。没连着：只记住选择；已连着（或正在连 / 正在取配置）：用新区域重连，
     * 并且取代任何在途的连接——旧区域迟到的配置会被丢弃，只连最后选的那个。
     */
    fun selectRegion(rawId: String) {
        val id = normalizeRegionId(rawId)
        val p = profile.value ?: return
        if (p.regions.none { normalizeRegionId(it.id) == id }) return
        launchSafe {
            if (store.selectedRegion() == id) return@launchSafe
            safely("save selected region") { store.setSelectedRegion(id) }
            RedFluentVpnService.clearFailure()
            val st = serviceState.value
            val tunnelUp = st == TunnelState.CONNECTED || st == TunnelState.CONNECTING || st == TunnelState.STOPPING
            if (tunnelUp || _connecting.value) startConnect(stopFirst = tunnelUp)
        }
    }

    fun onVpnPermissionGranted() {
        _needsVpnPermission.value = false
        connect(skipPermissionCheck = true)
    }

    fun onVpnPermissionDenied() {
        _needsVpnPermission.value = false
    }

    private fun connect(skipPermissionCheck: Boolean = false) {
        val app = getApplication<Application>()
        if (!skipPermissionCheck && android.net.VpnService.prepare(app) != null) {
            _needsVpnPermission.value = true
            return
        }
        startConnect(stopFirst = false)
    }

    /** 开始一个新操作（取代旧的）。block 收到自己这一代的编号，用来判断「我还是不是最新的」。 */
    private fun beginOp(block: suspend CoroutineScope.(gen: Long) -> Unit): Job {
        opJob?.cancel()
        val gen = ++opGen
        val job = launchSafe { block(gen) }
        opJob = job
        // 取消旧操作后，只有「最新一代」才有资格最终把 connecting 复位
        job.invokeOnCompletion { if (gen == opGen) _connecting.value = false }
        return job
    }

    private fun startConnect(stopFirst: Boolean) = beginOp { gen ->
        _connecting.value = true
        try {
            if (stopFirst) {
                sendStop()
                withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    serviceState.first { it == TunnelState.DISCONNECTED || it == TunnelState.ERROR }
                }
                delay(RESTART_SETTLE_MS)
            }
            if (connectOnce()) {
                // 等服务确认接手（进入 CONNECTING / CONNECTED / ERROR），期间界面一直显示 Connecting，不闪一下 Not connected
                withTimeoutOrNull(START_ACK_TIMEOUT_MS) {
                    serviceState.first {
                        it == TunnelState.CONNECTING || it == TunnelState.CONNECTED || it == TunnelState.ERROR
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RedFluentVpnService.reportFailure(e.message ?: "Connection failed")
        } finally {
            // 被取代的旧操作迟到的 finally 不能清掉新操作的区域
            if (gen == opGen) pendingRegionId = null
        }
    }

    /**
     * 取配置并交给服务。任何环节失败都变成界面上可见的错误状态（东京也一样）。
     * 返回 true = 已经把 START 交给服务；false = 没有发出（失败或已被取代）。
     */
    private suspend fun connectOnce(): Boolean {
        val profile = store.profile() ?: return false
        // 普通用户：不带 region 参数，区域由服务器按「分配给这个账号的区域」决定；
        // owner：用自己选的区域（已不被提供则退回服务器默认区域——不会带着过期选择去请求）
        val region: String? = runtimeRegionParam(profile.isOwner, store.selectedRegion(), profile.regions)
        pendingRegionId = region
        RedFluentVpnService.clearFailure()
        safely("save connection intent") { store.setWantConnected(true) }

        val runtime: RuntimeConfig = try {
            ApiClient.fetchRuntimeConfig(profile.token, region)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 账号到期 / 被吊销：显示 Expired / Revoked（与"无感"跟随同一条规则），而不是通用的连接失败
            if (e is ApiException) {
                val action = decideFollow(null, FollowOutcome.Failed(e.code, e.reason, e.expiresAt))
                if (action is FollowAction.Stop) {
                    RedFluentVpnService.reportAccessEnded(action.kind, action.expiresAt)
                    refreshProfile(force = true)
                    return false
                }
            }
            // 绝不回退到别的区域或任何内置配置，把失败明明白白显示出来
            val name = (if (region != null) profile.regions.firstOrNull { normalizeRegionId(it.id) == region }?.label else null)
                ?: region?.replaceFirstChar { it.uppercase() }
                ?: profile.serverRegion.ifBlank { "Server" }
            RedFluentVpnService.reportFailure("$name: ${e.message ?: "Connection failed"}")
            return false
        }

        // 记下这份配置的 runtimeMeta.ipv4Blocked（主页 IPv6 提示据此开关）
        safely("save runtime meta") { store.setIpv4Blocked(runtime.ipv4Blocked) }

        // 取配置期间可能已被取代（切了区域 / 点了断开 / 退出登录）或设备已换账号：丢弃这份配置
        currentCoroutineContext().ensureActive()
        val latest = store.profile()
        if (latest == null || latest.profileId != profile.profileId) return false

        val app = getApplication<Application>()
        app.startForegroundService(
            Intent(app, RedFluentVpnService::class.java).apply {
                action = RedFluentVpnService.ACTION_START
                putExtra(RedFluentVpnService.EXTRA_CONFIG, runtime.configJson)
                putExtra(RedFluentVpnService.EXTRA_REGION_ID, runtime.regionId)
                putExtra(RedFluentVpnService.EXTRA_REGION_NAME, runtime.regionName)
            },
        )
        return true
    }

    /** 用户点断开：同时取消在途的连接，免得取配置的结果迟到后又把隧道拉起来。 */
    private fun stopTunnel() = beginOp {
        _connecting.value = false
        pendingRegionId = null
        sendStop()
        safely("save connection intent") { store.setWantConnected(false) }
    }

    private fun sendStop() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, RedFluentVpnService::class.java).apply {
                action = RedFluentVpnService.ACTION_STOP
            },
        )
    }

    // ---- 1.3 主页：明暗 / 用量 / 实时读数 / Wi-Fi ----------------------------------------------

    fun setAppearance(value: Appearance) {
        launchSafe { safely("save appearance") { store.setAppearance(value) } }
    }

    /** 前台 / 后台：只在前台时盯 Wi-Fi、测速度和延迟（同 iOS：scenePhase 不在前台就全停）。 */
    fun setForeground(value: Boolean) {
        foreground.value = value
        if (value) {
            wifi.start()
            refreshQuota()
        } else {
            wifi.stop()
        }
    }

    /** Bandwidth：GET /device/quota。失败保留上一次的读数（没有就是 "—"），不打扰用户。 */
    fun refreshQuota() {
        if (quotaJob?.isActive == true) return
        quotaJob = launchSafe {
            val p = store.profile() ?: return@launchSafe
            try {
                val q = ApiClient.fetchDeviceQuota(p.token)
                if (store.profile()?.profileId != p.profileId) return@launchSafe
                if (q != null) _quota.value = q
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "quota refresh failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 已连接且在前台时：每秒用本 app 的收发字节数算 Down / Up（引擎的出站连接都属于本 app，
     * 所以这就是隧道流量；只读系统计数，不碰引擎），每 15 秒测一次 Ping。断开 / 退到后台即停，读数清空。
     */
    private fun startStatsLoop() {
        launchSafe {
            combine(foreground, serviceState) { fg, st -> fg && st == TunnelState.CONNECTED }
                .distinctUntilChanged()
                .collectLatest { live ->
                    _stats.value = LiveStats()
                    if (live.not()) return@collectLatest
                    val uid = android.os.Process.myUid()
                    var lastRx = android.net.TrafficStats.getUidRxBytes(uid)
                    var lastTx = android.net.TrafficStats.getUidTxBytes(uid)
                    var lastAt = SystemClock.elapsedRealtime()
                    var tick = 0
                    while (true) {
                        if (tick % PING_EVERY_TICKS == 0) {
                            launchSafe {
                                val ms = ApiClient.ping()
                                if (serviceState.value == TunnelState.CONNECTED) _stats.value = _stats.value.copy(pingMs = ms)
                            }
                        }
                        delay(STATS_TICK_MS)
                        tick++
                        val rx = android.net.TrafficStats.getUidRxBytes(uid)
                        val tx = android.net.TrafficStats.getUidTxBytes(uid)
                        val now = SystemClock.elapsedRealtime()
                        val dt = (now - lastAt).coerceAtLeast(1L)
                        val supported = rx >= 0 && tx >= 0 && lastRx >= 0 && lastTx >= 0
                        _stats.value = _stats.value.copy(
                            downBps = if (supported) ((rx - lastRx).coerceAtLeast(0) * 1000 / dt) else null,
                            upBps = if (supported) ((tx - lastTx).coerceAtLeast(0) * 1000 / dt) else null,
                        )
                        lastRx = rx
                        lastTx = tx
                        lastAt = now
                    }
                }
        }
    }

    // ---- Owner Admin（People）----------------------------------------------------------------

    fun openAdmin() {
        if (profile.value?.isOwner != true) return
        _adminRoute.value = AdminRoute.People
        _showAdmin.value = true
        refreshAdmin()
    }

    fun closeAdmin() {
        _showAdmin.value = false
    }

    /** 系统返回键：个人页 / Recently Deleted → People；People → 主页。 */
    fun adminBack() {
        if (_adminRoute.value == AdminRoute.People) {
            closeAdmin()
        } else {
            _adminRoute.value = AdminRoute.People
            _restore.value = RestoreUi()
        }
    }

    fun openPerson(code: String) {
        _adminRoute.value = AdminRoute.Person(code)
    }

    fun openDeleted() {
        _restore.value = RestoreUi()
        _adminRoute.value = AdminRoute.Deleted
        refreshAdmin()
    }

    fun refreshAdmin() {
        if (adminJob?.isActive == true) return
        adminJob = launchSafe {
            val p = store.profile()
            if (p == null || p.isOwner.not()) return@launchSafe
            val previous = (_admin.value as? AdminUiState.Loaded)?.data
            try {
                val (invites, devices) = coroutineScope {
                    val inv = async { ApiClient.fetchOwnerInvites(p.token) }
                    val dev = async { ApiClient.fetchOwnerDevices(p.token) }
                    inv.await() to dev.await()
                }
                if (invites.skipped > 0) Log.w(TAG, "people: ${invites.skipped} invite(s) could not be decoded")
                if (devices.skipped > 0) Log.w(TAG, "people: ${devices.skipped} device(s) could not be decoded")
                val deleted = loadDeleted(p.token, previous)
                // 请求在途时设备可能已换账号：不把上一个账号的数据显示给新账号
                if (store.profile()?.profileId != p.profileId) return@launchSafe
                _admin.value = AdminUiState.Loaded(
                    AdminData(
                        invites = invites.invites,
                        devices = devices.devices,
                        period = devices.period,
                        deleted = deleted.deleted,
                        deletedSupported = deleted.deletedSupported,
                        deletedError = deleted.deletedError,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = _admin.value
                _admin.value = if (current is AdminUiState.Loaded) {
                    current.copy(refreshFailed = true)
                } else {
                    AdminUiState.Error(e.message ?: "Failed to load people")
                }
            }
        }
    }

    /** 404 = 旧后端：入口隐藏、不报错；其它失败保留入口，在 Recently Deleted 页上说原因。 */
    private suspend fun loadDeleted(token: String, previous: AdminData?): AdminData = try {
        val r = ApiClient.fetchDeletedInvites(token)
        AdminData(deleted = RecentlyDeleted.sorted(r.invites), deletedSupported = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (RecentlyDeleted.isUnsupported(e.code)) {
            AdminData(deletedSupported = false)
        } else {
            AdminData(deleted = previous?.deleted.orEmpty(), deletedSupported = true, deletedError = e.message)
        }
    } catch (e: Exception) {
        AdminData(deleted = previous?.deleted.orEmpty(), deletedSupported = true, deletedError = e.message ?: "Failed to load")
    }

    private fun currentData(): AdminData? = (_admin.value as? AdminUiState.Loaded)?.data

    private fun currentPerson(code: String): Person? {
        val data = currentData() ?: return null
        val p = profile.value
        return data.people(p?.profileId, inviteCode.value, p?.ownerLabel).firstOrNull { it.code == code }
    }


    /** 保存个人页：六个字段完整带上（见 [buildInviteUpdate]）。成功后跟着新邀请码，刷新列表。 */
    fun savePerson(update: InviteUpdate) {
        runAdminOp(
            OwnerAction.SAVE,
            successText = { FeedbackText.SAVED },
            errorText = { code, reason -> inviteErrorText(code, reason) },
            onDone = { if (_adminRoute.value == AdminRoute.Person(update.code)) _adminRoute.value = AdminRoute.Person(update.newCode) },
        ) { token ->
            ApiClient.updateInvite(token, update)
            null
        }
    }

    /** 删除一个人（软删除，30 天内可在 Recently Deleted 恢复）。owner 自己的永远走不到这里。 */
    fun deletePerson(code: String) {
        val person = currentPerson(code) ?: return
        if (person.isOwner) return
        runAdminOp(
            OwnerAction.DELETE,
            successText = { FeedbackText.deleted(person.label) },
            onDone = { if (_adminRoute.value == AdminRoute.Person(code)) _adminRoute.value = AdminRoute.People },
        ) { token ->
            ApiClient.deleteInvite(token, person.code)
            null
        }
    }

    /** 把一个人的所有可用设备登出（接口还没上线时逐台回退，规则见 [logOutAll]）。 */
    fun logoutAll(code: String) {
        val person = currentPerson(code) ?: return
        if (person.isOwner || person.devicesInUse == 0) return
        val group = UsageGroup(
            label = person.label,
            totalGB = person.usedGB ?: 0.0,
            devices = person.devices.map { it.device },
            code = person.code,
            activeDevices = person.devicesInUse,
            isOwnerInvite = false,
        )
        runAdminOp(OwnerAction.LOG_OUT_ALL, successText = { FeedbackText.loggedOutAll(person.devicesInUse) }) { token ->
            val api = object : OwnerLogoutApi {
                override suspend fun logoutUser(code: String): Int? = ApiClient.logoutUser(token, code)
                override suspend fun revokeDevice(profileId: String) = ApiClient.revokeDevice(token, profileId)
            }
            (logOutAll(api, group) as? LogoutAllResult.Failed)?.message
        }
    }

    /** 把一台设备登出：设备回到激活页，邀请码仍然有效。owner 自己的设备永远不会走到这里。 */
    fun logoutDevice(code: String, profileId: String) {
        val person = currentPerson(code) ?: return
        if (person.isOwner) return
        val row = person.devices.firstOrNull { it.device.profileId == profileId && it.signedIn } ?: return
        val name = row.device.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: row.device.deviceName ?: "Device"
        runAdminOp(OwnerAction.LOG_OUT_DEVICE, successText = { FeedbackText.loggedOutDevice(name) }) { token ->
            ApiClient.revokeDevice(token, profileId)
            null
        }
    }

    fun renameDevice(profileId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return
        runAdminOp(OwnerAction.RENAME, successText = { FeedbackText.RENAMED }) { token ->
            ApiClient.renameDevice(token, profileId, trimmed)
            null
        }
    }

    /**
     * 跑一个 owner 操作：成功（返回 null）或失败（返回原因 / 抛出）都进顶部横幅，之后刷新列表，让界面显示真实状态。
     * 已有操作在进行时直接忽略（按钮也是禁用的），不会发出第二次请求。
     */
    private fun runAdminOp(
        action: OwnerAction,
        successText: () -> String,
        errorText: (Int, String) -> String = ::logoutErrorText,
        onDone: () -> Unit = {},
        block: suspend (token: String) -> String?,
    ) {
        if (startAction(action).not()) return
        actionJob = launchSafe {
            try {
                val p = store.profile()
                if (p == null || p.isOwner.not()) {
                    fail("Not allowed.")
                    return@launchSafe
                }
                val failure = block(p.token)
                if (failure == null) {
                    onDone()
                    succeed(successText())
                } else {
                    fail(failure)
                }
            } catch (e: CancellationException) {
                _feedback.value = _feedback.value.copy(busy = null)
                throw e
            } catch (e: ApiException) {
                fail(errorText(e.code, e.reason))
            } catch (e: Exception) {
                fail(e.message ?: "Something went wrong.")
            }
            adminJob?.cancel()
            refreshAdmin()
        }
    }

    /** 恢复一个删除了的人：邀请码重新可用，旧设备仍是登出状态（每台要重新输入邀请码）。 */
    fun restore(item: DeletedInvite) {
        if (_restore.value.working.contains(item.key)) return
        if (startAction(OwnerAction.RESTORE).not()) return
        _restore.value = _restore.value.let { it.copy(working = it.working + item.key, errors = it.errors - item.key) }
        launchSafe {
            var error: String? = null
            try {
                val p = store.profile()
                if (p == null || p.isOwner.not()) {
                    error = "Not allowed."
                } else {
                    ApiClient.restoreInvite(p.token, item.code)
                }
            } catch (e: CancellationException) {
                _feedback.value = _feedback.value.copy(busy = null)
                throw e
            } catch (e: ApiException) {
                error = RecentlyDeleted.restoreFailureText(e.code, e.reason)
            } catch (e: Exception) {
                error = e.message ?: "Could not restore."
            }
            val failed = error
            _restore.value = _restore.value.let {
                if (failed == null) {
                    it.copy(working = it.working - item.key, restored = it.restored + item.label)
                } else {
                    it.copy(working = it.working - item.key, errors = it.errors + (item.key to failed))
                }
            }
            if (failed == null) succeed(FeedbackText.restored(item.label)) else fail(failed)
            adminJob?.cancel()
            refreshAdmin()
        }
    }

    private companion object {
        const val EXPIRED_POLL_MS = 60_000L
        const val PROFILE_REFRESH_MIN_MS = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val RESTART_SETTLE_MS = 500L
        const val START_ACK_TIMEOUT_MS = 3_000L
        const val STATS_TICK_MS = 1_000L
        const val PING_EVERY_TICKS = 15
    }
}
