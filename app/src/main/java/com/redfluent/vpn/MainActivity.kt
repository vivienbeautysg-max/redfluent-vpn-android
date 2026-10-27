package com.redfluent.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.redfluent.vpn.data.Appearance
import com.redfluent.vpn.data.ProfileStore
import com.redfluent.vpn.data.SIGNED_OUT_NOTICE
import com.redfluent.vpn.debug.DebugHooks
import com.redfluent.vpn.debug.DemoMode
import com.redfluent.vpn.ui.AdminUiState
import com.redfluent.vpn.ui.AppFrame
import com.redfluent.vpn.ui.AppViewModel
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
import com.redfluent.vpn.ui.theme.DarkRf
import com.redfluent.vpn.ui.theme.LightRf
import com.redfluent.vpn.ui.theme.RedFluentTheme
import com.redfluent.vpn.ui.theme.Rf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 截图 / 动效演示模式（只有 debug 包里有；release 里 DemoMode 永远返回 null）。 */
    private var demo: (@Composable () -> Unit)? = null

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

        // 第一帧之前读好明暗（最多等 300ms），避免先白后黑闪一下
        val store = ProfileStore(applicationContext)
        val initial = runBlocking { withTimeoutOrNull(300L) { store.appearanceFlow.first() } } ?: Appearance.LIGHT
        applySystemBars(initial)

        demo = if (BuildConfig.DEBUG) DemoMode.content(this, intent) else null

        // 仅 debug 变体：测试用 token 注入。release 里 BuildConfig.DEBUG 恒为 false，
        // 且 release 的 DebugHooks 是空实现，整段会被 R8 删掉。演示模式不碰真实数据。
        if (BuildConfig.DEBUG && demo == null) {
            lifecycleScope.launch { DebugHooks.onActivityCreate(applicationContext, intent) }
        }

        if (demo == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val demoContent = demo
        setContent {
            if (demoContent != null) {
                demoContent()
            } else {
                val appearance by remember { store.appearanceFlow }.collectAsState(initial)
                LaunchedEffect(appearance) { applySystemBars(appearance) }
                RedFluentTheme(appearance) { RealApp(viewModel, appearance) }
            }
        }
    }

    /** 状态栏 / 导航栏图标颜色跟着 app 内的明暗（不跟系统）；窗口底色也跟着，转场间隙不露别的颜色。 */
    fun applySystemBars(appearance: Appearance) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (appearance == Appearance.DARK) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        val paper = if (appearance == Appearance.DARK) DarkRf.paper else LightRf.paper
        window.setBackgroundDrawable(ColorDrawable(paper.toArgb()))
    }

    @Composable
    private fun RealApp(vm: AppViewModel, appearance: Appearance) {
        val loaded by vm.loaded.collectAsState()
        val onboarded by vm.onboarded.collectAsState()
        val profile by vm.profile.collectAsState()
        val state by vm.tunnelState.collectAsState()
        val tunnelError by vm.tunnelError.collectAsState()
        val activating by vm.activating.collectAsState()
        val activationError by vm.activationError.collectAsState()
        val inviteCode by vm.inviteCode.collectAsState()
        val needsPermission by vm.needsVpnPermission.collectAsState()
        val selectedRegion by vm.selectedRegion.collectAsState()
        val activeRegion by vm.activeRegion.collectAsState()
        val regionInUse by vm.regionInUse.collectAsState()
        val accessNotice by vm.accessNotice.collectAsState()
        val signedOutNotice by vm.signedOutNotice.collectAsState()
        val ipv6Hint by vm.ipv6Hint.collectAsState()
        val quota by vm.quota.collectAsState()
        val stats by vm.stats.collectAsState()
        val connectedSince by vm.connectedSince.collectAsState()
        val showAdmin by vm.showAdmin.collectAsState()
        val route by vm.adminRoute.collectAsState()
        val admin by vm.admin.collectAsState()
        val feedback by vm.feedback.collectAsState()
        val restore by vm.restore.collectAsState()

        LaunchedEffect(needsPermission) {
            if (needsPermission) {
                VpnService.prepare(this@MainActivity)?.let { vpnPermission.launch(it) }
                    ?: vm.onVpnPermissionGranted()
            }
        }

        if (loaded.not()) {
            Box(Modifier.fillMaxSize().background(Rf.colors.paper))
            return
        }

        val isOwner = profile?.isOwner == true
        val screen = screenOf(onboarded, profile != null, showAdmin && isOwner, route)
        val data = (admin as? AdminUiState.Loaded)?.data
        val people = data?.people(profile?.profileId, inviteCode, profile?.ownerLabel).orEmpty()
        val adminUi = AdminUi(
            state = admin,
            people = people,
            period = data?.period,
            deleted = data?.deleted.orEmpty(),
            deletedSupported = data?.deletedSupported == true,
            deletedError = data?.deletedError,
            feedback = feedback,
            restore = restore,
            ownerRegions = profile?.regions.orEmpty(),
            nowMs = System.currentTimeMillis(),
        )
        val adminActions = remember(vm) {
            AdminActions(
                onClose = vm::closeAdmin,
                onBack = vm::adminBack,
                onRefresh = vm::refreshAdmin,
                onOpenPerson = vm::openPerson,
                onOpenDeleted = vm::openDeleted,
                onSave = vm::savePerson,
                onDelete = vm::deletePerson,
                onLogoutAll = vm::logoutAll,
                onLogoutDevice = vm::logoutDevice,
                onRename = vm::renameDevice,
                onRestore = vm::restore,
            )
        }
        val homeActions = remember(vm) {
            HomeActions(
                onToggle = vm::toggleTunnel,
                onSelectRegion = vm::selectRegion,
                onOpenAdmin = vm::openAdmin,
                onAppearance = vm::setAppearance,
                onRefreshQuota = vm::refreshQuota,
                onSignOut = { vm.signOut() },
            )
        }

        AppFrame(screen = screen, onBack = vm::adminBack, banner = feedback.banner, onDismissBanner = vm::dismissBanner) { s ->
            when (s) {
                Screen.Onboarding -> OnboardingScreen(onDone = vm::finishOnboarding)
                Screen.Activation -> ActivationScreen(
                    activating = activating,
                    error = activationError,
                    onActivate = vm::activate,
                    notice = if (signedOutNotice) SIGNED_OUT_NOTICE else null,
                )
                Screen.Home -> DashboardScreen(
                    HomeUi(
                        profile = profile,
                        state = state,
                        error = tunnelError,
                        inviteCode = inviteCode,
                        selectedRegion = selectedRegion,
                        regionInUse = regionInUse,
                        activeRegion = activeRegion,
                        accessNotice = accessNotice,
                        ipv6Hint = ipv6Hint,
                        quota = quota,
                        stats = stats,
                        connectedSince = connectedSince,
                        appearance = appearance,
                    ),
                    homeActions,
                )
                Screen.People -> PeopleScreen(adminUi, adminActions)
                is Screen.Person -> PersonScreen(people.firstOrNull { it.code == s.code }, adminUi, adminActions)
                Screen.Deleted -> DeletedScreen(adminUi, adminActions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (demo != null) return
        // 每次回到前台：刷新 isOwner / regions（内部有节流）、盯 Wi-Fi、取用量
        viewModel.refreshProfile()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        if (demo != null) return
        viewModel.setForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && demo == null) {
            setIntent(intent)
            lifecycleScope.launch { DebugHooks.onActivityCreate(applicationContext, intent) }
        }
    }
}
