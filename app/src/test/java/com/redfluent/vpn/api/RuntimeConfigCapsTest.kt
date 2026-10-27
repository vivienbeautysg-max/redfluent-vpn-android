package com.redfluent.vpn.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 1.3：取运行配置时声明 `X-RFV-Caps: local-dns`（后端只给声明了的客户端下发 type=local 的 DNS）。
 * App 取配置和 VpnService「跟随」时取配置走的是同一个 ApiClient.fetchRuntimeConfig，两处都要带上。
 */
class RuntimeConfigCapsTest {

    /** 最小的 HTTP 桩：每个连接读一个请求，记下路径和 X-RFV-Caps，回一份运行配置信封后关闭连接。 */
    private fun stub(seen: MutableList<Pair<String, String?>>): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (server.isClosed.not()) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val path = reader.readLine().orEmpty().split(" ").getOrNull(1).orEmpty().substringBefore("?")
                    var caps: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.substringBefore(":").trim().equals("X-RFV-Caps", ignoreCase = true)) {
                            caps = line.substringAfter(":").trim()
                        }
                    }
                    seen += path to caps
                    val body = """{"ok":true,"region":"tokyo","serverRegion":"Tokyo","runtimeConfig":{"outbounds":[]},"runtimeMeta":{"ipv4Blocked":true}}"""
                        .toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply {
                        write(head.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return server
    }

    @Test
    fun runtimeConfigRequestsDeclareLocalDns() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val server = stub(seen)
        try {
            ApiClient.setBaseUrl("http://127.0.0.1:${server.localPort}")
            val member = ApiClient.fetchRuntimeConfig("t", null) // 普通用户（不带 region）
            val owner = ApiClient.fetchRuntimeConfig("t", "tokyo") // owner 选东京
            assertEquals(true, member.ipv4Blocked)
            assertEquals("tokyo", owner.regionId)
        } finally {
            ApiClient.setBaseUrl(ApiClient.DEFAULT_BASE)
            server.close()
        }
        assertEquals(2, seen.size)
        seen.forEach { (path, caps) ->
            assertEquals("/device/runtime-config", path)
            assertEquals("local-dns", caps)
        }
    }

    @Test
    fun bothCallersUseTheSameFetch() {
        // 界面连接时（AppViewModel）和服务「跟随」时（RedFluentVpnService）都只能经过 fetchRuntimeConfig 取配置
        val main = File("src/main/java/com/redfluent/vpn")
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ApiClient.fetchRuntimeConfig(") }
            .map { it.name }.sorted().toList()
        assertEquals(listOf("AppViewModel.kt", "RedFluentVpnService.kt"), callers)
        val direct = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ApiClient.kt" }
            .filter { it.readText().contains("\"/device/runtime-config") }.map { it.name }.toList() // 字符串字面量，不算注释
        assertTrue("runtime-config must only be requested inside ApiClient: $direct", direct.isEmpty())
    }
}
