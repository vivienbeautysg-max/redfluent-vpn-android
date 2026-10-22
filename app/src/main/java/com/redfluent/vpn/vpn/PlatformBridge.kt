package com.redfluent.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.system.OsConstants
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
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

    override fun localDNSTransport(): LocalDNSTransport? = null

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
    }

    private fun notify(cm: ConnectivityManager, network: Network, listener: InterfaceUpdateListener) {
        val lp = cm.getLinkProperties(network) ?: return
        val caps = cm.getNetworkCapabilities(network)
        val name = lp.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        runCatching { listener.updateDefaultInterface(name, index, isExpensive, false) }
        runCatching { onNetworkChanged?.invoke() }
    }

    fun stop(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
