package com.redfluent.vpn.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.redfluent.vpn.api.ApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** 1.3 Soft Landing：月额度输入、Recently Deleted、恢复、IPv6 提示、明暗持久化、操作反馈。 */
class SoftLandingLogicTest {

    private fun root(text: String) = Json.parseToJsonElement(text) as JsonObject

    // ---- 月额度：1–2000 的整数；48.5 不许悄悄取整 -----------------------------------------------------

    @Test
    fun quotaAcceptsWholeNumbersInRange() {
        assertEquals(QuotaInput.Result.Ok(48), QuotaInput.parse("48"))
        assertEquals(QuotaInput.Result.Ok(48), QuotaInput.parse(" 48 "))
        assertEquals(QuotaInput.Result.Ok(1), QuotaInput.parse("1"))
        assertEquals(QuotaInput.Result.Ok(2000), QuotaInput.parse("2000"))
        assertEquals(48, QuotaInput.valueOf("48"))
    }

    @Test
    fun quotaRejectsFractionsZeroTooBigAndText() {
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.NOT_WHOLE_NUMBER), QuotaInput.parse("48.5"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.NOT_WHOLE_NUMBER), QuotaInput.parse("48,5"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.OUT_OF_RANGE), QuotaInput.parse("0"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.OUT_OF_RANGE), QuotaInput.parse("2001"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.OUT_OF_RANGE), QuotaInput.parse("99999999999"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.NOT_A_NUMBER), QuotaInput.parse("abc"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.NOT_A_NUMBER), QuotaInput.parse("-5"))
        assertEquals(QuotaInput.Result.Bad(QuotaInput.Problem.EMPTY), QuotaInput.parse(""))
        // 不合法时没有值：保存按钮禁用，绝不会发出 48 或 49
        assertNull(QuotaInput.valueOf("48.5"))
        assertEquals("Whole GB only", QuotaInput.Problem.NOT_WHOLE_NUMBER.message)
        assertEquals(listOf(10, 50, 100, 200), QuotaInput.PRESETS)
    }

    @Test
    fun aFractionalQuotaBlocksTheWholeSave() {
        val invite = OwnerInvite("C1", "Ben", 3, 200, true, region = "tokyo")
        val draft = PersonDraft.from(invite).copy(quotaText = "48.5")
        assertNull(draft.edit(showsRegion = true, showsAccess = true))
        val ok = PersonDraft.from(invite).copy(quotaText = "48").edit(showsRegion = true, showsAccess = true)!!
        val update = buildInviteUpdate(invite, ok)!!
        assertEquals(48, update.monthlyQuotaGB)
        // 六个字段每次都完整带上
        assertTrue(update.toJson().keys.containsAll(setOf("code", "newCode", "label", "maxDevices", "monthlyQuotaGB", "enabled")))
    }

    @Test
    fun ownerRowCannotBePausedOrRecoded() {
        val owner = OwnerInvite("OWN-1", "Max", 6, 1000, true, region = "tokyo")
        val edit = PersonDraft.from(owner).copy(code = "NEW-CODE", enabled = false, quotaText = "999").edit(showsRegion = false, showsAccess = false)!!
        val u = buildInviteUpdate(owner, edit, ProtectedKind.OWNER)!!
        assertEquals("OWN-1", u.newCode)
        assertTrue(u.enabled)
        assertEquals(999, u.monthlyQuotaGB)
        assertNull(u.region)
        assertEquals(DurationChange.Unchanged, u.duration)
    }

    // ---- Recently Deleted -------------------------------------------------------------------------

    @Test
    fun parsesTheDeletedList() {
        val r = parseDeletedInvites(
            root(
                """{"ok":true,"invites":[
                  {"code":"C7","label":"Evan","deletedAt":"2026-10-24T08:00:00.000Z","maxDevices":2,"monthlyQuotaGB":100,"region":"tokyo","deviceCount":1},
                  {"code":"C8","label":"  ","deletedAt":null,"maxDevices":"x","monthlyQuotaGB":1.5,"region":"","deviceCount":0},
                  {"label":"no code"},
                  "junk"]}""",
            ),
        )
        assertTrue(r.ok)
        assertEquals(2, r.invites.size)
        assertEquals(2, r.skipped)
        val evan = r.invites[0]
        assertEquals("Evan", evan.label)
        assertEquals(2, evan.maxDevices)
        assertEquals(100, evan.monthlyQuotaGB)
        assertEquals(1, evan.deviceCount)
        val blank = r.invites[1]
        assertEquals("C8", blank.label) // 名字空着就显示邀请码
        assertNull(blank.maxDevices)
        assertNull(blank.monthlyQuotaGB) // 1.5 不是整数，不当真
        assertNull(blank.region)
        assertFalse(evan.toString().contains("C7"))
    }

    @Test
    fun only404HidesTheRecentlyDeletedEntry() {
        assertTrue(RecentlyDeleted.isUnsupported(404))
        for (code in listOf(400, 401, 403, 409, 500, 502, -1)) assertFalse("$code", RecentlyDeleted.isUnsupported(code))
    }

    @Test
    fun restoreFailuresHaveTheirOwnShortSentences() {
        assertEquals("No longer in Recently Deleted", RecentlyDeleted.restoreFailureText(404, "not found"))
        assertEquals("This code is in use by another invite", RecentlyDeleted.restoreFailureText(409, "conflict"))
        assertEquals("The owner invite cannot be restored", RecentlyDeleted.restoreFailureText(403, "forbidden"))
        val other = RecentlyDeleted.restoreFailureText(400, "code required")
        assertTrue(other.isNotBlank())
        assertFalse(other == RecentlyDeleted.restoreFailureText(404, ""))
    }

    @Test
    fun deletedDetailLine() {
        val now = parseInstantMillis("2026-10-27T08:00:00.000Z")!!
        val item = DeletedInvite("C7", "Evan", "2026-10-24T08:00:00.000Z", 2, 100, "tokyo", 1)
        assertEquals("Deleted 3d ago · 1 device · 100 GB · Tokyo", RecentlyDeleted.detail(item, now))
    }

    // ---- IPv6 提示（与 iOS 同一规则）---------------------------------------------------------------------

    @Test
    fun ipv6HintOnlyOnTokyoWifiWithoutIpv6() {
        assertTrue(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "tokyo"))
        assertTrue(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = " Tokyo "))
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = true, regionId = "tokyo"))
        assertFalse(IPv6Hint.shows(onWiFi = false, wifiHasIPv6 = false, regionId = "tokyo"))
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "singapore"))
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = null))
        assertEquals("Wi-Fi has no IPv6 — switch to cellular", IPv6Hint.TEXT)
    }

    @Test
    fun ipv4BlockedGatesTheIpv6Hint() {
        // true：东京 IPv4 被封 → 没 IPv6 的 Wi-Fi 上照常提示
        assertTrue(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "tokyo", ipv4Blocked = true))
        // false：服务器重建后 IPv4 能通 → 不再提示
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "tokyo", ipv4Blocked = false))
        // 字段没有（旧后端）→ 原来的规则
        assertTrue(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "tokyo", ipv4Blocked = null))
        // true 也不越过其它条件：Wi-Fi 有 IPv6、不在 Wi-Fi、不是东京 → 不提示
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = true, regionId = "tokyo", ipv4Blocked = true))
        assertFalse(IPv6Hint.shows(onWiFi = false, wifiHasIPv6 = false, regionId = "tokyo", ipv4Blocked = true))
        assertFalse(IPv6Hint.shows(onWiFi = true, wifiHasIPv6 = false, regionId = "singapore", ipv4Blocked = true))
    }

    @Test
    fun parsesRuntimeMetaIpv4Blocked() {
        assertEquals(true, parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":{"ipv4Blocked":true}}""")))
        assertEquals(false, parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":{"ipv4Blocked":false}}""")))
        assertNull(parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":{}}""")))
        assertNull(parseIpv4Blocked(root("""{"ok":true}""")))
        assertNull(parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":null}""")))
        assertNull(parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":{"ipv4Blocked":null}}""")))
        assertNull(parseIpv4Blocked(root("""{"ok":true,"runtimeMeta":{"ipv4Blocked":"true"}}"""))) // 字符串不算
    }

    @Test
    fun dualStackTokyoOutboundsPassThroughUntouched() {
        // 东京双栈：proxy 变成 urltest 组（proxy-v4 / proxy-v6 两个 VLESS）。客户端只改 tun 入站，出站和路由一字不动。
        val cfg = root(
            """{"inbounds":[{"type":"tun","tag":"tun-in","strict_route":true,"interface_name":"utun9","stack":"mixed","mtu":1400}],
               "outbounds":[{"type":"urltest","tag":"proxy","outbounds":["proxy-v4","proxy-v6"]},
                            {"type":"vless","tag":"proxy-v4","server":"192.0.2.1","server_port":443},
                            {"type":"vless","tag":"proxy-v6","server":"2001:db8::1","server_port":443},
                            {"type":"direct","tag":"direct"}],
               "route":{"final":"proxy"}}""",
        )
        val out = ApiClient.adaptForAndroid(cfg)
        assertEquals(cfg["outbounds"], out["outbounds"])
        assertEquals(cfg["route"], out["route"])
        val tun = (out["inbounds"] as JsonArray)[0] as JsonObject
        assertFalse("strict_route" in tun)
        assertFalse("interface_name" in tun)
        assertEquals(JsonPrimitive("mixed"), tun["stack"])
        assertEquals(JsonPrimitive(1400), tun["mtu"])
    }

    @Test
    fun ipv4BlockedIsRememberedAndClearedWhenAbsent() = runBlocking {
        val dir = Files.createTempDirectory("rfv-ipv4").toFile()
        val file = File(dir, "test.preferences_pb")
        val job1 = SupervisorJob()
        val store1 = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job1)) { file }
        assertNull(ipv4BlockedFlow(store1).first()) // 从没取过配置 = 没有
        saveIpv4Blocked(store1, true)
        assertEquals(true, ipv4BlockedFlow(store1).first())
        job1.cancelAndJoin() // 模拟 app 退出再开

        val job2 = SupervisorJob()
        val store2 = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job2)) { file }
        assertEquals(true, ipv4BlockedFlow(store2).first())
        saveIpv4Blocked(store2, false)
        assertEquals(false, ipv4BlockedFlow(store2).first())
        saveIpv4Blocked(store2, null) // 新配置里没有这个字段 → 回到原规则
        assertNull(ipv4BlockedFlow(store2).first())
        job2.cancelAndJoin()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun routableIpv6NeedsAGlobalAddressAndADefaultRoute() {
        val global = InetAddress.getByName("2606:4700:4700::1111")
        val linkLocal = InetAddress.getByName("fe80::1")
        val ula = InetAddress.getByName("fd00::1")
        val doc = InetAddress.getByName("2001:db8::1")
        val v4 = InetAddress.getByName("192.168.1.20")
        assertTrue(hasRoutableIPv6(listOf(v4, global), hasIPv6DefaultRoute = true))
        assertFalse(hasRoutableIPv6(listOf(v4, global), hasIPv6DefaultRoute = false))
        assertFalse(hasRoutableIPv6(listOf(v4, linkLocal, ula, doc), hasIPv6DefaultRoute = true))
        assertFalse(hasRoutableIPv6(emptyList(), hasIPv6DefaultRoute = true))
    }

    @Test
    fun ipv6EntryDisplay() {
        assertEquals(IPv6Display.IPv4Only, IPv6Display.make(null))
        assertEquals(IPv6Display.IPv4Only, IPv6Display.make("  "))
        val a = IPv6Display.make("2001:db8:1000:5bd7:0:0:0:be10") as IPv6Display.Address
        assertEquals("2001:db8:1000:5bd7:", a.prefix)
        assertEquals(":be10", a.host)
    }

    // ---- 明暗：存下来、再开还在；默认浅色 ----------------------------------------------------------------

    @Test
    fun appearancePersistsAcrossStoreInstances() = runBlocking {
        val dir = Files.createTempDirectory("rfv-appearance").toFile()
        val file = File(dir, "test.preferences_pb")
        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(Dispatchers.IO + job1)
        val store1 = PreferenceDataStoreFactory.create(scope = scope1) { file }
        assertEquals(Appearance.LIGHT, appearanceFlow(store1).first()) // 什么都没存 = 浅色
        saveAppearance(store1, Appearance.DARK)
        assertEquals(Appearance.DARK, appearanceFlow(store1).first())
        job1.cancelAndJoin() // 关掉第一个实例（模拟 app 退出）

        val job2 = SupervisorJob()
        val scope2 = CoroutineScope(Dispatchers.IO + job2)
        val store2 = PreferenceDataStoreFactory.create(scope = scope2) { file }
        assertEquals(Appearance.DARK, appearanceFlow(store2).first()) // 重开 app 还是深色
        saveAppearance(store2, Appearance.LIGHT)
        assertEquals(Appearance.LIGHT, appearanceFlow(store2).first())
        job2.cancelAndJoin()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun unknownStoredAppearanceFallsBackToLight() {
        assertEquals(Appearance.LIGHT, Appearance.from(null))
        assertEquals(Appearance.LIGHT, Appearance.from("system"))
        assertEquals(Appearance.DARK, Appearance.from("dark"))
        assertEquals(Appearance.LIGHT, Appearance.from("light"))
    }

    // ---- 操作反馈（忙 / 成功 / 失败 / 收起）-------------------------------------------------------------

    @Test
    fun feedbackStartSucceedFail() {
        val s0 = ActionFeedback()
        val busy = FeedbackMachine.start(s0, OwnerAction.SAVE)!!
        assertTrue(busy.isBusy(OwnerAction.SAVE))
        assertNull(busy.banner)
        // 忙的时候不接第二个操作（连点两下不会发两次请求）
        assertNull(FeedbackMachine.start(busy, OwnerAction.SAVE))
        assertNull(FeedbackMachine.start(busy, OwnerAction.DELETE))

        val ok = FeedbackMachine.succeed(busy, FeedbackText.SAVED)
        assertNull(ok.busy)
        assertEquals(Banner(1, "Saved", success = true), ok.banner)

        val again = FeedbackMachine.start(ok, OwnerAction.SAVE)!!
        assertNull(again.banner) // 开始新操作时旧横幅收起
        val bad = FeedbackMachine.fail(again, "This code is in use by another invite")
        assertNull(bad.busy) // 失败后按钮恢复可点
        assertEquals(2L, bad.banner!!.seq)
        assertFalse(bad.banner!!.success)
        assertTrue(FeedbackMachine.start(bad, OwnerAction.SAVE) != null)
    }

    @Test
    fun feedbackDismissOnlyClosesItsOwnBanner() {
        val a = FeedbackMachine.succeed(ActionFeedback(), "A")
        val b = FeedbackMachine.fail(FeedbackMachine.start(a, OwnerAction.RESTORE)!!, "B")
        assertEquals(b, FeedbackMachine.dismiss(b, a.banner!!.seq)) // 迟到的「收起 A」不能收掉 B
        assertNull(FeedbackMachine.dismiss(b, b.banner!!.seq).banner)
        assertEquals("Something went wrong.", FeedbackMachine.fail(ActionFeedback(), "  ").banner!!.text)
        assertTrue(FeedbackMachine.showMillis(b.banner!!) > FeedbackMachine.showMillis(a.banner!!))
    }

    @Test
    fun feedbackTexts() {
        assertEquals("1 device logged out", FeedbackText.loggedOutAll(1))
        assertEquals("3 devices logged out", FeedbackText.loggedOutAll(3))
        assertEquals("Evan restored · Devices need to sign in again", FeedbackText.restored("Evan"))
        assertEquals("Signed out", SIGNED_OUT_NOTICE)
    }
}
