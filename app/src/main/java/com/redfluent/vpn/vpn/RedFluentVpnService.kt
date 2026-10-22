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
import android.util.Log
import com.redfluent.vpn.MainActivity
import com.redfluent.vpn.R
import com.redfluent.vpn.data.TunnelState
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RedFluentVpnService :
    VpnService(),
    PlatformBridge,
    CommandServerHandler {

    companion object {
        const val ACTION_START = "com.redfluent.vpn.START"
        const val ACTION_STOP = "com.redfluent.vpn.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_REGION_ID = "region_id"
        const val EXTRA_REGION_NAME = "region_name"

        private const val TAG = "RfvVpnService"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1001

        // 隧道的真实状态。界面只反映它，绝不做乐观显示。
        private val _state = MutableStateFlow(TunnelState.DISCONNECTED)
        val state = _state.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        // 隧道真正连着的区域（服务器确认的 id 与显示名）；没连上时为 null。
        private val _activeRegion = MutableStateFlow<String?>(null)
        val activeRegion = _activeRegion.asStateFlow()

        private val _activeRegionId = MutableStateFlow<String?>(null)
        val activeRegionId = _activeRegionId.asStateFlow()

        /**
         * 连接失败（包括在服务起来之前、取配置阶段就失败）：界面看到 ERROR + 原因，
         * 而不是什么都没发生，更不会悄悄连到别的区域。
         */
        fun reportFailure(message: String) {
            _lastError.value = message
            _activeRegion.value = null
            _activeRegionId.value = null
            _state.value = TunnelState.ERROR
        }

        /** 新的连接尝试 / 切换区域时清掉上一次的失败，免得旧错误一直挂在界面上。 */
        fun clearFailure() {
            _lastError.value = null
            if (_state.value == TunnelState.ERROR) _state.value = TunnelState.DISCONNECTED
        }
    }

    private enum class Phase { IDLE, STARTING, RUNNING }

    override val androidContext: Context get() = this

    // ---- 生命周期串行化 ----------------------------------------------------
    // 所有「起隧道 / 拆隧道」都在这一条线程上按提交顺序执行；
    // generation 在每次接受 START 和每次 STOP 时 +1。一个起隧道任务做完后必须确认
    // generation 仍是自己那一代，才允许发布 CONNECTED；否则说明期间来过 STOP，
    // 它要把自己刚创建出来的东西拆掉，由排在后面的 teardown 发布 DISCONNECTED。
    private val lock = Any()
    private var generation = 0L // 受 lock 保护
    private var phase = Phase.IDLE // 受 lock 保护
    private val lifecycle: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rfv-vpn-lifecycle") }

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var tearingDown = false

    @Volatile
    private var notificationText = "Connecting"

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    // ---- 服务生命周期 -------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            else -> handleStart(intent)
        }
        // 故意不用 START_STICKY：系统自动重启会造出「服务活着但隧道已死」的假绿状态。
        // 宁可明确断开，也不谎报连接。恢复交给 BootReceiver 与用户主动重连。
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent?) {
        // startForegroundService() 的契约：每一次都必须在几秒内调用 startForeground()，
        // 哪怕这次 START 最终被忽略。
        startForegroundSafely()

        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) {
            // 已经有隧道在起 / 在跑时，别因为一条残缺的指令把它拆掉
            if (synchronized(lock) { phase == Phase.IDLE }) {
                reportFailure("Missing tunnel configuration")
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        val regionId = intent.getStringExtra(EXTRA_REGION_ID)
        val regionName = intent.getStringExtra(EXTRA_REGION_NAME)

        val gen = synchronized(lock) {
            if (phase != Phase.IDLE) {
                null
            } else {
                phase = Phase.STARTING
                ++generation
            }
        }
        if (gen == null) {
            // 重复的 START：已经在起 / 在跑。换区域靠「先 STOP 再 START」，不靠重复 START。
            Log.w(TAG, "ignored START: a tunnel is already starting or running")
            return
        }
        runLifecycle { startTunnel(config, regionId, regionName, gen) }
    }

    private fun runLifecycle(task: () -> Unit) {
        try {
            lifecycle.execute {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e(TAG, "lifecycle task failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // 服务已经在销毁，不再接新任务
        }
    }

    /** 运行在 lifecycle 线程。 */
    private fun startTunnel(rawConfig: String, regionId: String?, regionName: String?, gen: Long) {
        synchronized(lock) {
            if (generation != gen) return // 排队期间已经来过 STOP
            notificationText = "Connecting"
            _lastError.value = null
            _state.value = TunnelState.CONNECTING
        }

        var server: CommandServer? = null
        try {
            val configJson = withEngineLog(rawConfig)
            server = CommandServer(this, this)
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
        } catch (e: Exception) {
            // 起到一半失败：本地的 server 和可能已经建好的 tun 都要关掉（旧版在这里会漏掉它们）
            discard(server)
            synchronized(lock) {
                if (generation == gen) {
                    phase = Phase.IDLE
                    reportFailure(e.message ?: "Failed to start tunnel")
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            return
        }

        val stale = synchronized(lock) {
            if (generation != gen) {
                true
            } else {
                commandServer = server
                phase = Phase.RUNNING
                _activeRegionId.value = regionId
                _activeRegion.value = regionName
                _state.value = TunnelState.CONNECTED
                false
            }
        }
        if (stale) {
            // 引擎启动期间收到了 STOP：把刚创建的东西拆掉，不发布 CONNECTED
            discard(server)
            return
        }
        updateNotification("Connected")
    }

    /** 拆掉「这次起隧道任务自己创建的」server 和 tun（关的时候引擎可能回调 serviceStop，用 tearingDown 挡住）。 */
    private fun discard(server: CommandServer?) {
        tearingDown = true
        try {
            closeQuietly(server)
            closeTun()
        } finally {
            tearingDown = false
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

    /** 主线程：登记「要停」，立刻让界面看到 STOPPING，真正的拆除排在 lifecycle 线程上。 */
    private fun requestStop() {
        val gen = synchronized(lock) {
            phase = Phase.IDLE
            ++generation
        }
        _activeRegion.value = null
        _activeRegionId.value = null
        _state.value = TunnelState.STOPPING
        runLifecycle { teardown(gen) }
    }

    /** 运行在 lifecycle 线程，因此一定排在任何正在进行的起隧道任务之后。 */
    private fun teardown(gen: Long) {
        tearingDown = true
        try {
            closeQuietly(commandServer)
            commandServer = null
            closeTun()
        } finally {
            tearingDown = false
        }
        synchronized(lock) {
            // 其后又接受了新的 START（或又来了 STOP）：状态由它接管，这里不动状态、不 stopSelf
            if (generation != gen) return
            _state.value = TunnelState.DISCONNECTED
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeQuietly(server: CommandServer?) {
        if (server == null) return
        runCatching { server.closeService() }
        runCatching { server.close() }
    }

    private fun closeTun() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    override fun onRevoke() {
        // 用户在系统设置里撤销了授权，或另一个 VPN 抢占了隧道
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        synchronized(lock) {
            generation++
            phase = Phase.IDLE
        }
        // 已提交的任务（包括排队中的 teardown）会跑完，之后不再接收新任务
        lifecycle.shutdown()
        tearingDown = true // 引擎关闭时可能回调 serviceStop，此时已无人接收新任务
        closeQuietly(commandServer)
        commandServer = null
        closeTun()
        DefaultNetworkMonitor.stop(this)
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
        // 我们自己在拆的时候引擎也可能回调到这里，忽略，免得重复排队
        if (!tearingDown) requestStop()
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
        val notification = buildNotification(notificationText)
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
        notificationText = text
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
