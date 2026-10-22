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
import com.redfluent.vpn.data.findLoggableDevice
import com.redfluent.vpn.data.findLoggableGroup
import com.redfluent.vpn.data.groupByUser
import com.redfluent.vpn.data.inviteErrorText
import com.redfluent.vpn.data.isSignedOutByProfile
import com.redfluent.vpn.data.locksConnect
import com.redfluent.vpn.data.logoutErrorText
import com.redfluent.vpn.data.normalizeRegionId
import com.redfluent.vpn.data.runtimeRegionParam
import com.redfluent.vpn.data.shouldDisconnectForRegions
import com.redfluent.vpn.data.sortInvites
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

/** Usage 页的界面状态。刷新失败时保留上一份数据，同时标出「这份数据可能过期」。 */
sealed interface UsageUiState {
    data object Loading : UsageUiState
    data class Error(val message: String) : UsageUiState
    data class Loaded(
        val period: UsagePeriod?,
        val groups: List<UsageGroup>,
        val refreshFailed: Boolean = false,
    ) : UsageUiState
}

/** Usage 页里「Log out / Log out all devices」的进度。失败原因留在页面上，直到下一次操作或刷新成功。 */
sealed interface UsageAction {
    data object Idle : UsageAction
    data object Working : UsageAction
    data class Error(val message: String) : UsageAction
}

/** Users 页（owner 管理邀请码）的界面状态。 */
sealed interface UsersUiState {
    data object Loading : UsersUiState
    data class Error(val message: String) : UsersUiState
    data class Loaded(val invites: List<OwnerInvite>, val refreshFailed: Boolean = false) : UsersUiState
}

