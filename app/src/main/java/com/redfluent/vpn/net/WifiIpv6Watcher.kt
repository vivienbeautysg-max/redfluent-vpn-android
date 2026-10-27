package com.redfluent.vpn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.redfluent.vpn.data.hasRoutableIPv6
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap

/**
 * 只看 Wi-Fi 本身（底层网络，不是 VPN）：有没有在用的 Wi-Fi？它能不能走 IPv6？
 * 隧道开着时系统默认网络是 VPN，所以这里按 TRANSPORT_WIFI + NOT_VPN 单独订阅，VPN 盖不住 Wi-Fi 的真实情况。
 * 主页据此显示 "Wi-Fi has no IPv6 — switch to cellular"（同 iOS 的 WiFiPathWatcher：NWPathMonitor(.wifi)）。
 */
class WifiIpv6Watcher(context: Context) {

    data class State(val onWiFi: Boolean = false, val wifiHasIPv6: Boolean = true)

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 每条在用的 Wi-Fi 网络 → 它能不能走 IPv6。 */
    private val networks = ConcurrentHashMap<Network, Boolean>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null || cm == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network, cm.getLinkProperties(network))
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = update(network, lp)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    networks.remove(network)
                    publish()
                }
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                publish()
            }
        }
        // 订阅失败（极少见）不影响别的功能：提示就不出现
        if (runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess) callback = cb
    }

    @Synchronized
    fun stop() {
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
        networks.clear()
        _state.value = State()
    }

    private fun update(network: Network, lp: LinkProperties?) {
        val caps = runCatching { cm?.getNetworkCapabilities(network) }.getOrNull()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
        networks[network] = lp?.let(::linkHasIPv6) ?: false
        publish()
    }

    private fun publish() {
        val values = networks.values.toList()
        _state.value = State(onWiFi = values.isNotEmpty(), wifiHasIPv6 = values.any { it })
    }

    companion object {
        /** 有全局 IPv6 地址，并且有 IPv6 默认路由（规则在 [hasRoutableIPv6]，JVM 测试覆盖）。 */
        fun linkHasIPv6(lp: LinkProperties): Boolean = hasRoutableIPv6(
            addresses = lp.linkAddresses.map { it.address },
            hasIPv6DefaultRoute = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address },
        )
    }
}
