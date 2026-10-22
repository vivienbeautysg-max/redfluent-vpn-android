package com.redfluent.vpn.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 到期状态的显示、解析与写回。 */
class AccessDisplayTest {

    private fun root(text: String) = Json.parseToJsonElement(text) as JsonObject

    // ---- 日期 --------------------------------------------------------------------------------

    @Test
    fun formatsTheDatePartOfAnIsoString() {
        assertEquals("3 Nov 2026", formatDay("2026-11-03T00:00:00.000Z"))
        assertEquals("21 Nov 2026", formatDay("2026-11-21T23:59:59.999Z"))
        assertEquals("9 Jan 2026", formatDay("2026-01-09"))
        assertEquals("31 Dec 2027", formatDay(" 2027-12-31T00:00:00.000Z "))
    }

    @Test
    fun invalidDatesAreNull() {
        for (bad in listOf(null, "", "abc", "2026/11/03", "2026-13-01", "2026-00-10", "2026-11-32", "26-11-03", "2026-1-3x")) {
            assertNull("should be null: $bad", formatDay(bad))
        }
    }

    @Test
    fun daysLeftWording() {
        assertEquals("29 days left", daysLeftText(29))
        assertEquals("2 days left", daysLeftText(2))
        assertEquals("1 day left", daysLeftText(1))
        assertEquals("today", daysLeftText(0))
        assertEquals("today", daysLeftText(-3))
    }

    // ---- 主页提示 ------------------------------------------------------------------------------

    @Test
    fun expiredShowsTheDate() {
        val n = accessNotice(expired = true, expiresAt = "2026-11-03T00:00:00.000Z", daysLeft = -2, stop = null)
        assertEquals(AccessNotice.Expired("3 Nov 2026"), n)
        assertEquals("Expired · 3 Nov 2026", n!!.text())
        assertEquals("Expired", accessNotice(true, null, null, null)!!.text())
    }

    @Test
    fun daysLeftAppearsOnlyInTheLastWeek() {
        assertNull(accessNotice(false, "2026-12-01T00:00:00.000Z", 8, null))
        assertEquals(AccessNotice.DaysLeft(7), accessNotice(false, "2026-12-01T00:00:00.000Z", 7, null))
        assertEquals(AccessNotice.DaysLeft(3), accessNotice(false, "2026-12-01T00:00:00.000Z", 3, null))
        assertEquals(AccessNotice.DaysLeft(0), accessNotice(false, "2026-12-01T00:00:00.000Z", 0, null))
        assertNull(accessNotice(false, null, null, null))
        assertNull(accessNotice(false, "2026-12-01T00:00:00.000Z", -1, null)) // 已过期会由 expired 标志表达，负数本身不提示
        assertEquals("3 days left", AccessNotice.DaysLeft(3).text())
        assertEquals("1 day left", AccessNotice.DaysLeft(1).text())
        assertEquals("Expires today", AccessNotice.DaysLeft(0).text())
    }

    @Test
    fun stopReasonsShowImmediatelyAndRevokedWins() {
        // 隧道刚被服务器叫停，资料还没刷新：先显示出来
        assertEquals(
            AccessNotice.Expired("3 Nov 2026"),
            accessNotice(false, null, null, StopReason(StopKind.EXPIRED, "2026-11-03T00:00:00.000Z")),
        )
        // 资料里已有日期时以资料为准
        assertEquals(
            AccessNotice.Expired("4 Nov 2026"),
            accessNotice(true, "2026-11-04T00:00:00.000Z", null, StopReason(StopKind.EXPIRED, "2026-11-03T00:00:00.000Z")),
        )
        assertEquals(AccessNotice.Revoked, accessNotice(true, "2026-11-04T00:00:00.000Z", 2, StopReason(StopKind.REVOKED)))
        assertEquals("Revoked", AccessNotice.Revoked.text())
    }

    @Test
    fun onlyExpiredAndRevokedLockConnect() {
        assertTrue(AccessNotice.Expired("3 Nov 2026").locksConnect())
        assertTrue(AccessNotice.Expired(null).locksConnect())
        assertTrue(AccessNotice.Revoked.locksConnect())
        assertFalse(AccessNotice.DaysLeft(3).locksConnect())
        val none: AccessNotice? = null
        assertFalse(none.locksConnect())
    }

    @Test
    fun profileOverloadReadsTheStoredFields() {
        val p = DeviceProfile("p", "t", expiresAt = "2026-11-03T00:00:00.000Z", expired = true)
        assertEquals(AccessNotice.Expired("3 Nov 2026"), accessNotice(p, null))
        assertNull(accessNotice(null as DeviceProfile?, null))
        assertNull(accessNotice(DeviceProfile("p", "t"), null))
    }

    // ---- /profile 的到期字段解析（有无该字段要分得清）--------------------------------------------------

