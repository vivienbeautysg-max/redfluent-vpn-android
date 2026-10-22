package com.redfluent.vpn.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 Users 页：邀请码解析、状态行、受保护的邀请码、更新请求体。 */
class InvitesTest {

    private fun root(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun invite(
        code: String = "CODE-1",
        label: String = "Alice",
        region: String? = "tokyo",
        durationDays: Int? = null,
        activatedAt: String? = null,
        effective: String? = null,
        daysLeft: Int? = null,
        expired: Boolean = false,
        legacy: String? = null,
        enabled: Boolean = true,
    ) = OwnerInvite(
        code = code, label = label, enabled = enabled, region = region, durationDays = durationDays,
        activatedAt = activatedAt, effectiveExpiresAt = effective, expiresAt = legacy, daysLeft = daysLeft, expired = expired,
    )

    // ---- 解码（旧后端 / 坏字段不能让整张表消失）-------------------------------------------------------

    @Test
    fun decodesTheNewFields() {
        val r = parseOwnerInvites(
            root(
                """{"ok":true,"invites":[{"code":"C1","label":"Alice","maxDevices":3,"enabled":true,"region":"singapore",
                    "durationDays":30,"activatedAt":"2026-10-01T00:00:00.000Z","effectiveExpiresAt":"2026-10-31T00:00:00.000Z",
                    "daysLeft":9,"expired":false,"unknownField":1}]}""",
            ),
        )
        assertTrue(r.ok)
        val i = r.invites.single()
        assertEquals("singapore", i.regionId)
        assertEquals(30, i.durationDays)
        assertEquals(9, i.daysLeft)
        assertFalse(i.expired)
    }

    @Test
    fun oldBackendWithoutNewFieldsStillDecodes() {
        val r = parseOwnerInvites(root("""{"ok":true,"invites":[{"code":"C1","label":"Alice","enabled":true,"expiresAt":"2027-01-01T00:00:00.000Z"}]}"""))
        val i = r.invites.single()
        assertEquals("tokyo", i.regionId) // 没有 region = 东京
        assertNull(i.durationDays)
        assertEquals("Expires 1 Jan 2027", inviteStatusLine(i)) // 旧的固定到期日照样显示
    }

    @Test
    fun wrongTypedNewFieldsKeepTheInviteListed() {
        val r = parseOwnerInvites(
            root(
                """{"ok":true,"invites":[
                    {"code":"C1","label":"Good","region":"tokyo","durationDays":7},
                    {"code":"C2","label":"Broken","region":5,"durationDays":"thirty","daysLeft":"x","expired":"maybe"},
                    "not an object",
                    {"code":"C3","label":"Worse","enabled":"yes"}]}""",
            ),
        )
        assertEquals(listOf("Good", "Broken"), r.invites.map { it.label })
        assertEquals(2, r.skipped) // 非对象的元素 + enabled 格式不对的那条
        assertEquals("tokyo", r.invites[1].regionId)
        assertNull(r.invites[1].durationDays)
    }

    @Test
    fun badTopLevelDegrades() {
        assertFalse(parseOwnerInvites(root("""{"invites":[]}""")).ok)
        assertTrue(parseOwnerInvites(root("""{"ok":true,"invites":"x"}""")).invites.isEmpty())
        assertEquals("boom", parseOwnerInvites(root("""{"ok":false,"error":"boom"}""")).error)
    }

    @Test
    fun theCodeNeverShowsUpInToString() {
        val i = invite(code = "SECRET-CODE-123", label = "Alice")
        assertFalse(i.toString().contains("SECRET-CODE-123"))
        assertFalse(InviteUpdate("SECRET-CODE-123", "singapore", DurationChange.Unchanged).toString().contains("SECRET-CODE-123"))
    }

    @Test
    fun sortsByLabelIgnoringCase() {
        val sorted = sortInvites(listOf(invite(label = "bob"), invite(label = "Alice"), invite(label = "Carol")))
        assertEquals(listOf("Alice", "bob", "Carol"), sorted.map { it.label })
    }

    // ---- 状态行 ------------------------------------------------------------------------------------

    @Test
    fun statusLineVariants() {
        assertEquals(
            "Expires 21 Nov 2026 · 29 days left",
            inviteStatusLine(invite(durationDays = 30, activatedAt = "2026-10-22T00:00:00.000Z", effective = "2026-11-21T00:00:00.000Z", daysLeft = 29)),
        )
        assertEquals(
            "Expires 21 Nov 2026 · 1 day left",
            inviteStatusLine(invite(effective = "2026-11-21T00:00:00.000Z", daysLeft = 1)),
        )
        assertEquals(
            "Expires 21 Nov 2026 · today",
            inviteStatusLine(invite(effective = "2026-11-21T00:00:00.000Z", daysLeft = 0)),
        )
        assertEquals(
            "Expired 3 Nov 2026",
            inviteStatusLine(invite(effective = "2026-11-03T00:00:00.000Z", daysLeft = -4, expired = true)),
        )
        assertEquals("Expired", inviteStatusLine(invite(expired = true)))
        assertEquals("Starts on activation", inviteStatusLine(invite(durationDays = 30)))
        assertEquals("No limit", inviteStatusLine(invite()))
        assertEquals("No limit", inviteStatusLine(invite(durationDays = 30, activatedAt = "2026-10-01T00:00:00.000Z"))) // 老后端：不知道生效日期
        assertEquals("Expires 31 Dec 2027", inviteStatusLine(invite(legacy = "2027-12-31T00:00:00.000Z")))
    }

    // ---- 受保护的邀请码 ------------------------------------------------------------------------------

    @Test
    fun ownerInviteIsRecognisedByCodeOrByLabel() {
        val owner = invite(code = "RF-OWNER-ABC", label = "Boss")
        assertEquals(ProtectedKind.OWNER, protectedKind(owner, "rf-owner-abc", null)) // 不区分大小写
        assertEquals(ProtectedKind.OWNER, protectedKind(owner, " RF-OWNER-ABC ", "x")) // trim
        assertEquals(ProtectedKind.OWNER, protectedKind(owner, null, "boss")) // 激活用的码没存下来时，用标签
        assertNull(protectedKind(invite(code = "OTHER", label = "Alice"), "RF-OWNER-ABC", "Boss"))
    }

    @Test
    fun codeComparisonUsesNfkc() {
        assertTrue(sameInviteCode("ＲＦ－ＯＷＮＥＲ", "RF-OWNER")) // 全角
        assertFalse(sameInviteCode("", ""))
        assertFalse(sameInviteCode(null, "x"))
    }

    @Test
    fun blankOwnerLabelDoesNotMatchBlankLabels() {
        assertNull(protectedKind(invite(code = "A", label = ""), null, ""))
        assertNull(protectedKind(invite(code = "A", label = "  "), null, "  "))
    }

    @Test
    fun reviewInviteIsRecognisedByLabel() {
        assertEquals(ProtectedKind.REVIEW, protectedKind(invite(label = "Apple Review"), null, null))
        assertEquals(ProtectedKind.REVIEW, protectedKind(invite(label = " apple review "), "x", "Boss"))
    }

    // ---- 编辑：只发改了的字段 -----------------------------------------------------------------------------

    @Test
    fun nothingChangedMeansNoUpdate() {
        val i = invite(region = "tokyo", durationDays = 30)
        assertNull(buildInviteUpdate(i, "tokyo", AccessChoice.Days(30)))
        assertNull(buildInviteUpdate(invite(region = null), "tokyo", AccessChoice.NoLimit)) // 没 region = 东京
        assertNull(buildInviteUpdate(invite(), "Tokyo", AccessChoice.NoLimit))
    }

    @Test
    fun regionOnlySendsOnlyRegion() {
        val u = buildInviteUpdate(invite(), "singapore", AccessChoice.NoLimit)!!
        assertEquals(InviteUpdate("CODE-1", "singapore", DurationChange.Unchanged), u)
        assertEquals(setOf("code", "region"), u.toJson().keys)
        assertEquals("singapore", (u.toJson()["region"] as JsonPrimitive).content)
    }

    @Test
    fun durationOnlySendsOnlyDuration() {
        val u = buildInviteUpdate(invite(durationDays = 30), "tokyo", AccessChoice.Days(90))!!
        assertNull(u.region)
        assertEquals(DurationChange.Set(90), u.duration)
        assertEquals(setOf("code", "durationDays"), u.toJson().keys)
        assertEquals(90, (u.toJson()["durationDays"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun clearingTheDurationSendsAnExplicitNull() {
        val u = buildInviteUpdate(invite(durationDays = 30), "tokyo", AccessChoice.NoLimit)!!
        assertEquals(DurationChange.Clear, u.duration)
        assertEquals(setOf("code", "durationDays"), u.toJson().keys)
        assertEquals(JsonNull, u.toJson()["durationDays"])
    }

    @Test
    fun settingADurationOnAnInviteWithoutOne() {
        val u = buildInviteUpdate(invite(durationDays = null), "tokyo", AccessChoice.Days(7))!!
        assertEquals(DurationChange.Set(7), u.duration)
    }

    @Test
    fun bothFieldsTogether() {
        val u = buildInviteUpdate(invite(durationDays = 30), "singapore", AccessChoice.Days(365))!!
        assertEquals(setOf("code", "region", "durationDays"), u.toJson().keys)
    }

    @Test
    fun invalidCustomDaysAreNeverSent() {
        assertNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(0)))
        assertNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(-5)))
        assertNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(3651)))
        assertNotNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(3650)))
        assertNotNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(1)))
    }

    @Test
    fun ownerInviteCanNeverBeChanged() {
        assertNull(buildInviteUpdate(invite(), "singapore", AccessChoice.Days(7), ProtectedKind.OWNER))
    }

    @Test
    fun reviewInviteOnlyTakesARegion() {
        assertNull(buildInviteUpdate(invite(), "tokyo", AccessChoice.Days(7), ProtectedKind.REVIEW))
        val u = buildInviteUpdate(invite(), "singapore", AccessChoice.Days(7), ProtectedKind.REVIEW)!!
        assertEquals(DurationChange.Unchanged, u.duration)
        assertEquals(setOf("code", "region"), u.toJson().keys)
    }

    @Test
    fun regionIsNormalised() {
        assertEquals("singapore", buildInviteUpdate(invite(), " Singapore ", AccessChoice.NoLimit)!!.region)
    }

    // ---- 面板里的选项 -------------------------------------------------------------------------------------

    @Test
    fun accessInitialChoice() {
        assertEquals(AccessChoice.NoLimit, accessChoiceOf(invite()))
        assertEquals(AccessChoice.Days(30), accessChoiceOf(invite(durationDays = 30)))
        assertEquals(AccessChoice.Days(45), accessChoiceOf(invite(durationDays = 45)))
        assertEquals(listOf(7, 30, 90, 180, 365), ACCESS_PRESETS)
    }

    @Test
    fun customDaysParsing() {
        assertEquals(7, parseCustomDays("7"))
        assertEquals(365, parseCustomDays(" 365 "))
        assertEquals(3650, parseCustomDays("3650"))
        assertEquals(1, parseCustomDays("1"))
        for (bad in listOf("", " ", "0", "-5", "3651", "abc", "12.5", "1e3")) assertNull("should be null: $bad", parseCustomDays(bad))
    }

    @Test
    fun regionChoicesRespectTheOwnersRegions() {
        val tokyo = RegionOption("tokyo", "Tokyo", true)
        val sg = RegionOption("singapore", "Singapore")
        assertEquals(listOf("tokyo", "singapore"), regionChoices(listOf(tokyo, sg), "tokyo").map { it.id })
        assertEquals(listOf("tokyo"), regionChoices(listOf(tokyo), "tokyo").map { it.id }) // 新加坡只有 owner 自己能用才出现
        assertEquals(listOf("tokyo"), regionChoices(emptyList(), null).map { it.id })
        assertEquals(listOf("tokyo", "singapore"), regionChoices(listOf(sg), "tokyo").map { it.id }) // 东京永远在前
        // 已分配的区域即使 owner 暂时用不了，也要能在面板里表示出来
        assertEquals(listOf("tokyo", "singapore"), regionChoices(listOf(tokyo), "singapore").map { it.id })
    }

    @Test
    fun saveErrorsAreOneShortSentence() {
        assertEquals("This region is unavailable right now.", inviteErrorText(400, "region unavailable"))
        assertEquals("Invalid duration.", inviteErrorText(400, "invalid duration"))
        assertEquals("Owner invite cannot be changed.", inviteErrorText(400, "owner invite cannot be changed"))
        assertEquals("User not found.", inviteErrorText(404, "invite not found"))
        assertEquals("Network error: timeout", inviteErrorText(-1, "Network error: timeout"))
        assertEquals("Bad gateway.", inviteErrorText(502, "<!DOCTYPE html><p>x</p>"))
        val long = inviteErrorText(400, "x".repeat(500))
        assertTrue(long.length <= MAX_ERROR_REASON_LENGTH + 2)
    }
}
