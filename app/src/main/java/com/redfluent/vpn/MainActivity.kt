package com.redfluent.vpn

import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.redfluent.vpn.ui.AppViewModel
import com.redfluent.vpn.ui.screens.ActivationScreen
import com.redfluent.vpn.ui.screens.DashboardScreen
import com.redfluent.vpn.ui.screens.OnboardingScreen
import com.redfluent.vpn.ui.theme.RedFluentTheme

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

                    LaunchedEffect(needsPermission) {
                        if (needsPermission) {
                            VpnService.prepare(this@MainActivity)?.let { vpnPermission.launch(it) }
                                ?: viewModel.onVpnPermissionGranted()
                        }
                    }

                    when {
                        !onboarded -> OnboardingScreen(onDone = viewModel::finishOnboarding)
                        profile == null -> ActivationScreen(
                            activating = activating,
                            error = activationError,
                            onActivate = viewModel::activate,
                        )
                        else -> DashboardScreen(
                            profile = profile,
                            state = state,
                            error = tunnelError,
                            inviteCode = inviteCode,
                            onToggle = viewModel::toggleTunnel,
                            onSignOut = viewModel::signOut,
                        )
                    }
                }
            }
        }
    }
}
