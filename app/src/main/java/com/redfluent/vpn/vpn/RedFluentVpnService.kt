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
import android.os.SystemClock
import android.util.Log
import com.redfluent.vpn.BuildConfig
import com.redfluent.vpn.MainActivity
import com.redfluent.vpn.R
import com.redfluent.vpn.api.ApiClient
import com.redfluent.vpn.api.ApiException
import com.redfluent.vpn.api.RuntimeConfig
import com.redfluent.vpn.data.FOLLOW_INTERVAL_MS
import com.redfluent.vpn.data.FOLLOW_MIN_RECHECK_MS
import com.redfluent.vpn.data.FOLLOW_NETWORK_DEBOUNCE_MS
import com.redfluent.vpn.data.FollowAction
import com.redfluent.vpn.data.FollowOutcome
import com.redfluent.vpn.data.ProfileStore
import com.redfluent.vpn.data.REGION_TOKYO
import com.redfluent.vpn.data.StopKind
import com.redfluent.vpn.data.StopReason
import com.redfluent.vpn.data.TunnelState
import com.redfluent.vpn.data.configChanged
import com.redfluent.vpn.data.decideFollow
import com.redfluent.vpn.data.runtimeRegionParam
import com.redfluent.vpn.debug.DebugHooks
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

        private fun setState(next: TunnelState) {
            val prev = _state.value
            _state.value = next
            // 1.3：主页"已连接多久"的只读值。只在进入 CONNECTED 时记一次（热重载不经过别的状态，不会重置）。
            if (next == TunnelState.CONNECTED && prev != TunnelState.CONNECTED) {
                _connectedSince.value = android.os.SystemClock.elapsedRealtime()
            } else if (next != TunnelState.CONNECTED) {
                _connectedSince.value = null
            }
            if (prev != next) Log.i(TAG, "state $prev -> $next")
        }

        /** 1.3：进入 CONNECTED 的时刻（elapsedRealtime 毫秒）；没连着为 null。界面只读。 */
        private val _connectedSince = MutableStateFlow<Long?>(null)
        val connectedSince = _connectedSince.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        // 隧道真正连着的区域（服务器确认的 id 与显示名）；没连上或服务器没回显时为 null。
        private val _activeRegion = MutableStateFlow<String?>(null)
        val activeRegion = _activeRegion.asStateFlow()

        private val _activeRegionId = MutableStateFlow<String?>(null)
        val activeRegionId = _activeRegionId.asStateFlow()

        // 1.2："无感"跟随发现账号到期 / 被吊销而叫停隧道的原因；界面据此显示 Expired / Revoked。
        private val _stopReason = MutableStateFlow<StopReason?>(null)
        val stopReason = _stopReason.asStateFlow()

        fun clearStopReason() {
            _stopReason.value = null
        }

        /** 连接时（隧道还没起）就被服务器告知账号到期 / 被吊销：同样记成叫停原因，界面显示 Expired / Revoked。 */
        fun reportAccessEnded(kind: StopKind, expiresAt: String?) {
            _stopReason.value = StopReason(kind, expiresAt)
        }

        /**
         * 连接失败（包括在服务起来之前、取配置阶段就失败）：界面看到 ERROR + 原因，
         * 而不是什么都没发生，更不会悄悄连到别的区域。
         */
        fun reportFailure(message: String) {
            _lastError.value = message
            _activeRegion.value = null
            _activeRegionId.value = null
            setState(TunnelState.ERROR)
        }

        /** 新的连接尝试 / 切换区域时清掉上一次的失败，免得旧错误一直挂在界面上。 */
        fun clearFailure() {
            _lastError.value = null
            if (_state.value == TunnelState.ERROR) setState(TunnelState.DISCONNECTED)
        }
    }

    private enum class Phase { IDLE, STARTING, RUNNING }

    override val androidContext: Context get() = this

    // ---- 生命周期串行化 ----------------------------------------------------
    // 所有「起隧道 / 重载 / 拆隧道」都在这一条线程上按提交顺序执行；
    // generation 在每次接受 START 和每次 STOP 时 +1。一个任务做完后必须确认
    // generation 仍是自己那一代，才允许发布结果；否则说明期间来过 STOP，
    // 它要把自己刚创建出来的东西拆掉，由排在后面的 teardown 发布 DISCONNECTED。
    private val lock = Any()
    private var generation = 0L // 受 lock 保护
    private var phase = Phase.IDLE // 受 lock 保护
    private val lifecycle: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rfv-vpn-lifecycle") }

    // 1.2 "无感"跟随：全服务只有这一个协程；停隧道 / 销毁服务时取消
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var followJob: Job? = null // 受 lock 保护
    private val netSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var tearingDown = false

    @Volatile
    private var notificationText = "Connecting"

    /** 引擎正在跑的那份配置（已加好日志设置）；重载失败时用它恢复。 */
    @Volatile
    private var lastGoodConfig: String? = null

    /** 重载失败（已退回 lastGoodConfig）的那份配置：服务器还给同一份时不再每 5 分钟重试。 */
    @Volatile
    private var rejectedConfig: String? = null

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
        _stopReason.value = null // 用户重新发起连接：上一次的到期 / 吊销提示作废
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
            setState(TunnelState.CONNECTING)
        }

        var server: CommandServer? = null
        // 每次起隧道从空日志开始：引擎以 O_APPEND 打开日志、从不截断（sing-box log/observable.go:64）
        engineLogFile().delete()
        val configJson = withEngineLog(rawConfig)
        try {
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
                lastGoodConfig = configJson
                phase = Phase.RUNNING
                _activeRegionId.value = regionId?.takeIf { it.isNotBlank() }
                _activeRegion.value = regionName?.takeIf { it.isNotBlank() }
                setState(TunnelState.CONNECTED)
                false
            }
        }
        if (stale) {
            // 引擎启动期间收到了 STOP：把刚创建的东西拆掉，不发布 CONNECTED
            discard(server)
            return
        }
        updateNotification("Connected")
        startFollow(gen)
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

    private fun engineLogFile() = java.io.File(filesDir, "box.log")

    private fun withEngineLog(raw: String): String =
        engineLogConfig(raw, engineLogFile().absolutePath, debug = BuildConfig.DEBUG)

    /** 主线程：登记「要停」，立刻让界面看到 STOPPING，真正的拆除排在 lifecycle 线程上。 */
    private fun requestStop() {
        stopFollow()
        val gen = synchronized(lock) {
            phase = Phase.IDLE
            ++generation
        }
        _activeRegion.value = null
        _activeRegionId.value = null
        setState(TunnelState.STOPPING)
        runLifecycle { teardown(gen) }
    }

    /** 运行在 lifecycle 线程，因此一定排在任何正在进行的起隧道 / 重载任务之后。 */
    private fun teardown(gen: Long) {
        tearingDown = true
        try {
            closeQuietly(commandServer)
            commandServer = null
            lastGoodConfig = null
            rejectedConfig = null
            closeTun()
        } finally {
            tearingDown = false
        }
        synchronized(lock) {
            // 其后又接受了新的 START（或又来了 STOP）：状态由它接管，这里不动状态、不 stopSelf
            if (generation != gen) return
            setState(TunnelState.DISCONNECTED)
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
        stopFollow()
        serviceScope.cancel()
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

    // ---- 1.2 "无感"跟随 ---------------------------------------------------
    // 隧道在跑期间：每 5 分钟、以及底层网络变化（合并抖动）后，重新取一次运行配置，规则与连接时相同
    // （owner 用自己选的区域，普通用户不带 region 参数、由服务器决定）。
    //  - 服务器给的区域和正在用的不同 → 用新配置热重载引擎（见 reloadTunnel），不经过任何「已断开」状态；
    //  - 403 "invite expired" / 401 / 其它 403（被吊销）→ 停隧道，界面显示 Expired / Revoked；
    //  - 网络错误、5xx、区域类错误 → 保持运行，下个周期再试。

    private fun startFollow(gen: Long) {
        val interval = if (BuildConfig.DEBUG) DebugHooks.followIntervalMs(this) ?: FOLLOW_INTERVAL_MS else FOLLOW_INTERVAL_MS
        DefaultNetworkMonitor.onNetworkChanged = { netSignal.trySend(Unit) }
        val job = serviceScope.launch { followLoop(gen, interval) }
        synchronized(lock) {
            followJob?.cancel()
            followJob = job
        }
    }

    private fun stopFollow() {
        DefaultNetworkMonitor.onNetworkChanged = null
        synchronized(lock) {
            followJob?.cancel()
            followJob = null
        }
        netSignal.tryReceive()
    }

    private fun isCurrentRunning(gen: Long): Boolean =
        synchronized(lock) { generation == gen && phase == Phase.RUNNING }

    private suspend fun followLoop(gen: Long, intervalMs: Long) {
        var lastCheck = SystemClock.elapsedRealtime()
        val minRecheck = FOLLOW_MIN_RECHECK_MS.coerceAtMost(intervalMs)
        while (currentCoroutineContext().isActive) {
            val byNetwork = withTimeoutOrNull(intervalMs) {
                netSignal.receive()
                true
            } ?: false
            if (byNetwork) {
                delay(FOLLOW_NETWORK_DEBOUNCE_MS) // 合并连续的网络变化事件
                netSignal.tryReceive()
                if (SystemClock.elapsedRealtime() - lastCheck < minRecheck) continue
            }
            if (!isCurrentRunning(gen)) return
            lastCheck = SystemClock.elapsedRealtime()
            followOnce(gen, if (byNetwork) "network" else "timer")
        }
    }

    private suspend fun followOnce(gen: Long, trigger: String) {
        val store = ProfileStore(applicationContext)
        val profile = try {
            store.profile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (profile == null) return // 已退出登录（或读不到）：由界面拆隧道，这里无事可做
        val selected = try {
            store.selectedRegion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            REGION_TOKYO
        }
        val param = runtimeRegionParam(profile.isOwner, selected, profile.regions)

        var runtime: RuntimeConfig? = null
        val outcome: FollowOutcome = try {
            val r = ApiClient.fetchRuntimeConfig(profile.token, param)
            runtime = r
            val fetched = withEngineLog(r.configJson)
            FollowOutcome.Fetched(
                r.regionId,
                configChanged = configChanged(fetched, lastGoodConfig),
                rejected = fetched == rejectedConfig,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            FollowOutcome.Failed(e.code, e.reason, e.expiresAt)
        } catch (e: Exception) {
            FollowOutcome.Failed(-1, "error")
        }
        // 1.3：记下最新的 runtimeMeta.ipv4Blocked（只给主页 IPv6 提示用；存不下不影响隧道）
        runtime?.let { r ->
            try {
                store.setIpv4Blocked(r.ipv4Blocked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "save runtime meta failed: ${e.javaClass.simpleName}")
            }
        }

        if (!isCurrentRunning(gen)) return // 取配置期间隧道已经停了 / 换了
        val action = decideFollow(_activeRegionId.value, outcome)
        Log.i(TAG, "follow($trigger): ${describe(outcome)} -> ${action.javaClass.simpleName}")
        when (action) {
            FollowAction.Keep -> Unit
            FollowAction.Reload -> runtime?.let { r -> runLifecycle { reloadTunnel(gen, r) } }
            is FollowAction.Stop -> stopForAccess(action.kind, action.expiresAt)
        }
    }

    private fun describe(outcome: FollowOutcome): String = when (outcome) {
        is FollowOutcome.Fetched -> "fetched region=${outcome.regionId?.takeIf { it.isNotBlank() } ?: "-"}"
        is FollowOutcome.Failed -> if (outcome.code < 0) "network error" else "http ${outcome.code} ${outcome.reason}"
    }

    private fun stopForAccess(kind: StopKind, expiresAt: String?) {
        Log.i(TAG, "follow: access ended ($kind) -> stopping the tunnel")
        _stopReason.value = StopReason(kind, expiresAt)
        requestStop()
    }

    /**
     * 热重载：对正在运行的 CommandServer 再调一次 startOrReloadService（libbox 的「启动或重载」入口）。
     * 它在引擎内部先关掉旧的 box 实例、再用新配置建新的，期间 VpnService、前台通知、CommandServer 都不动；
     * 引擎会重新回调 openTun —— 新隧道建好后我们才关旧的 fd（见 openTun），所以不会出现无隧道的空档。
     * 全程不发布任何 CONNECTING / STOPPING / DISCONNECTED，界面始终是 Connected，只有区域名变了。
     * 这是「真正的原地重载」（没有拆服务再重起）；系统层面新旧两个 tun 接口会短暂并存。
     * 重载失败（旧实例已被关闭）时用上一份可用配置恢复；恢复也失败才算真正的失败。
     */
    private fun reloadTunnel(gen: Long, runtime: RuntimeConfig) {
        val server: CommandServer
        synchronized(lock) {
            if (generation != gen || phase != Phase.RUNNING) return
            server = commandServer ?: return
        }
        Log.i(TAG, "reload: engine -> region=${runtime.regionId.ifEmpty { "-" }}")
        val config = withEngineLog(runtime.configJson)
        try {
            server.startOrReloadService(config, OverrideOptions())
        } catch (e: Exception) {
            Log.w(TAG, "reload failed (${e.javaClass.simpleName}); restoring the previous configuration")
            rejectedConfig = config
            val previous = lastGoodConfig
            val restored = previous != null && try {
                server.startOrReloadService(previous, OverrideOptions())
                true
            } catch (_: Exception) {
                false
            }
            if (!restored) {
                val failed = synchronized(lock) {
                    if (generation == gen) {
                        phase = Phase.IDLE
                        generation++
                        true
                    } else {
                        false
                    }
                }
                if (failed) {
                    stopFollow()
                    commandServer = null
                    discard(server)
                    reportFailure("Region switch failed")
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            return
        }
        synchronized(lock) {
            if (generation != gen) return // 重载期间收到了 STOP：排在后面的 teardown 会清理
            lastGoodConfig = config
            rejectedConfig = null
            _activeRegionId.value = runtime.regionId.takeIf { it.isNotBlank() }
            _activeRegion.value = runtime.regionName.takeIf { it.isNotBlank() }
        }
        Log.i(TAG, "reload: done, now region=${runtime.regionId.ifEmpty { "-" }}")
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
        // 热重载时引擎会再次来要隧道：先把新的建好、换上，再关旧的（旧 fd 不关会一直占着旧接口）
        val previous = tunFd
        tunFd = pfd
        runCatching { previous?.close() }
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

/**
 * 给引擎配置换上我们的日志段，落到 app 私有文件（引擎默认只写 stdout，在 Android 上等于丢弃）。
 * debug 包用 info，便于排查路由决策；release 包用 warn —— info 级会把每条连接、每次 DNS 的域名写进文件，
 * 而 release 里没有任何地方读它，留着只会是一份不断变大的访问记录。
 */
internal fun engineLogConfig(raw: String, logPath: String, debug: Boolean): String = runCatching {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val obj = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
    kotlinx.serialization.json.buildJsonObject {
        obj.forEach { (k, v) -> if (k != "log") put(k, v) }
        put(
            "log",
            kotlinx.serialization.json.buildJsonObject {
                put("level", kotlinx.serialization.json.JsonPrimitive(if (debug) "info" else "warn"))
                put("timestamp", kotlinx.serialization.json.JsonPrimitive(true))
                put("output", kotlinx.serialization.json.JsonPrimitive(logPath))
            },
        )
    }.toString()
}.getOrDefault(raw)
