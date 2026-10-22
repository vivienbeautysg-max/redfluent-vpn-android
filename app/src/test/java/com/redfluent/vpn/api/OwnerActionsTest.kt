package com.redfluent.vpn.api

import com.redfluent.vpn.data.OwnerDevice
import com.redfluent.vpn.data.UsageGroup
import com.redfluent.vpn.data.groupByUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1「Log out all devices」：优先一次性接口，接口不存在才逐台回退，其它错误绝不回退。 */
class OwnerActionsTest {

    private class FakeApi(
        private val logoutUser: (String) -> Int? = { 1 },
        private val revoke: (String) -> Unit = {},
    ) : OwnerLogoutApi {
        val logoutCalls = mutableListOf<String>()
        val revokeCalls = mutableListOf<String>()
        override suspend fun logoutUser(code: String): Int? {
            logoutCalls += code
            return logoutUser.invoke(code)
        }

        override suspend fun revokeDevice(profileId: String) {
            revokeCalls += profileId
            revoke(profileId)
        }
    }

    private fun dev(id: String, code: String? = "CODE-A", enabled: Boolean = true, revokedAt: String? = null) =
        OwnerDevice(profileId = id, inviteCode = code, inviteLabel = "Alice", displayName = id, enabled = enabled, revokedAt = revokedAt)

    private fun group(vararg devices: OwnerDevice, ownerCode: String? = null): UsageGroup =
        groupByUser(devices.toList(), ownerCode, null).single()

    private fun run(api: OwnerLogoutApi, g: UsageGroup): LogoutAllResult = runBlocking { logOutAll(api, g) }

    @Test
    fun usesTheOneShotEndpointWhenItExists() {
        val api = FakeApi()
        val result = run(api, group(dev("d1"), dev("d2")))
        assertEquals(LogoutAllResult.Done(viaFallback = false), result)
        assertEquals(listOf("CODE-A"), api.logoutCalls)
        assertTrue(api.revokeCalls.isEmpty())
    }

    @Test
    fun fallsBackToPerDeviceRevokeWhenTheEndpointIsMissing() {
        val api = FakeApi(logoutUser = { throw ApiException(404, "not found") })
        val result = run(api, group(dev("d1"), dev("d2"), dev("gone", revokedAt = "2026-10-20", enabled = false)))
        assertEquals(LogoutAllResult.Done(viaFallback = true), result)
        assertEquals(listOf("d1", "d2"), api.revokeCalls.sorted()) // 已登出的不再动
        assertEquals(1, api.logoutCalls.size)
    }

    @Test
    fun fallbackWorksWhenTheBackendDidNotReturnTheInviteCode() {
        val api = FakeApi()
        val result = run(api, group(dev("d1", code = null), dev("d2", code = null)))
        assertEquals(LogoutAllResult.Done(viaFallback = true), result)
        assertTrue(api.logoutCalls.isEmpty())
        assertEquals(listOf("d1", "d2"), api.revokeCalls.sorted())
    }

    @Test
    fun aMissingInviteIsReportedNotRetriedPerDevice() {
        val api = FakeApi(logoutUser = { throw ApiException(404, "invite not found") })
        val result = run(api, group(dev("d1")))
        assertEquals(LogoutAllResult.Failed("User not found."), result)
        assertTrue(api.revokeCalls.isEmpty())
    }

    @Test
    fun otherErrorsAreNeverRetriedPerDevice() {
        for ((code, reason, text) in listOf(
            Triple(403, "owner invite cannot be logged out", "Not allowed."),
            Triple(401, "valid owner profile required", "Valid owner profile required."),
            Triple(500, "boom", "Boom."),
            Triple(-1, "Network error: timeout", "Network error: timeout"),
        )) {
            val api = FakeApi(logoutUser = { throw ApiException(code, reason) })
            val result = run(api, group(dev("d1")))
            assertEquals("$code", LogoutAllResult.Failed(text), result)
            assertTrue("$code", api.revokeCalls.isEmpty())
        }
    }

    @Test
    fun oneFailingDeviceDoesNotStopTheOthers() {
        val api = FakeApi(
            logoutUser = { throw ApiException(404, "not found") },
            revoke = { if (it == "d2") throw ApiException(500, "boom") },
        )
        val result = run(api, group(dev("d1"), dev("d2"), dev("d3")))
        assertEquals(LogoutAllResult.Failed("Could not log out 1 of 3 devices."), result)
        assertEquals(listOf("d1", "d2", "d3"), api.revokeCalls.sorted())
    }

    @Test
    fun everyDeviceFailingIsOneShortSentence() {
        val api = FakeApi(logoutUser = { throw ApiException(404, "not found") }, revoke = { throw ApiException(-1, "Network error: x") })
        assertEquals(LogoutAllResult.Failed("Could not log out."), run(api, group(dev("d1"), dev("d2"))))
    }

    @Test
    fun theOwnerInviteIsNeverTouched() {
        val api = FakeApi()
        val result = run(api, group(dev("o1", code = "OWNER-CODE"), ownerCode = "owner-code"))
        assertEquals(LogoutAllResult.Failed("Not allowed."), result)
        assertTrue(api.logoutCalls.isEmpty() && api.revokeCalls.isEmpty())
    }

    @Test
    fun aUserWithNoActiveDeviceIsNotTouched() {
        val api = FakeApi()
        val result = run(api, group(dev("d1", revokedAt = "2026-10-20", enabled = false)))
        assertEquals(LogoutAllResult.Failed("Not allowed."), result)
        assertTrue(api.logoutCalls.isEmpty() && api.revokeCalls.isEmpty())
    }
}
