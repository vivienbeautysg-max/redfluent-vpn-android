package com.redfluent.vpn.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLogicTest {

    private fun device(
        id: String,
        label: String?,
        used: Double?,
        quota: Double? = 1000.0,
        name: String? = null,
        model: String? = null,
        version: String? = null,
        percent: Double? = null,
        code: String? = null,
        lastUsed: String? = null,
        enabled: Boolean = true,
        revokedAt: String? = null,
        online: Boolean = false,
    ) = OwnerDevice(
        profileId = id,
        inviteCode = code,
        inviteLabel = label,
        displayName = name,
        deviceName = model,
        appVersion = version,
        enabled = enabled,
        revokedAt = revokedAt,
        online = online,
        usage = used?.let { DeviceUsage(usedGB = it, quotaGB = quota, percent = percent) },
        lastUsedAt = lastUsed,
    )

    // ---- 颜色阈值：<70 正常，70–90 琥珀，≥90 红 -----------------------------

    @Test
    fun levelThresholds() {
        assertEquals(UsageLevel.NORMAL, usageLevel(0.0))
        assertEquals(UsageLevel.NORMAL, usageLevel(42.3))
        assertEquals(UsageLevel.NORMAL, usageLevel(69.9))
        assertEquals(UsageLevel.NORMAL, usageLevel(69.999))
        assertEquals(UsageLevel.WARNING, usageLevel(70.0))
        assertEquals(UsageLevel.WARNING, usageLevel(80.0))
        assertEquals(UsageLevel.WARNING, usageLevel(89.9))
        assertEquals(UsageLevel.WARNING, usageLevel(89.999))
        assertEquals(UsageLevel.CRITICAL, usageLevel(90.0))
        assertEquals(UsageLevel.CRITICAL, usageLevel(99.9))
        assertEquals(UsageLevel.CRITICAL, usageLevel(100.0))
        assertEquals(UsageLevel.CRITICAL, usageLevel(250.0)) // 可以超过 100%
    }

    @Test
    fun barPercentIsNullWithoutQuota() {
        assertNull(DeviceUsage(usedGB = 50.0, quotaGB = null, percent = null).barPercent())
        assertNull(DeviceUsage(usedGB = 50.0, quotaGB = null, percent = 12.0).barPercent()) // 没配额就不画条
        assertNull(DeviceUsage(usedGB = 50.0, quotaGB = 0.0).barPercent())
    }

    @Test
    fun barPercentPrefersServerValueElseComputes() {
        assertEquals(41.2, DeviceUsage(usedGB = 412.0, quotaGB = 1000.0, percent = 41.2).barPercent()!!, 1e-9)
        assertEquals(25.0, DeviceUsage(usedGB = 250.0, quotaGB = 1000.0, percent = null).barPercent()!!, 1e-9)
        assertEquals(101.0, DeviceUsage(usedGB = 1010.0, quotaGB = 1000.0, percent = 101.0).barPercent()!!, 1e-9)
        assertEquals(0.0, DeviceUsage(usedGB = 0.0, quotaGB = 1000.0, percent = -3.0).barPercent()!!, 1e-9)
    }

    // ---- 分组与排序 -----------------------------------------------------------

    @Test
    fun groupsSortedByTotalDescAndDevicesByUsedDesc() {
        val groups = groupByUser(
            listOf(
                device("a1", "Alice", 10.0, name = "Phone"),
                device("a2", "Alice", 500.0, name = "Tablet"),
                device("b1", "Bob", 700.0, name = "Laptop"),
                device("c1", "Carol", 30.0, name = "Desk"),
                device("c2", "Carol", 20.0, name = "Watch"),
            ),
        )
        assertEquals(listOf("Bob", "Alice", "Carol"), groups.map { it.label })
        assertEquals(700.0, groups[0].totalGB, 1e-9)
        assertEquals(510.0, groups[1].totalGB, 1e-9)
        assertEquals(50.0, groups[2].totalGB, 1e-9)
        assertEquals(listOf("a2", "a1"), groups[1].devices.map { it.profileId })
        assertEquals(listOf("c1", "c2"), groups[2].devices.map { it.profileId })
    }

    @Test
    fun missingUsageCountsAsZeroAndLabelFallsBack() {
        val groups = groupByUser(
            listOf(
                device("x1", null, null).copy(ownerLabel = "Legacy"),
                device("x2", "  ", 5.0), // 空白标签 → 回退
                device("x3", null, null),
            ),
        )
        assertEquals(listOf("Unassigned", "Legacy"), groups.map { it.label }) // Unassigned 总计 5 > Legacy 0
        assertEquals(5.0, groups[0].totalGB, 1e-9)
        assertEquals(0.0, groups[1].totalGB, 1e-9)
    }

    @Test
    fun equalTotalsKeepStableOrder() {
        val groups = groupByUser(
            listOf(
                device("1", "bravo", 10.0),
                device("2", "Alpha", 10.0),
            ),
        )
        assertEquals(listOf("Alpha", "bravo"), groups.map { it.label })
    }

    // ---- 1.2.1：按邀请码分组、Last used、在线、登出资格 ------------------------------------

    @Test
    fun groupsByInviteCodeNotByLabel() {
        val groups = groupByUser(
            listOf(
                device("a1", "Alice", 10.0, code = "CODE-A"),
                device("a2", "Alice", 20.0, code = "CODE-A"),
                device("x1", "Alice", 5.0, code = "CODE-X"), // 同名但是另一个邀请码 = 另一个用户
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf(30.0, 5.0), groups.map { it.totalGB })
        assertEquals(listOf("CODE-A", "CODE-X"), groups.map { it.code })
        assertEquals(2, groups.map { it.key }.toSet().size)
    }

    @Test
    fun codeComparisonForGroupingUsesNfkcAndIgnoresCase() {
        val groups = groupByUser(
            listOf(
                device("a1", "Alice", 1.0, code = "rf-abc"),
                device("a2", "Alice", 1.0, code = " RF-ABC "),
            ),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun tiesGoToTheMostRecentlyUsedThenNeverUsed() {
        val groups = groupByUser(
            listOf(
                device("n1", "Never", 50.0),
                device("o1", "Older", 50.0, lastUsed = "2026-10-20T10:00:00Z"),
                device("r1", "Recent", 50.0, lastUsed = "2026-10-22T09:00:00Z"),
                device("t1", "Top", 60.0),
            ),
        )
        assertEquals(listOf("Top", "Recent", "Older", "Never"), groups.map { it.label })
    }

    @Test
    fun totalWinsOverRecency() {
        val groups = groupByUser(
            listOf(
                device("a1", "Busy", 90.0, lastUsed = "2026-01-01T00:00:00Z"),
                device("b1", "Fresh", 10.0, lastUsed = "2026-10-22T00:00:00Z"),
            ),
        )
        assertEquals(listOf("Busy", "Fresh"), groups.map { it.label })
    }

    @Test
    fun lastUsedIsTheLatestAcrossTheDevicesAndBadValuesAreIgnored() {
        val g = groupByUser(
            listOf(
                device("a1", "Alice", 1.0, lastUsed = "2026-10-01T00:00:00Z"),
                device("a2", "Alice", 1.0, lastUsed = "2026-10-22 08:00:00"),
                device("a3", "Alice", 1.0, lastUsed = "garbage"),
                device("a4", "Alice", 1.0, lastUsed = null),
            ),
        )[0]
        assertEquals(parseInstantMillis("2026-10-22T08:00:00Z"), g.lastUsedAtMs)
        assertNull(groupByUser(listOf(device("z", "Zed", 1.0, lastUsed = "garbage")))[0].lastUsedAtMs)
    }

    @Test
    fun signedOutDevicesStillCountInTheTotalButNotAsActive() {
        val g = groupByUser(
            listOf(
                device("a1", "Alice", 100.0),
                device("a2", "Alice", 40.0, enabled = false, revokedAt = "2026-10-20 10:00:00"),
                device("a3", "Alice", 5.0, enabled = false), // 停用但没被登出
            ),
        )[0]
        assertEquals(145.0, g.totalGB, 1e-9)
        assertEquals(1, g.activeDevices)
        assertEquals(3, g.devices.size)
    }

    @Test
    fun activeMeansEnabledAndNotSignedOut() {
        assertEquals(true, device("a", "A", 1.0).isActive())
        assertEquals(false, device("a", "A", 1.0, enabled = false).isActive())
        assertEquals(false, device("a", "A", 1.0, revokedAt = "2026-10-20").isActive()) // 登出标记本身就够了
        assertEquals(false, device("a", "A", 1.0, enabled = false, revokedAt = "2026-10-20").isActive())
    }

    @Test
    fun onlineMeansAnActiveDeviceIsOnline() {
        assertEquals(true, groupByUser(listOf(device("a1", "A", 1.0, online = true)))[0].online)
        assertEquals(false, groupByUser(listOf(device("a1", "A", 1.0, online = false)))[0].online)
        // 已登出的设备不会让用户显示在线
        val revoked = device("a1", "A", 1.0, online = true, enabled = false, revokedAt = "2026-10-20")
        assertEquals(false, groupByUser(listOf(revoked))[0].online)
    }

    @Test
    fun ownerInviteIsRecognisedByCodeOrLabel() {
        val devices = listOf(
            device("o1", "Boss", 1.0, code = "OWNER-CODE"),
            device("u1", "Alice", 1.0, code = "ALICE-CODE"),
        )
        val byCode = groupByUser(devices, ownerCode = " owner-code ", ownerLabel = null)
        assertEquals(true, byCode.first { it.label == "Boss" }.isOwnerInvite)
        assertEquals(false, byCode.first { it.label == "Alice" }.isOwnerInvite)
        val byLabel = groupByUser(devices, ownerCode = null, ownerLabel = "boss")
        assertEquals(true, byLabel.first { it.label == "Boss" }.isOwnerInvite)
        assertEquals(false, byLabel.first { it.label == "Alice" }.isOwnerInvite)
        val neither = groupByUser(devices, ownerCode = null, ownerLabel = null)
        assertEquals(listOf(false, false), neither.map { it.isOwnerInvite })
        assertEquals(false, groupByUser(devices, ownerCode = "x", ownerLabel = "  ").any { it.isOwnerInvite })
    }

    @Test
    fun inviteCodeNeverShowsUpInToString() {
        val d = device("p1", "Alice", 1.0, code = "SECRET-CODE-123", name = "Phone")
        assertEquals(false, d.toString().contains("SECRET-CODE-123"))
        val g = groupByUser(listOf(d))[0]
        assertEquals(false, g.toString().contains("SECRET-CODE-123"))
        assertEquals("SECRET-CODE-123", g.code)
    }

    @Test
    fun decodesLastUsedAndInviteCode() {
        val text = """{"ok":true,"devices":[{"profileId":"p1","inviteCode":"C1","inviteLabel":"Alice","lastUsedAt":"2026-10-22T08:00:00.000Z",
            "usage":{"usedGB":1}},{"profileId":"p2","inviteLabel":"Bob","lastUsedAt":null}]}"""
        val resp = parseOwnerDevices(Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)
        assertEquals("C1", resp.devices[0].inviteCode)
        assertEquals("2026-10-22T08:00:00.000Z", resp.devices[0].lastUsedAt)
        assertNull(resp.devices[1].lastUsedAt)
        assertNull(resp.devices[1].inviteCode)
    }

    @Test
    fun wrongTypedLastUsedKeepsTheDeviceListed() {
        val text = """{"ok":true,"devices":[
            {"profileId":"p1","inviteLabel":"Alice","lastUsedAt":12345,"usage":{"usedGB":7}},
            {"profileId":"p2","inviteLabel":"Bob","lastUsedAt":"2026-10-22T08:00:00Z","usage":{"usedGB":"lots"}},
            {"profileId":"p3","inviteLabel":"Carol","inviteCode":99,"lastUsedAt":[],"usage":"x"}]}"""
        val resp = parseOwnerDevices(Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)
        assertEquals(0, resp.skipped)
        assertEquals(3, resp.devices.size)
        assertNull(resp.devices[0].lastUsedAt)
        assertEquals(7.0, resp.devices[0].usage!!.usedGB, 1e-9) // 只丢坏的那个字段，用量保住
        assertNull(resp.devices[1].usage)
        assertEquals("2026-10-22T08:00:00Z", resp.devices[1].lastUsedAt) // 坏的是 usage，lastUsedAt 保住
        assertNull(resp.devices[2].inviteCode)
    }

    // ---- 文案与数字格式 -------------------------------------------------------

    @Test
    fun titleAndSubtitle() {
        val d = device("p1", "Alice", 1.0, name = "Alice iPhone", model = "iPhone 15", version = "1.0 (39)")
        assertEquals("Alice iPhone", d.title())
        assertEquals("iPhone 15 · 1.0 (39)", d.subtitle())

        // 型号与名字相同时不重复
        val same = device("p2", "Alice", 1.0, name = "Pixel 8", model = "Pixel 8", version = "1.1 (2)")
        assertEquals("1.1 (2)", same.subtitle())

        // 没有名字时用型号；再没有时用 profileId 前 8 位
        assertEquals("ALN-AL10", device("p3", "Alice", 1.0, model = "ALN-AL10").title())
        assertEquals("abcdef12", device("abcdef1234567890", "Alice", 1.0).title())
    }

    @Test
    fun numberFormatting() {
        assertEquals("12.3", formatGB(12.34))
        assertEquals("0.0", formatGB(0.0))
        assertEquals("1234.5", formatGB(1234.5))
        assertEquals("1000", formatQuotaGB(1000.0))
        assertEquals("12.5", formatQuotaGB(12.5))
        assertEquals("41.2%", formatPercent(41.23))
        assertEquals("101.0%", formatPercent(101.0))
    }

    // ---- 旧后端兼容：没有 usage / period / lastUsedAt 也能解码；未知的多余字段被丢掉 --------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesNewBackendPayload() {
        val text = """
            {"ok":true,"period":{"start":"2026-10-01T00:00:00.000Z","end":"2026-11-01T00:00:00.000Z"},
             "devices":[{"profileId":"p1","inviteCode":"SECRET","inviteLabel":"Alice","displayName":"Phone",
               "deviceName":"iPhone","appVersion":"1.0 (39)","enabled":true,"online":true,
               "usage":{"usedGB":12.3,"quotaGB":1000,"percent":1.2,"uploadGB":2,"downloadGB":10.3},"extra":1},
              {"profileId":"p2","inviteLabel":"Bob","enabled":false,"revokedAt":"2026-10-02",
               "usage":{"usedGB":3,"quotaGB":null,"percent":null,"uploadGB":1,"downloadGB":2}}]}
        """.trimIndent()
        val resp = json.decodeFromString<OwnerDevicesResponse>(text)
        assertTrue(resp.ok)
        assertEquals("2026-10-01T00:00:00.000Z", resp.period?.start)
        assertEquals(2, resp.devices.size)
        assertEquals(12.3, resp.devices[0].usage!!.usedGB, 1e-9)
        assertEquals(1000.0, resp.devices[0].usage!!.quotaGB!!, 1e-9)
        assertNull(resp.devices[1].usage!!.quotaGB)
        assertNull(resp.devices[1].usage!!.barPercent())
    }

    @Test
    fun decodesOldBackendPayloadWithoutUsage() {
        val text = """{"ok":true,"devices":[{"profileId":"p1","inviteLabel":"Alice","displayName":"Phone","enabled":true}]}"""
        val resp = json.decodeFromString<OwnerDevicesResponse>(text)
        assertEquals(1, resp.devices.size)
        assertNull(resp.devices[0].usage)
        assertNull(resp.period)
        assertEquals(0.0, groupByUser(resp.devices)[0].totalGB, 1e-9)
    }
}
