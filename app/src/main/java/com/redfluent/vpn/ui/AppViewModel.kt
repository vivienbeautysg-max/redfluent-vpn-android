package com.redfluent.vpn.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redfluent.vpn.BuildConfig
import com.redfluent.vpn.api.ApiClient
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.ProfileStore
import com.redfluent.vpn.data.REGION_TOKYO
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.data.UsageGroup
import com.redfluent.vpn.data.UsagePeriod
import com.redfluent.vpn.data.groupByUser
import com.redfluent.vpn.data.resolveRegion
import com.redfluent.vpn.vpn.RedFluentVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** 界面的唯一状态源。所有显示都基于真实状态，不做乐观更新。 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    val profile: StateFlow<DeviceProfile?> =
        store.profileFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboarded: StateFlow<Boolean> =
        store.onboardedFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val inviteCode: StateFlow<String?> =
        store.lastInviteFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 用户选的区域（owner 才有得选；其他人永远是东京）。 */
    val selectedRegion: StateFlow<String> =
        store.selectedRegionFlow.stateIn(viewModelScope, SharingStarted.Eagerly, REGION_TOKYO)

    /** 隧道真实状态，直接来自服务。 */
    val tunnelState: StateFlow<TunnelState> = RedFluentVpnService.state
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

    private var lastProfileRefreshAt = 0L
    private var restartJob: Job? = null
    private var usageJob: Job? = null

    init {
        // 后端在 token 快过期时回发新 token，这里静默换掉。
        // 走 updateProfile（原子读改写），避免与资料刷新互相覆盖。
        ApiClient.onTokenRenewed = { newToken ->
            store.updateProfile { it.withToken(newToken) }
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch { store.setOnboarded() }
    }

    fun activate(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || _activating.value) return
        viewModelScope.launch {
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
                store.saveInviteCode(trimmed)
                // 激活响应里不一定带 owner / 区域信息，立刻再取一次 /profile。
                refreshProfile(force = true)
            } catch (e: Exception) {
                _activationError.value = e.message ?: "Activation failed"
            } finally {
                _activating.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _showUsage.value = false
            _usage.value = UsageUiState.Loading
            stopTunnel()
            store.clearProfile()
        }
    }

    /**
     * 从服务器刷新 isOwner / regions，并在已选区域不再被提供时静默退回东京。
     * 失败一律静默：保留本地已有的值，不打扰用户。
     */
    fun refreshProfile(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProfileRefreshAt < PROFILE_REFRESH_MIN_MS) return
        lastProfileRefreshAt = now
        viewModelScope.launch {
            val before = store.profile() ?: return@launch
            val status = try {
                ApiClient.fetchProfile(before.token)
            } catch (_: Exception) {
                return@launch
            }
            // 请求在途时设备可能已被换掉（退出后用别的邀请码重新激活）。
            // 只有 profileId 仍是发请求时那一个，才把结果写回——否则会把旧账号的 owner 标记写到新账号上。
            var applied = false
            store.updateProfile { current ->
                if (current.profileId == before.profileId) {
                    applied = true
                    current.copy(isOwner = status.isOwner, regions = status.regions)
                } else {
                    current
                }
            }
            if (!applied) return@launch
            store.reconcileRegion(status.regions)
            // 不再是 owner：收起 Usage 页
            if (!status.isOwner) _showUsage.value = false
        }
    }

    /** 点中央环：已连接就断开，否则连接。 */
    fun toggleTunnel() {
        when (tunnelState.value) {
            TunnelState.CONNECTED, TunnelState.CONNECTING -> stopTunnel()
            else -> connect()
        }
    }

    /**
     * 切换区域。没连着：只记住选择；已连着（或正在连）：先断开再用新区域重连。
     */
    fun selectRegion(id: String) {
        val p = profile.value ?: return
        if (p.regions.none { it.id == id }) return
        viewModelScope.launch {
            val restarting = restartJob?.isActive == true
            if (store.selectedRegion() == id && !restarting) return@launch
            store.setSelectedRegion(id)
            val running = tunnelState.value == TunnelState.CONNECTED ||
                tunnelState.value == TunnelState.CONNECTING
            if (running || restarting) {
                restartJob?.cancel()
                restartJob = launch { restartTunnel() }
            }
        }
    }

    private suspend fun restartTunnel() {
        stopTunnel()
        withTimeoutOrNull(RESTART_STOP_TIMEOUT_MS) {
            tunnelState.first { it == TunnelState.DISCONNECTED || it == TunnelState.ERROR }
        }
        delay(RESTART_SETTLE_MS)
        connect(skipPermissionCheck = true)
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
        viewModelScope.launch {
            val profile = store.profile() ?: return@launch
            // 选中的区域若已不被提供，这里同样退回东京——不会带着过期选择去请求
            val region = resolveRegion(store.selectedRegion(), profile.regions)
            store.setWantConnected(true)
            val runtime = try {
                ApiClient.fetchRuntimeConfig(profile.token, region)
            } catch (e: Exception) {
                if (region == REGION_TOKYO) {
                    // 东京的行为与 1.0 完全一致
                    _activationError.value = e.message
                } else {
                    // 非默认区域：绝不回退到东京或任何内置配置，把失败明明白白显示出来
                    val name = profile.regions.firstOrNull { it.id == region }?.label ?: region
                    RedFluentVpnService.reportFailure("$name: ${e.message ?: "Connection failed"}")
                }
                return@launch
            }
            val intent = Intent(app, RedFluentVpnService::class.java).apply {
                action = RedFluentVpnService.ACTION_START
                putExtra(RedFluentVpnService.EXTRA_CONFIG, runtime.configJson)
                putExtra(RedFluentVpnService.EXTRA_REGION_NAME, runtime.regionName)
            }
            app.startForegroundService(intent)
        }
    }

    private fun stopTunnel() {
        val app = getApplication<Application>()
        viewModelScope.launch { store.setWantConnected(false) }
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
        usageJob = viewModelScope.launch {
            val p = store.profile()
            if (p == null || !p.isOwner) return@launch
            try {
                val resp = ApiClient.fetchOwnerDevices(p.token)
                _usage.value = UsageUiState.Loaded(resp.period, groupByUser(resp.devices))
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

    private companion object {
        const val PROFILE_REFRESH_MIN_MS = 10_000L
        const val RESTART_STOP_TIMEOUT_MS = 5_000L
        const val RESTART_SETTLE_MS = 500L
    }
}
