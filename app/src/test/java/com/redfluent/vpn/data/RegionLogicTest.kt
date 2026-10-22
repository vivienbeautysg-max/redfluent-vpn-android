package com.redfluent.vpn.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionLogicTest {

    private val tokyo = RegionOption("tokyo", "Tokyo", true)
    private val singapore = RegionOption("singapore", "Singapore", false)

    // ---- 已选区域被服务器收回 → 静默回默认区域（正常就是东京）-------------------------------

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
    fun missingOrBlankSelectionMeansServerDefault() {
        assertEquals("tokyo", resolveRegion(null, listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("", listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("   ", listOf(tokyo, singapore)))
        assertEquals("tokyo", resolveRegion("tokyo", emptyList()))
    }

    @Test
    fun selectionIsCaseInsensitive() {
        assertEquals("singapore", resolveRegion("Singapore", listOf(tokyo, singapore)))
        // 服务器（或旧版本存下来的）列表里 id 大小写不规范也能对上
        assertEquals("singapore", resolveRegion("singapore", listOf(tokyo, RegionOption("Singapore"))))
    }

    // ---- F8：连东京都不提供时，用服务器标的默认区域，其次列表第一个 ---------------------------

    @Test
    fun fallsBackToServerDefaultWhenTokyoIsNotOffered() {
        val sgDefault = RegionOption("singapore", "Singapore", true)
        assertEquals("singapore", resolveRegion("tokyo", listOf(sgDefault)))
        assertEquals("singapore", resolveRegion(null, listOf(sgDefault)))
        assertEquals("singapore", resolveRegion("mars", listOf(RegionOption("frankfurt"), sgDefault)))
    }

    @Test
    fun fallsBackToFirstOfferedWhenNoDefaultFlagged() {
        val list = listOf(RegionOption("frankfurt"), RegionOption("singapore"))
        assertEquals("frankfurt", resolveRegion("tokyo", list))
        assertEquals("frankfurt", resolveRegion(null, list))
        // 列表为空时才落到硬编码的 tokyo
        assertEquals("tokyo", resolveRegion("singapore", emptyList()))
    }

    @Test
    fun defaultRegionIdPrefersFlaggedThenFirstThenTokyo() {
        assertEquals("singapore", defaultRegionId(listOf(tokyo.copy(isDefault = false), RegionOption("Singapore", isDefault = true))))
        assertEquals("a", defaultRegionId(listOf(RegionOption("A"), RegionOption("b"))))
        assertEquals("tokyo", defaultRegionId(emptyList()))
    }

    // ---- 旧后端兼容 + F8 整理 ------------------------------------------------------

    @Test
    fun normalizeRegionsFallsBackToTokyoOnly() {
        assertEquals(DEFAULT_REGIONS, normalizeRegions(null))
        assertEquals(DEFAULT_REGIONS, normalizeRegions(emptyList()))
        assertEquals(DEFAULT_REGIONS, normalizeRegions(listOf(RegionOption(" ", "x"))))
        assertEquals(listOf(tokyo, singapore), normalizeRegions(listOf(tokyo, singapore)))
        assertEquals("tokyo", DEFAULT_REGIONS.single().id)
    }

    @Test
    fun normalizeRegionsTrimsLowercasesAndDedupes() {
        val cleaned = normalizeRegions(
            listOf(
                RegionOption(" Tokyo ", " Tokyo ", true),
                RegionOption("tokyo", "Tokyo duplicate"),
                RegionOption("SINGAPORE", "Singapore"),
                RegionOption("", "no id"),
                RegionOption("singapore ", "Singapore again"),
            ),
        )
        assertEquals(listOf("tokyo", "singapore"), cleaned.map { it.id })
        assertEquals("Tokyo", cleaned[0].name) // 先出现的保留，名字也被 trim
        assertTrue(cleaned[0].isDefault)
        assertEquals("Singapore", cleaned[1].name)
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
        assertTrue(regionConfirmed("Singapore", "singapore"))
        // 旧后端忽略 ?region= 参数、照样发东京配置：必须拒绝
        assertFalse(regionConfirmed("singapore", null))
        assertFalse(regionConfirmed("singapore", ""))
        assertFalse(regionConfirmed("singapore", "tokyo"))
    }

    // ---- F5：服务器收回「隧道正在用的区域」→ 必须断开 ------------------------------------------

    @Test
    fun disconnectsWhenInUseRegionIsNoLongerOffered() {
        assertTrue(shouldDisconnectForRegions("singapore", listOf(tokyo)))
        assertTrue(shouldDisconnectForRegions("singapore", DEFAULT_REGIONS))
        assertTrue(shouldDisconnectForRegions("tokyo", listOf(RegionOption("singapore", isDefault = true))))
    }

    @Test
    fun keepsTunnelWhenInUseRegionStillOfferedOrNothingInUse() {
        assertFalse(shouldDisconnectForRegions("singapore", listOf(tokyo, singapore)))
        assertFalse(shouldDisconnectForRegions("Singapore", listOf(RegionOption(" singapore "))))
        assertFalse(shouldDisconnectForRegions(null, listOf(tokyo)))
        assertFalse(shouldDisconnectForRegions("", listOf(tokyo)))
        assertFalse(shouldDisconnectForRegions("  ", emptyList()))
    }

    // ---- F5：Region 一行显示什么 -------------------------------------------------------------

    private val ownerProfile = DeviceProfile("p", "t", serverRegion = "Tokyo", isOwner = true, regions = listOf(tokyo, singapore))
    private val plainProfile = DeviceProfile("p", "t", serverRegion = "Tokyo")

    @Test
    fun connectedAlwaysShowsActiveRegionEvenForSingleRegion() {
        // 单区域分支也要显示隧道实际连着的区域
        assertEquals("Singapore", regionShown(plainProfile, multiRegion = false, connected = true, selectedRegion = "tokyo", activeRegion = "Singapore"))
        assertEquals("Singapore", regionShown(ownerProfile, multiRegion = true, connected = true, selectedRegion = "tokyo", activeRegion = "Singapore"))
    }

    @Test
    fun notConnectedShowsProfileRegionOrSelection() {
        assertEquals("Tokyo", regionShown(plainProfile, multiRegion = false, connected = false, selectedRegion = "tokyo", activeRegion = null))
        assertEquals("Singapore", regionShown(ownerProfile, multiRegion = true, connected = false, selectedRegion = "singapore", activeRegion = null))
        assertEquals("Singapore", regionShown(ownerProfile, multiRegion = true, connected = false, selectedRegion = "Singapore", activeRegion = null))
    }

    @Test
    fun connectedButRegionUnknownFallsBackToProfile() {
        assertEquals("Tokyo", regionShown(plainProfile, multiRegion = false, connected = true, selectedRegion = "tokyo", activeRegion = null))
        assertEquals("Tokyo", regionShown(plainProfile, multiRegion = false, connected = true, selectedRegion = "tokyo", activeRegion = "  "))
        assertEquals("—", regionShown(null, multiRegion = false, connected = false, selectedRegion = "tokyo", activeRegion = null))
    }

    // ---- F4：宽松解码（这条路径每个新用户激活都要走）----------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun envelope(text: String) = json.decodeFromString<Envelope>(text)

    @Test
    fun legacyEnvelopeHasNoOwnerAndNoRegions() {
        val env = envelope("""{"ok":true,"profileId":"p1","enabled":true,"unknown":1}""")
        assertFalse(parseOwnerFlag(env.isOwner))
        assertEquals(DEFAULT_REGIONS, parseRegions(env.regions))
    }

    @Test
    fun ownerEnvelopeCarriesRegions() {
        val env = envelope(
            """{"ok":true,"profileId":"p1","isOwner":true,
                "regions":[{"id":"tokyo","name":"Tokyo","isDefault":true},{"id":"singapore","name":"Singapore","isDefault":false}]}""",
        )
        assertTrue(parseOwnerFlag(env.isOwner))
        assertEquals(listOf("tokyo", "singapore"), parseRegions(env.regions).map { it.id })
    }

    @Test
    fun malformedOwnerAndRegionsDoNotBreakTheEnvelope() {
        // 以前 isOwner 声明成 Boolean?、regions 声明成 List?：这些格式会让整个信封解码抛异常，
        // /redeem-invite 对每个新用户都会失败。
        val bad = listOf(
            """{"ok":true,"profileId":"p1","token":"t","isOwner":"yes","regions":{"id":"x"}}""",
            """{"ok":true,"profileId":"p1","token":"t","isOwner":1,"regions":"singapore"}""",
            """{"ok":true,"profileId":"p1","token":"t","isOwner":{"a":1},"regions":123}""",
            """{"ok":true,"profileId":"p1","token":"t","isOwner":null,"regions":null}""",
            """{"ok":true,"profileId":"p1","token":"t","isOwner":[true],"regions":[[],{"id":5},"x",{"name":"no id"},null]}""",
        )
        for (text in bad) {
            val env = envelope(text)
            assertTrue(env.ok)
            assertEquals("p1", env.profileId)
            assertEquals("t", env.token)
            assertFalse(text, parseOwnerFlag(env.isOwner))
            assertEquals(text, DEFAULT_REGIONS, parseRegions(env.regions))
        }
    }

    @Test
    fun ownerFlagOnlyAcceptsRealJsonBoolean() {
        fun flag(raw: String) = parseOwnerFlag(envelope("""{"ok":true,"isOwner":$raw}""").isOwner)
        assertTrue(flag("true"))
        assertFalse(flag("false"))
        assertFalse(flag("\"true\""))
        assertFalse(flag("1"))
        assertFalse(flag("null"))
        assertFalse(parseOwnerFlag(null))
    }

    @Test
    fun badRegionElementsAreDroppedGoodOnesKept() {
        val env = envelope(
            """{"ok":true,"regions":[{"id":"tokyo","name":"Tokyo","isDefault":true},{"id":5},"x",{"name":"no id"},{"id":"Singapore","name":"Singapore"}]}""",
        )
        assertEquals(listOf("tokyo", "singapore"), parseRegions(env.regions).map { it.id })
    }

    @Test
    fun emptyRegionsArrayMeansTokyoOnly() {
        assertEquals(DEFAULT_REGIONS, parseRegions(envelope("""{"ok":true,"regions":[]}""").regions))
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
        val text = json.encodeToString(DeviceProfile.serializer(), owner)
        val back = json.decodeFromString<DeviceProfile>(text)
        assertTrue(back.isOwner)
        assertEquals(listOf("tokyo", "singapore"), back.regions.map { it.id })
    }
}
