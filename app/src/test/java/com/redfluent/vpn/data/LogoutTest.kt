package com.redfluent.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1：owner 登出设备 / 用户的资格与文案；设备被登出后回到激活页的判定。 */
class LogoutTest {

    private fun dev(
        id: String,
        label: String = "Alice",
        code: String? = "CODE-A",
        enabled: Boolean = true,
        revokedAt: String? = null,
    ) = OwnerDevice(profileId = id, inviteCode = code, inviteLabel = label, displayName = "Dev $id", enabled = enabled, revokedAt = revokedAt)

    private fun groupOf(vararg devices: OwnerDevice, ownerCode: String? = null, ownerLabel: String? = null): UsageGroup =
        groupByUser(devices.toList(), ownerCode, ownerLabel).single()

    // ---- 资格 ---------------------------------------------------------------------------------

    @Test
    fun activeDevicesOfAMemberCanBeLoggedOut() {
        val g = groupOf(dev("d1"), dev("d2"))
        assertTrue(canLogOutDevice(g, g.devices[0]))
        assertTrue(canLogOutAll(g))
    }

    @Test
    fun ownerInviteHasNoLogoutAtAll() {
        val g = groupOf(dev("o1", "Boss", "OWNER-CODE"), ownerCode = "owner-code")
        assertTrue(g.isOwnerInvite)
        assertFalse(canLogOutDevice(g, g.devices[0]))
        assertFalse(canLogOutAll(g))
        assertTrue(logoutTargets(g).isEmpty())
        val byLabel = groupOf(dev("o1", "Boss", null), ownerLabel = "Boss")
        assertFalse(canLogOutAll(byLabel))
    }

    @Test
    fun signedOutAndDisabledDevicesHaveNoAction() {
        val g = groupOf(
            dev("d1"),
            dev("d2", revokedAt = "2026-10-20 10:00:00", enabled = false),
            dev("d3", enabled = false),
        )
        assertEquals(listOf("d1"), g.devices.filter { canLogOutDevice(g, it) }.map { it.profileId })
        assertEquals(listOf("d1"), logoutTargets(g).map { it.profileId })
    }

    @Test
    fun logoutAllNeedsAtLeastOneActiveDevice() {
        val allGone = groupOf(dev("d1", revokedAt = "2026-10-20", enabled = false))
        assertFalse(canLogOutAll(allGone))
        assertEquals(0, allGone.activeDevices)
    }

    @Test
    fun lookupOnlyFindsWhatMayBeLoggedOut() {
        val groups = groupByUser(
            listOf(dev("m1"), dev("m2", revokedAt = "x", enabled = false), dev("o1", "Boss", "OWNER-CODE")),
            ownerCode = "OWNER-CODE",
        )
        assertNotNull(findLoggableDevice(groups, "m1"))
        assertNull(findLoggableDevice(groups, "m2")) // 已登出
        assertNull(findLoggableDevice(groups, "o1")) // owner 自己的
        assertNull(findLoggableDevice(groups, "nope"))
        val member = groups.first { !it.isOwnerInvite }
        assertNotNull(findLoggableGroup(groups, member.key))
        assertNull(findLoggableGroup(groups, groups.first { it.isOwnerInvite }.key))
        assertNull(findLoggableGroup(groups, "c:unknown"))
    }

    // ---- 文案 ---------------------------------------------------------------------------------

    @Test
    fun confirmationWording() {
        assertEquals("Log out Alice iPhone?", confirmDeviceTitle("Alice iPhone"))
        assertEquals("It will need the invite code to reconnect.", CONFIRM_DEVICE_MESSAGE)
        assertEquals("Log out all 3 devices of Alice?", confirmAllTitle(3, "Alice"))
        assertEquals("Log out all 2 devices of Alice?", confirmAllTitle(2, "Alice"))
        assertEquals("Log out 1 device of Alice?", confirmAllTitle(1, "Alice"))
    }

