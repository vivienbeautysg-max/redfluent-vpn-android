package com.redfluent.vpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 引擎日志：release 只记 warn 及以上（不写每条连接的域名），每次起隧道清空文件。 */
class EngineLogConfigTest {

    private val raw = """
        {"log":{"level":"trace","output":"stderr"},
         "dns":{"servers":[{"tag":"local","type":"local"}],"final":"local"},
         "outbounds":[{"type":"direct","tag":"direct"}],
         "route":{"final":"direct"}}
    """.trimIndent()

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun releaseLogsWarnDebugLogsInfo() {
        val release = parse(engineLogConfig(raw, "/data/x/files/box.log", debug = false))
        val debug = parse(engineLogConfig(raw, "/data/x/files/box.log", debug = true))
        for ((cfg, level) in listOf(release to "warn", debug to "info")) {
            val log = cfg.getValue("log").jsonObject
            assertEquals(level, log.getValue("level").jsonPrimitive.content)
            assertEquals("/data/x/files/box.log", log.getValue("output").jsonPrimitive.content)
            assertEquals("true", log.getValue("timestamp").jsonPrimitive.content)
            assertEquals(setOf("level", "output", "timestamp"), log.keys)
            // 其余配置原样保留
            val original = parse(raw)
            for (key in listOf("dns", "outbounds", "route")) assertEquals(original[key], cfg[key])
        }
    }

    @Test
    fun serviceUsesBuildTypeLevelAndClearsTheLogOnEveryTunnelStart() {
        val src = File("src/main/java/com/redfluent/vpn/vpn/RedFluentVpnService.kt").readText()
        // 级别只由构建类型决定：调用点传 BuildConfig.DEBUG，没有别处写死 info
        assertTrue(src.contains("engineLogConfig(raw, engineLogFile().absolutePath, debug = BuildConfig.DEBUG)"))
        assertEquals(1, Regex("JsonPrimitive\\(if \\(debug\\) \"info\" else \"warn\"\\)").findAll(src).count())
        assertEquals(1, Regex("\"box\\.log\"").findAll(src).count())
        // 起隧道：先删旧日志，再生成配置、启动引擎（引擎以 O_APPEND 打开，不清就跨会话一直变大）
        val start = src.substring(src.indexOf("private fun startTunnel("))
        val clear = start.indexOf("engineLogFile().delete()")
        val config = start.indexOf("withEngineLog(rawConfig)")
        val engine = start.indexOf("startOrReloadService(configJson")
        assertTrue("clear=$clear config=$config engine=$engine", clear in 0 until config && config < engine)
    }
}
