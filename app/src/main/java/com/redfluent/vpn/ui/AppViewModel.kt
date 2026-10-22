package com.redfluent.vpn.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redfluent.vpn.BuildConfig
import com.redfluent.vpn.api.ApiClient
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.ProfileStore
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.vpn.RedFluentVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 界面的唯一状态源。所有显示都基于真实状态，不做乐观更新。 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    val profile: StateFlow<DeviceProfile?> =
        store.profileFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboarded: StateFlow<Boolean> =
        store.onboardedFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val inviteCode: StateFlow<String?> =
        store.lastInviteFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 隧道真实状态，直接来自服务。 */
    val tunnelState: StateFlow<TunnelState> = RedFluentVpnService.state
    val tunnelError: StateFlow<String?> = RedFluentVpnService.lastError

    private val _activating = MutableStateFlow(false)
    val activating = _activating.asStateFlow()

    private val _activationError = MutableStateFlow<String?>(null)
    val activationError = _activationError.asStateFlow()

    /** 需要向系统申请 VPN 授权时置位，由界面弹出系统对话框。 */
    private val _needsVpnPermission = MutableStateFlow(false)
    val needsVpnPermission = _needsVpnPermission.asStateFlow()

    init {
        // 后端在 token 快过期时回发新 token，这里静默换掉
        ApiClient.onTokenRenewed = { newToken ->
            store.profile()?.let { store.saveProfile(it.withToken(newToken)) }
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
            } catch (e: Exception) {
                _activationError.value = e.message ?: "Activation failed"
            } finally {
                _activating.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            stopTunnel()
            store.clearProfile()
        }
    }

    /** 点中央环：已连接就断开，否则连接。 */
    fun toggleTunnel() {
        when (tunnelState.value) {
            TunnelState.CONNECTED, TunnelState.CONNECTING -> stopTunnel()
            else -> connect()
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
        viewModelScope.launch {
            val token = store.profile()?.token ?: return@launch
            store.setWantConnected(true)
            val config = try {
                ApiClient.fetchRuntimeConfig(token)
            } catch (e: Exception) {
                _activationError.value = e.message
                return@launch
            }
            val intent = Intent(app, RedFluentVpnService::class.java).apply {
                action = RedFluentVpnService.ACTION_START
                putExtra(RedFluentVpnService.EXTRA_CONFIG, config)
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
}
