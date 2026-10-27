package com.redfluent.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Func
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/** 把 Kotlin 列表适配成 libbox 要的字符串迭代器。 */
class StringArray(private val iterator: Iterator<String>) : StringIterator {
    override fun len(): Int = 0
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): String = iterator.next()
}

private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): LibboxNetworkInterface = iterator.next()
}

/**
 * libbox 引擎向 Android 索取系统资源的桥。共 18 个回调。
 *
 * 只认真实现隧道真正需要的那几个；分应用代理、WiFi 状态、邻居表这类能力
 * 本 app 不提供（也因此不申请对应的定位权限），一律返回空/默认值。
 */
interface PlatformBridge : PlatformInterface {

    val androidContext: Context

    /** 由 VpnService 覆写：把 socket 排除在隧道之外，避免流量回环。 */
    fun protectSocket(fd: Int): Boolean

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protectSocket(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 仅 iOS 的 Network Extension 才为 true。 */
    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    /** 分应用代理未实现——本 app 全局代理，不区分应用。 */
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = throw UnsupportedOperationException("per-app proxy not supported")

    /** 1.3：配置里 type 为 local 的 DNS 服务器由系统解析器在底层网络上查（见 [LocalResolver]）。 */
    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    /** 不申请定位权限，因此拿不到也不需要 WiFi 状态。 */
    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator = StringArray(emptyList<String>().iterator())

    /** 引擎自己要弹通知的场景（本 app 的状态通知由 VpnService 统一管理）。 */
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit

    override fun clearDNSCache() = Unit

    override fun registerMyInterface(interfaceName: String) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    /**
     * 枚举网络接口供引擎选择出口。
     *
     * 🔴 只遍历 ConnectivityManager 认可的活跃网络，不要用
     * NetworkInterface.getNetworkInterfaces() 全量枚举——那会带出 dummy0 之类的
     * 虚拟网卡，其 IPv6 链路本地地址形如 fe80::xxx%dummy0，Go 侧 netip.ParsePrefix
     * 不接受带 zone 的前缀，会直接 panic 掉整个引擎。
     */
    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = androidContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return InterfaceArray(emptyList<LibboxNetworkInterface>().iterator())

        val systemInterfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
        val result = mutableListOf<LibboxNetworkInterface>()

        for (network in cm.allNetworks) {
            val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: continue
            val caps: NetworkCapabilities = cm.getNetworkCapabilities(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            val nif = systemInterfaces.find { it.name == name } ?: continue

            val boxed = LibboxNetworkInterface().apply {
                this.name = name
                index = nif.index
                runCatching { mtu = nif.mtu }
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                addresses = StringArray(
                    nif.interfaceAddresses.mapNotNull { it.toPrefixOrNull() }.iterator(),
                )
                // 引擎靠这两项决定怎么把流量送出去；漏掉会导致 tun 抓到的包转发不出去
                // （表现为 DNS 能通、网页打不开）。
                dnsServer = StringArray(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator(),
                )

                var f = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (runCatching { nif.isLoopback }.getOrDefault(false)) f = f or OsConstants.IFF_LOOPBACK
                if (runCatching { nif.isPointToPoint }.getOrDefault(false)) f = f or OsConstants.IFF_POINTOPOINT
                if (runCatching { nif.supportsMulticast() }.getOrDefault(false)) f = f or OsConstants.IFF_MULTICAST
                flags = f
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            result.add(boxed)
        }
        return InterfaceArray(result.iterator())
    }

    /**
     * IPv6 链路本地地址的 hostAddress 会带 zone（fe80::x%dummy0），
     * 用原始字节重建一个不带 zone 的地址再取字符串。
     */
    private fun InterfaceAddress.toPrefixOrNull(): String? = runCatching {
        val a = address
        if (a is Inet6Address) {
            Inet6Address.getByAddress(a.address).hostAddress + "/" + networkPrefixLength
        } else {
            a.hostAddress + "/" + networkPrefixLength
        }
    }.getOrNull()

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.start(androidContext, listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.stop(androidContext)
    }
}

/**
 * 1.3：sing-box 配置里 type 为 `local` 的 DNS 服务器，在 Android 上必须由平台提供（iOS 由系统解析）。
 * 不提供时引擎退回 Go 自带解析，读不到 /etc/resolv.conf，就去问 [::1]:53 → connection refused，
 * 凡是走 local 的查询全部失败（实测：已连上，但 api.ipify.org 解析不了，网页打不开）。
 * 照官方 sing-box for Android 的做法：用系统 DnsResolver 在底层（非 VPN）网络上查；Android 10 以下只能按域名查。
 * 本 app 自己的流量不进隧道（addDisallowedApplication），所以这些查询不会绕回隧道。
 */
internal object LocalResolver : LocalDNSTransport {

    private const val RCODE_NXDOMAIN = 3
    private const val TIMEOUT_SECONDS = 10L
    private val executor = Dispatchers.IO.asExecutor()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 10+：原样转发整条 DNS 报文（raw() 为 true 时引擎才会调它）。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        await(ctx) { signal, done ->
            DnsResolver.getInstance().rawQuery(
                DefaultNetworkMonitor.network(), message, DnsResolver.FLAG_NO_RETRY, executor, signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                        done(null)
                    }

                    override fun onError(error: DnsResolver.DnsException) = done(error)
                },
            )
        }
    }

    /** 按域名查，返回换行分隔的地址。network 以 4 / 6 结尾时只查 A / AAAA。 */
    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupWithDnsResolver(ctx, network, domain)
            return
        }
        val answer = try {
            DefaultNetworkMonitor.network()?.getAllByName(domain) ?: InetAddress.getAllByName(domain)
        } catch (e: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookupWithDnsResolver(ctx: ExchangeContext, network: String, domain: String) {
        await(ctx) { signal, done ->
            val callback = object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (rcode == 0) ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n")) else ctx.errorCode(rcode)
                    done(null)
                }

                override fun onError(error: DnsResolver.DnsException) = done(error)
            }
            val net = DefaultNetworkMonitor.network()
            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (type != null) {
                DnsResolver.getInstance().query(net, domain, type, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
            } else {
                DnsResolver.getInstance().query(net, domain, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
            }
        }
    }

    /**
     * 引擎在自己的线程上同步调用：发出查询，等回调（最多 10 秒）。
     * 系统报的 errno 原样交给引擎；其它错误抛出去，由引擎当作这次查询失败。
     */
    private fun await(ctx: ExchangeContext, start: (CancellationSignal, (Exception?) -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val signal = CancellationSignal()
        ctx.onCancel(Func { signal.cancel() })
        start(signal) { e ->
            failure.set(e)
            latch.countDown()
        }
        if (latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS).not()) {
            signal.cancel()
            throw IOException("local DNS timed out")
        }
        val e = failure.get() ?: return
        val errno = (e.cause as? ErrnoException)?.errno
        if (errno != null) ctx.errnoCode(errno) else throw e
    }
}

