package com.redfluent.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.3：东京双栈后 proxy 是 urltest 组，引擎一启动就拨号（测速、下载 rule-set）。
 * 默认网络监视器必须在 startDefaultInterfaceMonitor 返回前同步报一次底层网络，
 * 否则启动报 "outbound/urltest[proxy]: no available network interface"（1.2.1 实测）。
 */
class DefaultNetworkMonitorTest {

    private data class Net(val name: String, val vpn: Boolean, val internet: Boolean = true)

    private val usable = { n: Net -> n.internet && n.vpn.not() }

    @Test
    fun prefersTheSystemDefaultWhenItIsARealNetwork() {
        val wifi = Net("wlan0", vpn = false)
        val cell = Net("rmnet0", vpn = false)
        assertEquals(wifi, pickUnderlying(wifi, listOf(cell, wifi), usable))
    }

    @Test
    fun skipsTheVpnItselfDuringAHotReload() {
        val tun = Net("tun0", vpn = true)
        val cell = Net("rmnet0", vpn = false)
        assertEquals(cell, pickUnderlying(tun, listOf(tun, cell), usable))
    }

    @Test
    fun nothingUsableMeansNoReport() {
        val tun = Net("tun0", vpn = true)
        val offline = Net("wlan0", vpn = false, internet = false)
        assertNull(pickUnderlying(tun, listOf(tun, offline), usable))
        assertNull(pickUnderlying(null, emptyList(), usable))
    }

    @Test
    fun startReportsSynchronouslyBeforeReturning() {
        val src = File("src/main/java/com/redfluent/vpn/vpn/PlatformBridge.kt").readText()
        val start = src.substringAfter("fun start(context: Context, listener: InterfaceUpdateListener)")
            .substringBefore("private fun currentUnderlying")
        // 先注册回调（之后的变化），再在返回前同步报一次当前底层网络
        val register = start.indexOf("registerBestMatchingNetworkCallback")
        val syncReport = start.indexOf("currentUnderlying(cm)?.let { report(cm, it, listener) }")
        assertTrue("start() must register the NOT_VPN network callback", register >= 0)
        assertTrue("start() must report the current underlying network synchronously", syncReport > register)
        // 仍然不用 registerDefaultNetworkCallback 作为首选（Android P 起会把 VPN 自己报成默认网络）
        assertTrue(start.contains("NET_CAPABILITY_NOT_RESTRICTED"))
    }

    @Test
    fun localDnsServerIsProvidedByThePlatform() {
        // 配置里 type=local 的 DNS 服务器：返回 null 时引擎去问 [::1]:53（Android 上没有）→ connection refused
        val src = File("src/main/java/com/redfluent/vpn/vpn/PlatformBridge.kt").readText()
        assertTrue(src.contains("override fun localDNSTransport(): LocalDNSTransport? = LocalResolver"))
        val resolver = src.substringAfter("internal object LocalResolver : LocalDNSTransport").substringBefore("\n}\n")
        assertTrue("queries must go to the underlying network", resolver.contains("DefaultNetworkMonitor.network()"))
        assertTrue(resolver.contains("rawQuery("))
        assertTrue(resolver.contains("ctx.errnoCode("))
    }
}
