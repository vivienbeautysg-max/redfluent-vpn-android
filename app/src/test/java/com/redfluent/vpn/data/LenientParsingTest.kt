package com.redfluent.vpn.data

import com.redfluent.vpn.api.ApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F4（逐设备宽松解码）、用量数值保护、F7（错误文案）。 */
class LenientParsingTest {

    private fun root(text: String) = Json.parseToJsonElement(text) as JsonObject

    // ---- 用量表：一台设备坏了不能让整张表消失 -------------------------------------------------

    @Test
    fun oneBadDeviceDoesNotHideTheTable() {
        val resp = parseOwnerDevices(
            root(
                """{"ok":true,"period":{"start":"2026-10-01","end":"2026-11-01"},"devices":[
                    {"profileId":"a","inviteLabel":"Alice","displayName":"Phone","usage":{"usedGB":12.5,"quotaGB":1000,"percent":1.3}},
                    {"profileId":"b","inviteLabel":"Bob","displayName":"Tablet","usage":{"usedGB":"abc","quotaGB":1000}},
                    "not an object",
                    {"profileId":"c","inviteLabel":"Carol","enabled":"maybe"},
                    {"profileId":"d","inviteLabel":"Dave","displayName":"Mac","usage":{"usedGB":3,"quotaGB":null}}
                ]}""",
            ),
        )
        assertTrue(resp.ok)
        assertEquals(listOf("a", "b", "d"), resp.devices.map { it.profileId })
        assertEquals(2, resp.skipped) // 非对象的元素 + enabled 格式不对、连去掉 usage 也救不回来的那台
        assertEquals("2026-10-01", resp.period?.start)
        // a、d 的数据完整；b 去掉了坏的 usage，仍然列出来，只是没有进度条
        assertEquals(12.5, resp.devices[0].usage!!.usedGB, 1e-9)
        assertNull(resp.devices[1].usage)
        assertEquals(3.0, resp.devices[2].usage!!.usedGB, 1e-9)
    }

    @Test
    fun badTopLevelFieldsDegradeInsteadOfThrowing() {
        val resp = parseOwnerDevices(root("""{"ok":true,"period":"never","devices":{"x":1}}"""))
        assertTrue(resp.ok)
        assertNull(resp.period)
        assertTrue(resp.devices.isEmpty())
        assertFalse(parseOwnerDevices(root("""{"devices":[]}""")).ok)
        assertFalse(parseOwnerDevices(root("""{"ok":"yes"}""")).ok)
        assertEquals("boom", parseOwnerDevices(root("""{"ok":false,"error":"boom"}""")).error)
    }

    @Test
    fun inviteCodeIsNeverCarriedIntoTheModel() {
        val resp = parseOwnerDevices(root("""{"ok":true,"devices":[{"profileId":"a","inviteCode":"SECRET-CODE","inviteLabel":"Alice"}]}"""))
        assertEquals("Alice", resp.devices.single().inviteLabel)
        assertFalse(resp.devices.single().toString().contains("SECRET-CODE"))
    }

    // ---- 数值保护：NaN / 无穷 / 负数 -------------------------------------------------------------

    @Test
    fun nonFiniteAndNegativeUsedGBCountAsZero() {
        assertEquals(0.0, DeviceUsage(usedGB = Double.NaN).safeUsedGB(), 0.0)
        assertEquals(0.0, DeviceUsage(usedGB = Double.POSITIVE_INFINITY).safeUsedGB(), 0.0)
        assertEquals(0.0, DeviceUsage(usedGB = Double.NEGATIVE_INFINITY).safeUsedGB(), 0.0)
        assertEquals(0.0, DeviceUsage(usedGB = -5.0).safeUsedGB(), 0.0)
        assertEquals(7.5, DeviceUsage(usedGB = 7.5).safeUsedGB(), 0.0)
    }

    @Test
    fun badQuotaMeansNoBar() {
        assertNull(DeviceUsage(usedGB = 5.0, quotaGB = Double.NaN).barPercent())
        assertNull(DeviceUsage(usedGB = 5.0, quotaGB = Double.POSITIVE_INFINITY).barPercent())
        assertNull(DeviceUsage(usedGB = 5.0, quotaGB = -100.0).barPercent())
        assertNull(DeviceUsage(usedGB = 5.0, quotaGB = 0.0).barPercent())
        assertNull(DeviceUsage(usedGB = 5.0, quotaGB = Double.NaN).safeQuotaGB())
        assertEquals(1000.0, DeviceUsage(usedGB = 5.0, quotaGB = 1000.0).safeQuotaGB()!!, 0.0)
    }