/** 选引擎用的底层网络：系统默认网络能用就用它；否则（默认网络是 VPN 自己）取第一个能用的；都不行为 null。纯逻辑，JVM 测试覆盖。 */
internal fun <T> pickUnderlying(active: T?, all: List<T>, usable: (T) -> Boolean): T? =
    active?.takeIf(usable) ?: all.firstOrNull(usable)

/**
 * 默认网络监视器。网络切换（WiFi↔移动数据）时通知引擎重建连接，
 * 否则切换后隧道会静默失效——用户以为还连着，其实已经不通。
 */
object DefaultNetworkMonitor {

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 1.2：底层网络变化（切换 / 链路属性变化 / 丢失）时额外通知一次，用于触发"无感"跟随检查。
     * 只是附加的旁路通知，不改变上面通知引擎的任何逻辑；由服务在隧道运行期间设置、停止时清空。
     */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null

    fun start(context: Context, listener: InterfaceUpdateListener) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        stop(context)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(cm, network, listener)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                notify(cm, network, listener)

            override fun onLost(network: Network) {
                if (current == network) current = null
                runCatching { listener.updateDefaultInterface("", -1, false, false) }
            }
        }
        callback = cb

        // 🔴 不能用 registerDefaultNetworkCallback：从 Android P 起它会把 VPN 自己
        // 报成默认网络，引擎于是找不到真正的出网口，报 "no available network interface"。
        // 必须显式请求「有互联网、不受限」的网络，才能拿到 VPN 底下那层真实网络。
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    cm.registerBestMatchingNetworkCallback(request, cb, Handler(Looper.getMainLooper()))
                Build.VERSION.SDK_INT >= 28 ->
                    cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
                else ->
                    cm.registerDefaultNetworkCallback(cb)
            }
        }.onFailure {
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }

        // 1.3：回调是异步的（主线程稍后才到）。引擎一启动就会拨号——东京双栈后 proxy 是 urltest 组，
        // 启动时就要测速、下载 rule-set——这时还不知道出网口就报 "no available network interface"，整个启动失败。
        // 所以在返回前先同步报一次当前的底层网络（不是 VPN 自己）；之后的变化仍由上面的回调负责。
        currentUnderlying(cm)?.let { report(cm, it, listener) }
    }

    /** 当前可上网、不受限、且不是 VPN 的网络：优先系统默认网络，否则任取一个符合条件的。 */
    @Suppress("DEPRECATION") // allNetworks：默认网络是 VPN 自己（热重载时）才需要，API 24 起都可用
    private fun currentUnderlying(cm: ConnectivityManager): Network? {
        fun usable(n: Network): Boolean {
            val c = cm.getNetworkCapabilities(n) ?: return false
            return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN).not()
        }
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return pickUnderlying(active, all) { usable(it) }
    }

    private fun notify(cm: ConnectivityManager, network: Network, listener: InterfaceUpdateListener) {
        if (report(cm, network, listener)) runCatching { onNetworkChanged?.invoke() }
    }

    /** 把这个网络作为默认出网口告诉引擎；拿不到接口名时什么也不做，返回 false。 */
    private fun report(cm: ConnectivityManager, network: Network, listener: InterfaceUpdateListener): Boolean {
        val lp = cm.getLinkProperties(network) ?: return false
        val caps = cm.getNetworkCapabilities(network)
        val name = lp.interfaceName ?: return false
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        current = network
        runCatching { listener.updateDefaultInterface(name, index, isExpensive, false) }
        return true
    }

    /** 最近一次报给引擎的底层网络（[LocalResolver] 在它上面查 DNS）；还没有为 null（= 本 app 的默认网络，本就不进隧道）。 */
    @Volatile
    private var current: Network? = null

    fun network(): Network? = current

    fun stop(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        current = null
    }
}
