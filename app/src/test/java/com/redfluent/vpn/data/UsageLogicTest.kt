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
    ) = OwnerDevice(
        profileId = id,
        inviteLabel = label,
        displayName = name,
        deviceName = model,
        appVersion = version,
        usage = used?.let { DeviceUsage(usedGB = it, quotaGB = quota, percent = percent) },
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

    // ---- 旧后端兼容：没有 usage / period 也能解码；inviteCode 等多余字段被丢掉 --------

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
