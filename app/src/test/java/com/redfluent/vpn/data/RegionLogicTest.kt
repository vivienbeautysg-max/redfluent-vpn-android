package com.redfluent.vpn.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionLogicTest {

    private val tokyo = RegionOption("tokyo", "Tokyo", true)
    private val singapore = RegionOption("singapore", "Singapore", false)

    // ---- 已选区域被服务器收回 → 静默回东京 ----------------------------------------

    @Test
    fun keepsSelectionWhileStillOffered() {
        assertEquals("singapore", resolveRegion("singapore", listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("tokyo", listOf(tokyo, singapore)))
    }

    @Test
    fun resetsToTokyoWhenNoLongerOffered() {
        // 新加坡被关闭 / 设备不再是 owner：服务器只给 tokyo
        assertEquals("tokyo", resolveRegion("singapore", listOf(tokyo)))
        assertEquals("tokyo", resolveRegion("singapore", DEFAULT_REGIONS))
        assertEquals("tokyo", resolveRegion("singapore", emptyList()))
        assertEquals("tokyo", resolveRegion("mars", listOf(tokyo, singapore)))
    }

    @Test
    fun missingOrBlankSelectionMeansTokyo() {
        assertEquals("tokyo", resolveRegion(null, listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("", listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("   ", listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("tokyo", emptyList()))
    }

    @Test
    fun selectionIsCaseInsensitive() {
        assertEquals("singapore", resolveRegion("Singapore", listOf(tokyo, singapore)))
    }

    // ---- 旧后端兼容 --------------------------------------------------------------

    @Test
    fun normalizeRegionsFallsBackToTokyoOnly() {
        assertEquals(DEFAULT_REGIONS, normalizeRegions(null))
        assertEquals(DEFAULT_REGIONS, normalizeRegions(emptyList()))
        assertEquals(DEFAULT_REGIONS, normalizeRegions(listOf(RegionOption(" ", "x"))))
        assertEquals(listOf(tokyo, singapore), normalizeRegions(listOf(tokyo, singapore)))
        assertEquals("tokyo", DEFAULT_REGIONS.single().id)
    }

    @Test
    fun regionLabelFallsBackToCapitalizedId() {
        assertEquals("Singapore", RegionOption("singapore").label)
        assertEquals("Singapore", singapore.label)
    }

    // ---- 非默认区域：服务器必须确认，否则绝不连 -------------------------------------------

    @Test
    fun tokyoNeedsNoConfirmation() {
        assertTrue(regionConfirmed("tokyo", null)) // 旧后端不返回 region 字段
        assertTrue(regionConfirmed("tokyo", "tokyo"))
    }

    @Test
    fun nonDefaultRegionMustBeConfirmedByServer() {
        assertTrue(regionConfirmed("singapore", "singapore"))
        assertTrue(regionConfirmed("singapore", " Singapore "))
        // 旧后端忽略 ?region= 参数、照样发东京配置：必须拒绝
        assertFalse(regionConfirmed("singapore", null))
        assertFalse(regionConfirmed("singapore", ""))
        assertFalse(regionConfirmed("singapore", "tokyo"))
    }

    // ---- 解码：旧后端 / 新后端 / 旧版本存下来的 profile -------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyEnvelopeHasNoOwnerAndNoRegions() {
        val env = json.decodeFromString<Envelope>("""{"ok":true,"profileId":"p1","enabled":true,"unknown":1}""")
        assertEquals(null, env.isOwner)
        assertEquals(null, env.regions)
        assertEquals(DEFAULT_REGIONS, normalizeRegions(env.regions))
    }

    @Test
    fun ownerEnvelopeCarriesRegions() {
        val env = json.decodeFromString<Envelope>(
            """{"ok":true,"profileId":"p1","isOwner":true,
                "regions":[{"id":"tokyo","name":"Tokyo","isDefault":true},{"id":"singapore","name":"Singapore","isDefault":false}]}""",
        )
        assertEquals(true, env.isOwner)
        assertEquals(listOf("tokyo", "singapore"), normalizeRegions(env.regions).map { it.id })
    }

    @Test
    fun profileStoredByVersion10StillDecodes() {
        // 1.0 存进 DataStore 的 JSON 里没有 isOwner / regions
        val stored = """{"profileId":"p1","token":"t","ownerLabel":"Alice","serverRegion":"Tokyo","configVersion":"1","monthlyQuotaGB":200}"""
        val profile = json.decodeFromString<DeviceProfile>(stored)
        assertFalse(profile.isOwner)
        assertEquals(DEFAULT_REGIONS, profile.regions)
        assertEquals("Tokyo", profile.serverRegion)
    }

    @Test
    fun profileRoundTripKeepsOwnerRegions() {
        val owner = DeviceProfile("p1", "t", isOwner = true, regions = listOf(tokyo, singapore))
        val text = Json { ignoreUnknownKeys = true }.encodeToString(DeviceProfile.serializer(), owner)
        val back = json.decodeFromString<DeviceProfile>(text)
        assertTrue(back.isOwner)
        assertEquals(listOf("tokyo", "singapore"), back.regions.map { it.id })
    }
}