    @Test
    fun noExpiryKeysMeansOldBackend() {
        assertNull(parseAccessInfo(root("""{"ok":true,"profileId":"p"}""")))
    }

    @Test
    fun parsesAllThreeFields() {
        val a = parseAccessInfo(root("""{"expiresAt":"2026-11-03T00:00:00.000Z","daysLeft":29,"expired":false}"""))!!
        assertEquals("2026-11-03T00:00:00.000Z", a.expiresAt)
        assertEquals(29, a.daysLeft)
        assertFalse(a.expired)
        val b = parseAccessInfo(root("""{"expiresAt":"2026-10-01T00:00:00.000Z","daysLeft":-3,"expired":true}"""))!!
        assertTrue(b.expired)
        assertEquals(-3, b.daysLeft)
    }

    @Test
    fun explicitNullsStillCountAsPresent() {
        // 时长被清掉 / 还没激活：expiresAt 明确是 null，要把本地旧的到期日清掉
        val a = parseAccessInfo(root("""{"expiresAt":null,"daysLeft":null,"expired":false}"""))
        assertNotNull(a)
        assertNull(a!!.expiresAt)
        assertNull(a.daysLeft)
        assertFalse(a.expired)
    }

    @Test
    fun wrongTypesDegradeInsteadOfThrowing() {
        val a = parseAccessInfo(root("""{"expiresAt":20261103,"daysLeft":"soon","expired":"yes"}"""))!!
        assertNull(a.expiresAt)
        assertNull(a.daysLeft)
        assertFalse(a.expired)
        // 引号包起来的数字 / 布尔也不算：只认真正的 JSON 数字和布尔
        val quoted = parseAccessInfo(root("""{"expiresAt":"2026-11-03T00:00:00.000Z","daysLeft":"29","expired":"true"}"""))!!
        assertNull(quoted.daysLeft)
        assertFalse(quoted.expired)
        assertEquals(29, parseAccessInfo(root("""{"daysLeft":29.9}"""))!!.daysLeft)
        assertNull(parseAccessInfo(root("""{"daysLeft":[1]}"""))!!.daysLeft)
    }

    // ---- 写回本地资料 --------------------------------------------------------------------------------

    private val base = DeviceProfile(
        profileId = "p1", token = "t", serverRegion = "Tokyo", expiresAt = "2027-12-31T00:00:00.000Z",
    )

    @Test
    fun syncOverwritesExpiryWhenTheResponseCarriesIt() {
        val status = ProfileStatus(
            profileId = "p1",
            serverRegion = "Singapore",
            access = AccessInfo(expiresAt = "2026-11-03T00:00:00.000Z", daysLeft = 3, expired = false),
        )
        val p = syncProfileFromServer(base, null, "p1", status)!!.profile
        assertEquals("2026-11-03T00:00:00.000Z", p.expiresAt)
        assertEquals(3, p.daysLeft)
        assertFalse(p.expired)
        assertEquals("Singapore", p.serverRegion)
    }

    @Test
    fun syncClearsExpiryWhenTheResponseSaysNull() {
        val withOld = base.copy(daysLeft = 3, expired = true)
        val status = ProfileStatus(profileId = "p1", access = AccessInfo(expiresAt = null, daysLeft = null, expired = false))
        val p = syncProfileFromServer(withOld, null, "p1", status)!!.profile
        assertNull(p.expiresAt)
        assertNull(p.daysLeft)
        assertFalse(p.expired)
    }

    @Test
    fun syncKeepsLocalExpiryWhenTheResponseHasNone() {
        // 旧后端 / owner：响应里没有到期字段，激活时拿到的到期日不能被冲成空
        val kept = syncProfileFromServer(base.copy(daysLeft = 9), null, "p1", ProfileStatus(profileId = "p1"))!!.profile
        assertEquals("2027-12-31T00:00:00.000Z", kept.expiresAt)
        assertEquals(9, kept.daysLeft)
        assertEquals("Tokyo", kept.serverRegion) // 空的 serverRegion 也不覆盖
    }

    @Test
    fun expiredAutoClearsWhenTheProfileStopsSayingExpired() {
        val expired = base.copy(expired = true, expiresAt = "2026-10-01T00:00:00.000Z")
        assertTrue(accessNotice(expired, null).locksConnect())
        val fresh = syncProfileFromServer(
            expired, null, "p1",
            ProfileStatus(profileId = "p1", access = AccessInfo("2026-12-01T00:00:00.000Z", 40, false)),
        )!!.profile
        assertFalse(accessNotice(fresh, null).locksConnect())
    }

    @Test
    fun profileStoredByVersion11StillDecodes() {
        val stored = """{"profileId":"p1","token":"t","isOwner":false}"""
        val p = Json { ignoreUnknownKeys = true }.decodeFromString<DeviceProfile>(stored)
        assertFalse(p.expired)
        assertNull(p.daysLeft)
    }
}
