package com.redfluent.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.redfluent.vpn.MainActivity
import com.redfluent.vpn.R
import com.redfluent.vpn.data.TunnelState
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RedFluentVpnService :
    VpnService(),
    PlatformBridge,
    CommandServerHandler {

    companion object {
        const val ACTION_START = "com.redfluent.vpn.START"
        const val ACTION_STOP = "com.redfluent.vpn.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_REGION_NAME = "region_name"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1001

        // 隧道的真实状态。界面只反映它，绝不做乐观显示。
        private val _state = MutableStateFlow(TunnelState.DISCONNECTED)
        val state = _state.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        // 隧道真正连着的区域名（服务器确认的）；没连上时为 null。
        private val _activeRegion = MutableStateFlow<String?>(null)
        val activeRegion = _activeRegion.asStateFlow()

        /**
         * 连接在拿到配置之前就失败了（例如非默认区域取配置失败）。
         * 服务还没起，所以由调用方通过这里把失败显示出来——界面看到 ERROR + 原因，
         * 而不是什么都没发生，更不会悄悄连到别的区域。
         */
        fun reportFailure(message: String) {
            _lastError.value = message
            _activeRegion.value = null
            _state.value = TunnelState.ERROR
        }
    }

    override val androidContext: Context get() = this

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    // ---- 服务生命周期 -------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                val config = intent?.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    fail("Missing tunnel configuration")
                    return START_NOT_STICKY
                }
                val regionName = intent.getStringExtra(EXTRA_REGION_NAME)
                startForegroundSafely()
                scope.launch { startTunnel(config, regionName) }
            }
        }
        // 故意不用 START_STICKY：系统自动重启会造出「服务活着但隧道已死」的假绿状态。
        // 宁可明确断开，也不谎报连接。恢复交给 BootReceiver 与用户主动重连。
        return START_NOT_STICKY
    }

    private fun startTunnel(rawConfig: String, regionName: String?) {
        _state.value = TunnelState.CONNECTING
        _lastError.value = null
        try {
            val configJson = withEngineLog(rawConfig)
            val server = CommandServer(this, this)
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            commandServer = server
            _activeRegion.value = regionName
            _state.value = TunnelState.CONNECTED
            updateNotification("Connected")
        } catch (e: Exception) {
            fail(e.message ?: "Failed to start tunnel")
        }
    }

    /**
     * 把引擎日志落到 app 私有目录，便于排查路由决策。
     * 引擎默认只写 stdout，在 Android 上等于丢弃。
     */
    private fun withEngineLog(raw: String): String = runCatching {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val logPath = java.io.File(filesDir, "box.log").absolutePath
        kotlinx.serialization.json.buildJsonObject {
            obj.forEach { (k, v) -> if (k != "log") put(k, v) }
            put(
                "log",
                kotlinx.serialization.json.buildJsonObject {
                    put("level", kotlinx.serialization.json.JsonPrimitive("info"))
                    put("timestamp", kotlinx.serialization.json.JsonPrimitive(true))
                    put("output", kotlinx.serialization.json.JsonPrimitive(logPath))
                },
            )
        }.toString()
    }.getOrDefault(raw)

    private fun stopTunnel() {
        _state.value = TunnelState.STOPPING
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        _activeRegion.value = null
        _state.value = TunnelState.DISCONNECTED
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        _lastError.value = message
        _activeRegion.value = null
        _state.value = TunnelState.ERROR
        runCatching { commandServer?.close() }
        commandServer = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onRevoke() {
        // 用户在系统设置里撤销了授权，或另一个 VPN 抢占了隧道
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        DefaultNetworkMonitor.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    // ---- 引擎向我们索取隧道 --------------------------------------------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission not granted")

        val builder = Builder()
            .setSession("RedFluent VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val a = v4.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val a = v6.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }

            val r4 = options.inet4RouteAddress
            if (r4.hasNext()) {
                while (r4.hasNext()) {
                    val p = r4.next()
                    builder.addRoute(p.address(), p.prefix())
                }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            val r6 = options.inet6RouteAddress
            while (r6.hasNext()) {
                val p = r6.next()
                builder.addRoute(p.address(), p.prefix())
            }

            // 本 app 自身流量不进隧道，避免控制面回环
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        val pfd = builder.establish() ?: error("Failed to establish tunnel: not prepared or revoked")
        tunFd = pfd
        return pfd.fd
    }

    // ---- CommandServerHandler ------------------------------------------

    override fun serviceReload() = Unit

    override fun serviceStop() {
        stopTunnel()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String) = Unit

    // ---- 前台通知（对抗杀后台的第一层）----------------------------------

    private fun startForegroundSafely() {
        ensureChannel()
        val notification = buildNotification("Connecting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                // 低优先级：通知划不掉（保活），但不发声不打扰
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("RedFluent VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
