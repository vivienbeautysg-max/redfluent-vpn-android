package com.redfluent.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住一条底线：包里绝不能带任何隧道配置，服务也绝不能回退到「设备自己取来的配置」以外的东西。
 *  - src/main/assets 只允许 licenses/ 下的 .txt（字体许可证），不许有任何 .json 或别的文件；
 *  - src/main/res/raw 不许存在任何文件；
 *  - 主源码里不许读 assets / raw 资源；
 *  - 服务里的配置只来自 Intent 的 EXTRA_CONFIG（ViewModel 从 /runtime-config 取来的）或「跟随」时重新取的 runtime.configJson，
 *    没配置就报错停下（"Missing tunnel configuration"），不兜底。
 */
class NoBundledConfigTest {

    private val main = File("src/main")

    @Test
    fun runsFromTheModuleDirectory() {
        assertTrue("unit tests must run with the app module as working dir: ${File(".").absolutePath}", main.isDirectory)
    }

    @Test
    fun noConfigFilesAreBundled() {
        val assets = File(main, "assets")
        val files = assets.walkTopDown().filter { it.isFile }.map { it.relativeTo(assets).invariantSeparatorsPath }.toList()
        val notLicenses = files.filterNot { it.startsWith("licenses/") && it.endsWith(".txt") }
        assertEquals("unexpected files in src/main/assets", emptyList<String>(), notLicenses)
        assertTrue("no json anywhere in assets", files.none { it.endsWith(".json", ignoreCase = true) })

        val raw = File(main, "res/raw")
        val rawFiles = if (raw.exists()) raw.walkTopDown().filter { it.isFile }.toList() else emptyList()
        assertEquals("res/raw must stay empty", emptyList<File>(), rawFiles)

        val jsonUnderRes = File(main, "res").walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.toList()
        assertEquals(emptyList<File>(), jsonUnderRes)
    }

    @Test
    fun mainSourcesNeverReadAssetsOrRawResources() {
        val forbidden = listOf("assets.open", ".getAssets(", "openRawResource", "R.raw.", "AssetManager", "file:///android_asset")
        val hits = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it) }.map { "${f.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun serviceOnlyUsesTheFetchedConfig() {
        val src = File(main, "java/com/redfluent/vpn/vpn/RedFluentVpnService.kt").readText()
        // 启动：只读 Intent 里的 EXTRA_CONFIG，没有就报错，不兜底
        assertTrue(src.contains("intent?.getStringExtra(EXTRA_CONFIG)"))
        assertTrue(src.contains("Missing tunnel configuration"))
        // 引擎只吃两种来源：启动时传进来的 / 跟随时新取的 runtime.configJson（以及它们成功后的副本 lastGoodConfig）
        val engineCalls = Regex("startOrReloadService\\(([^,]+),").findAll(src).map { it.groupValues[1].trim() }.toSet()
        assertTrue("engine called with: $engineCalls", engineCalls.isNotEmpty())
        assertTrue("engine called with: $engineCalls", engineCalls.all { it in setOf("configJson", "config", "previous") })
        // 源码里没有写死的配置内容
        for (literal in listOf("\"outbounds\"", "\"vless\"", "\"reality\"", "private_key", "\"inbounds\"")) {
            assertTrue("literal $literal in service", src.contains(literal).not())
        }
    }
}