    @Test
    fun nonFinitePercentIsRecomputedNegativeIsClamped() {
        assertEquals(25.0, DeviceUsage(usedGB = 250.0, quotaGB = 1000.0, percent = Double.NaN).barPercent()!!, 1e-9)
        assertEquals(25.0, DeviceUsage(usedGB = 250.0, quotaGB = 1000.0, percent = Double.POSITIVE_INFINITY).barPercent()!!, 1e-9)
        assertEquals(0.0, DeviceUsage(usedGB = 250.0, quotaGB = 1000.0, percent = -7.0).barPercent()!!, 1e-9)
        // 用量非法时按 0 算，而不是 NaN
        assertEquals(0.0, DeviceUsage(usedGB = Double.NaN, quotaGB = 1000.0, percent = null).barPercent()!!, 1e-9)
        // 极端大数相除溢出成无穷：宁可不画条
        assertNull(DeviceUsage(usedGB = 1e308, quotaGB = 1e-300, percent = null).barPercent())
    }

    @Test
    fun invalidUsageIsExcludedFromTotalsAndSorting() {
        fun dev(id: String, used: Double) = OwnerDevice(profileId = id, inviteLabel = "Alice", displayName = id, usage = DeviceUsage(usedGB = used))
        val group = groupByUser(listOf(dev("nan", Double.NaN), dev("neg", -50.0), dev("inf", Double.POSITIVE_INFINITY), dev("ok", 10.0))).single()
        assertEquals(10.0, group.totalGB, 0.0)
        assertTrue(group.totalGB.isFinite())
        assertEquals("ok", group.devices.first().profileId)
        assertEquals(10.0, OwnerDevice(usage = DeviceUsage(usedGB = 10.0)).usedGB(), 0.0)
        assertEquals(0.0, OwnerDevice(usage = DeviceUsage(usedGB = Double.NaN)).usedGB(), 0.0)
    }

    // ---- F7：失败响应变成一句短话，界面上不出现 HTML ---------------------------------------------

    private val cloudflarePage = "<!DOCTYPE html>\n<html lang=\"en-US\"><head><title>502 Bad gateway</title></head><body>…</body></html>"

    @Test
    fun htmlErrorBodyBecomesShortSentence() {
        val reason = cleanErrorReason(502, null, cloudflarePage)
        assertEquals("bad gateway", reason)
        val shown = ApiException(502, reason).message!!
        assertEquals("Server error 502: bad gateway", shown)
        assertFalse(shown.contains("<!DOCTYPE", ignoreCase = true))
        assertFalse(shown.contains("<"))
    }

    @Test
    fun statusTextCoversCommonGatewayCodes() {
        assertEquals("service unavailable", cleanErrorReason(503, null, "<html>x</html>"))
        assertEquals("gateway timeout", cleanErrorReason(504, null, "<html>x</html>"))
        assertEquals("upstream error", cleanErrorReason(522, null, "<html>x</html>"))
        assertEquals("unexpected response", cleanErrorReason(418, null, "<html>x</html>"))
    }

    @Test
    fun jsonErrorReasonIsKeptSoKnownMessagesStillMapToText() {
        assertEquals("invite expired", cleanErrorReason(403, "invite expired", """{"ok":false,"error":"invite expired"}"""))
        assertEquals("This invite has expired.", ApiException(403, "invite expired").message)
        assertEquals("This region is unavailable right now.", ApiException(503, "region unavailable").message)
    }

    @Test
    fun jsonErrorThatLooksLikeMarkupOrIsTooLongIsReplaced() {
        assertEquals("bad gateway", cleanErrorReason(502, "<!DOCTYPE html><p>x</p>", "{}"))
        assertEquals("internal error", cleanErrorReason(500, "x".repeat(500), "{}"))
        assertEquals("internal error", cleanErrorReason(500, "line1\nline2", "{}"))
    }

    @Test
    fun plainTextAndEmptyBodies() {
        assertEquals("unknown", cleanErrorReason(500, null, "   "))
        assertEquals("Bad Gateway", cleanErrorReason(502, null, " Bad Gateway \n"))
        assertEquals("bad gateway", cleanErrorReason(502, null, "x".repeat(500)))
        assertEquals("bad gateway", cleanErrorReason(502, null, "line1\nline2"))
        val longest = cleanErrorReason(500, null, "y".repeat(5000))
        assertTrue(longest.length <= MAX_ERROR_REASON_LENGTH)
    }

    @Test
    fun networkErrorHasNoServerErrorPrefix() {
        assertEquals("Network error: timeout", ApiException(-1, "Network error: timeout").message)
        assertNotNull(ApiException(500, "boom").message)
        assertEquals("Server error 500: boom", ApiException(500, "boom").message)
    }
}