    @Test
    fun errorWording() {
        assertEquals("Not allowed.", logoutErrorText(403, "owner device cannot be revoked here"))
        assertEquals("User not found.", logoutErrorText(404, "invite not found"))
        assertEquals("Device not found.", logoutErrorText(404, "device not found"))
        assertEquals("Network error: timeout", logoutErrorText(-1, "Network error: timeout"))
        assertEquals("Server error.", logoutErrorText(500, "server error"))
        assertEquals("Bad gateway.", logoutErrorText(502, "<!DOCTYPE html><p>x</p>"))
        assertEquals("Could not log out.", partialLogoutText(2, 2))
        assertEquals("Could not log out 1 of 3 devices.", partialLogoutText(1, 3))
    }

    // ---- 接口不存在时才逐台回退 ----------------------------------------------------------------

    @Test
    fun fallbackOnlyWhenTheEndpointIsMissing() {
        assertTrue(logoutAllUnsupported(404, "not found")) // 整页 HTML 的 404 → "not found"
        assertTrue(logoutAllUnsupported(404, "Cannot POST /owner/users/logout"))
        assertFalse(logoutAllUnsupported(404, "invite not found")) // 接口在，邀请码不存在
        assertFalse(logoutAllUnsupported(403, "owner invite cannot be logged out"))
        assertFalse(logoutAllUnsupported(401, "valid owner profile required"))
        assertFalse(logoutAllUnsupported(500, "boom"))
        assertFalse(logoutAllUnsupported(-1, "Network error: x"))
        assertFalse(logoutAllUnsupported(400, "bad request"))
    }

    // ---- 设备这一侧：被登出 → 激活页 --------------------------------------------------------------

    private val profile = DeviceProfile(profileId = "p1", token = "t")

    @Test
    fun signedOutOnlyWhenTheServerSaysEnabledFalseForThisAccount() {
        assertTrue(isSignedOutByProfile(profile, "p1", ProfileStatus(profileId = "p1", enabled = false)))
        assertFalse(isSignedOutByProfile(profile, "p1", ProfileStatus(profileId = "p1", enabled = true)))
        // 没带 enabled（默认）≠ 被登出
        assertFalse(isSignedOutByProfile(profile, "p1", ProfileStatus(profileId = "p1")))
    }

    @Test
    fun aStaleOrForeignResponseNeverSignsTheCurrentAccountOut() {
        val off = ProfileStatus(profileId = "p1", enabled = false)
        assertFalse(isSignedOutByProfile(null, "p1", off)) // 本地已经没有账号
        assertFalse(isSignedOutByProfile(profile.copy(profileId = "p2"), "p1", off)) // 请求在途时换了账号
        assertFalse(isSignedOutByProfile(profile, "p1", ProfileStatus(profileId = "other", enabled = false))) // 响应不是给它的
    }

    // ---- /profile 信封 → ProfileStatus：缺少 enabled 绝不等于被登出 ----------------------------------

    private fun statusOf(text: String): ProfileStatus {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        return profileStatusFrom(json.decodeFromJsonElement(Envelope.serializer(), raw), raw)
    }

    @Test
    fun profileEnabledFlagMapping() {
        assertFalse(statusOf("""{"ok":true,"profileId":"p1","enabled":false}""").enabled)
        assertTrue(statusOf("""{"ok":true,"profileId":"p1","enabled":true}""").enabled)
        // 旧后端 / 缺字段：不是"被登出"
        assertTrue(statusOf("""{"ok":true,"profileId":"p1"}""").enabled)
        assertTrue(statusOf("""{"ok":true,"profileId":"p1","enabled":null}""").enabled)
        assertFalse(isSignedOutByProfile(profile, "p1", statusOf("""{"ok":true,"profileId":"p1"}""")))
        assertTrue(isSignedOutByProfile(profile, "p1", statusOf("""{"ok":true,"profileId":"p1","enabled":false}""")))
    }

    @Test
    fun noticeWording() {
        assertEquals("Signed out. Enter your invite code to reconnect.", SIGNED_OUT_NOTICE)
    }
}