/** 编辑面板里「保存」的进度。 */
sealed interface InviteSaveState {
    data object Idle : InviteSaveState
    data object Saving : InviteSaveState
    data object Done : InviteSaveState
    data class Error(val message: String) : InviteSaveState
}

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

    private val _showUsage = MutableStateFlow(false)
    val showUsage = _showUsage.asStateFlow()

    private val _usage = MutableStateFlow<UsageUiState>(UsageUiState.Loading)
    val usage = _usage.asStateFlow()

    private val _showUsers = MutableStateFlow(false)
    val showUsers = _showUsers.asStateFlow()

    private val _users = MutableStateFlow<UsersUiState>(UsersUiState.Loading)
    val users = _users.asStateFlow()

    private val _saveState = MutableStateFlow<InviteSaveState>(InviteSaveState.Idle)
    val saveState = _saveState.asStateFlow()

    /** Users 列表是否展开：默认收起，本次使用期间记住（退出登录时复位）。 */
    private val _usersExpanded = MutableStateFlow(false)
    val usersExpanded = _usersExpanded.asStateFlow()

    /** Usage 页里展开了设备列表的用户（按 UsageGroup.key），刷新不会收起。 */
    private val _expandedUsage = MutableStateFlow<Set<String>>(emptySet())
    val expandedUsage = _expandedUsage.asStateFlow()

    private val _usageAction = MutableStateFlow<UsageAction>(UsageAction.Idle)
    val usageAction = _usageAction.asStateFlow()

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
    private var usageJob: Job? = null
    private var usersJob: Job? = null
    private var saveJob: Job? = null
    private var actionJob: Job? = null

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

    fun finishOnboarding() {
        launchSafe { safely("save onboarding flag") { store.setOnboarded() } }
    }

    fun activate(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || _activating.value) return
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _activationError.value = e.message ?: "Activation failed"
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
        usageJob?.cancel()
        usersJob?.cancel()
        saveJob?.cancel()
        actionJob?.cancel()
        _showUsage.value = false
        _usage.value = UsageUiState.Loading
        _showUsers.value = false
        _users.value = UsersUiState.Loading
        _saveState.value = InviteSaveState.Idle
        _usersExpanded.value = false
        _expandedUsage.value = emptySet()
        _usageAction.value = UsageAction.Idle
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

        // 不再是 owner：收起 Usage / Users 页
        if (!applied.profile.isOwner) {
            _showUsage.value = false
            _showUsers.value = false
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

    // ---- Owner：用量 ----------------------------------------------------

    fun openUsage() {
        if (profile.value?.isOwner != true) return
        _showUsage.value = true
        refreshUsage()
    }

    fun closeUsage() {
        _showUsage.value = false
    }

    fun refreshUsage() {
        if (usageJob?.isActive == true) return
        usageJob = launchSafe {
            val p = store.profile()
            if (p == null || !p.isOwner) return@launchSafe
            try {
                val resp = ApiClient.fetchOwnerDevices(p.token)
                if (resp.skipped > 0) Log.w(TAG, "usage: ${resp.skipped} device(s) could not be decoded")
                // 请求在途时设备可能已换账号：不把上一个账号的用量显示给新账号
                if (store.profile()?.profileId != p.profileId) return@launchSafe
                _usage.value = UsageUiState.Loaded(resp.period, groupByUser(resp.devices, inviteCode.value, p.ownerLabel))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = _usage.value
                _usage.value = if (current is UsageUiState.Loaded) {
                    current.copy(refreshFailed = true)
                } else {
                    UsageUiState.Error(e.message ?: "Failed to load usage")
                }
            }
        }
    }

    /** 展开 / 收起一个用户的设备列表（本次使用期间记住）。 */
    fun toggleUsageUser(key: String) {
        _expandedUsage.value = _expandedUsage.value.let { if (key in it) it - key else it + key }
    }

    fun dismissUsageActionError() {
        if (_usageAction.value is UsageAction.Error) _usageAction.value = UsageAction.Idle
    }

    /** 把一台设备登出：设备回到激活页，邀请码仍然有效。owner 自己的设备永远不会走到这里。 */
    fun logoutDevice(profileId: String) {
        if (actionJob?.isActive == true) return
        val groups = (_usage.value as? UsageUiState.Loaded)?.groups ?: return
        if (findLoggableDevice(groups, profileId) == null) return
        runOwnerAction { token ->
            ApiClient.revokeDevice(token, profileId)
            null
        }
    }

    /** 把一个用户的所有可用设备登出（接口还没上线时逐台回退）。owner 自己的邀请码永远不会走到这里。 */
    fun logoutAll(groupKey: String) {
        if (actionJob?.isActive == true) return
        val groups = (_usage.value as? UsageUiState.Loaded)?.groups ?: return
        val group = findLoggableGroup(groups, groupKey) ?: return
        runOwnerAction { token ->
            val api = object : OwnerLogoutApi {
                override suspend fun logoutUser(code: String): Int? = ApiClient.logoutUser(token, code)
                override suspend fun revokeDevice(profileId: String) = ApiClient.revokeDevice(token, profileId)
            }
            (logOutAll(api, group) as? LogoutAllResult.Failed)?.message
        }
    }

    /** 跑一个 owner 操作：成功（返回 null）或失败（返回原因）之后都刷新用量，让界面显示真实状态。 */
    private fun runOwnerAction(action: suspend (token: String) -> String?) {
        actionJob = launchSafe {
            _usageAction.value = UsageAction.Working
            try {
                val p = store.profile()
                if (p == null || !p.isOwner) {
                    _usageAction.value = UsageAction.Error("Not allowed.")
                    return@launchSafe
                }
                val failure = action(p.token)
                _usageAction.value = if (failure == null) UsageAction.Idle else UsageAction.Error(failure)
                usageJob?.cancel()
                refreshUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _usageAction.value = UsageAction.Error(logoutErrorText(e.code, e.reason))
                usageJob?.cancel()
                refreshUsage()
            } catch (e: Exception) {
                _usageAction.value = UsageAction.Error(e.message ?: "Could not log out.")
            }
        }
    }

    // ---- Owner：Users（每个邀请码的区域与使用时长）--------------------------------

    fun toggleUsersExpanded() {
        _usersExpanded.value = !_usersExpanded.value
    }

    fun openUsers() {
        if (profile.value?.isOwner != true) return
        _saveState.value = InviteSaveState.Idle
        _showUsers.value = true
        refreshUsers()
    }

    fun closeUsers() {
        _showUsers.value = false
        saveJob?.cancel()
        _saveState.value = InviteSaveState.Idle
    }

    fun refreshUsers() {
        if (usersJob?.isActive == true) return
        usersJob = launchSafe {
            val p = store.profile()
            if (p == null || !p.isOwner) return@launchSafe
            try {
                val resp = ApiClient.fetchOwnerInvites(p.token)
                if (resp.skipped > 0) Log.w(TAG, "users: ${resp.skipped} invite(s) could not be decoded")
                if (store.profile()?.profileId != p.profileId) return@launchSafe
                _users.value = UsersUiState.Loaded(sortInvites(resp.invites))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = _users.value
                _users.value = if (current is UsersUiState.Loaded) {
                    current.copy(refreshFailed = true)
                } else {
                    UsersUiState.Error(e.message ?: "Failed to load users")
                }
            }
        }
    }

    /** 保存编辑面板：只发改了的字段；失败时原因留在面板里（InviteSaveState.Error），成功后刷新列表。 */
    fun saveInvite(update: InviteUpdate) {
        if (saveJob?.isActive == true) return
        saveJob = launchSafe {
            _saveState.value = InviteSaveState.Saving
            try {
                val p = store.profile()
                if (p == null || !p.isOwner) {
                    _saveState.value = InviteSaveState.Error("Not allowed.")
                    return@launchSafe
                }
                ApiClient.updateInvite(p.token, update)
                _saveState.value = InviteSaveState.Done
                usersJob?.cancel()
                refreshUsers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _saveState.value = InviteSaveState.Error(inviteErrorText(e.code, e.reason))
            } catch (e: Exception) {
                _saveState.value = InviteSaveState.Error(e.message ?: "Save failed.")
            }
        }
    }

    fun resetSaveState() {
        _saveState.value = InviteSaveState.Idle
    }

    private companion object {
        const val EXPIRED_POLL_MS = 60_000L
        const val PROFILE_REFRESH_MIN_MS = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val RESTART_SETTLE_MS = 500L
        const val START_ACK_TIMEOUT_MS = 3_000L
    }
}
