package com.redfluent.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.redfluent.vpn.debug.DebugHooks
import com.redfluent.vpn.ui.AppViewModel
import com.redfluent.vpn.ui.screens.ActivationScreen
import com.redfluent.vpn.ui.screens.DashboardScreen
import com.redfluent.vpn.ui.screens.OnboardingScreen
import com.redfluent.vpn.ui.screens.UsageScreen
import com.redfluent.vpn.ui.screens.UsersScreen
import com.redfluent.vpn.ui.theme.RedFluentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 系统 VPN 授权对话框——只有用户本人能点同意。 */
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不阻断：通知只影响保活强度，不影响能否连接 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 仅 debug 变体：测试用 token 注入。release 里 BuildConfig.DEBUG 恒为 false，
        // 且 release 的 DebugHooks 是空实现，整段会被 R8 删掉。
        if (BuildConfig.DEBUG) {
            lifecycleScope.launch { DebugHooks.onActivityCreate(applicationContext, intent) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            RedFluentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val onboarded by viewModel.onboarded.collectAsState()
                    val profile by viewModel.profile.collectAsState()
                    val state by viewModel.tunnelState.collectAsState()
                    val tunnelError by viewModel.tunnelError.collectAsState()
                    val activating by viewModel.activating.collectAsState()
                    val activationError by viewModel.activationError.collectAsState()
                    val inviteCode by viewModel.inviteCode.collectAsState()
                    val needsPermission by viewModel.needsVpnPermission.collectAsState()
                    val selectedRegion by viewModel.selectedRegion.collectAsState()
                    val activeRegion by viewModel.activeRegion.collectAsState()
                    val showUsage by viewModel.showUsage.collectAsState()
                    val usage by viewModel.usage.collectAsState()
                    val showUsers by viewModel.showUsers.collectAsState()
                    val users by viewModel.users.collectAsState()
                    val saveState by viewModel.saveState.collectAsState()
                    val accessNotice by viewModel.accessNotice.collectAsState()

                    LaunchedEffect(needsPermission) {
                        if (needsPermission) {
                            VpnService.prepare(this@MainActivity)?.let { vpnPermission.launch(it) }
                                ?: viewModel.onVpnPermissionGranted()
                        }
                    }

                    val usageVisible = showUsage && profile?.isOwner == true
                    val usersVisible = showUsers && profile?.isOwner == true

                    // 系统返回手势 / 返回键：从 Usage / Users 回到主页（Users 的编辑面板打开时，返回先关面板）
                    BackHandler(enabled = usageVisible) { viewModel.closeUsage() }
                    BackHandler(enabled = usersVisible) { viewModel.closeUsers() }

                    when {
                        !onboarded -> OnboardingScreen(onDone = viewModel::finishOnboarding)
                        profile == null -> ActivationScreen(
                            activating = activating,
                            error = activationError,
                            onActivate = viewModel::activate,
                        )
                        usageVisible -> UsageScreen(
                            state = usage,
                            onBack = viewModel::closeUsage,
                            onRefresh = viewModel::refreshUsage,
                        )
                        usersVisible -> UsersScreen(
                            state = users,
                            ownerRegions = profile?.regions.orEmpty(),
                            ownerCode = inviteCode,
                            ownerLabel = profile?.ownerLabel,
                            saveState = saveState,
                            onBack = viewModel::closeUsers,
                            onRefresh = viewModel::refreshUsers,
                            onSave = viewModel::saveInvite,
                            onSaveHandled = viewModel::resetSaveState,
                        )
                        else -> DashboardScreen(
                            profile = profile,
                            state = state,
                            error = tunnelError,
                            inviteCode = inviteCode,
                            selectedRegion = selectedRegion,
                            activeRegion = activeRegion,
                            onToggle = viewModel::toggleTunnel,
                            onSignOut = viewModel::signOut,
                            onSelectRegion = viewModel::selectRegion,
                            onOpenUsage = viewModel::openUsage,
                            onOpenUsers = viewModel::openUsers,
                            accessNotice = accessNotice,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 每次回到前台：刷新 isOwner / regions（内部有节流）
        viewModel.refreshProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) {
            setIntent(intent)
            lifecycleScope.launch { DebugHooks.onActivityCreate(applicationContext, intent) }
        }
    }
}
