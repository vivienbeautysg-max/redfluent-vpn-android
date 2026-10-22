package com.redfluent.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3：token 续期与 /profile 刷新只能作用于「发请求时的那个账号」。 */
class ProfileSyncTest {

    private val tokyo = RegionOption("tokyo", "Tokyo", true)
    private val singapore = RegionOption("singapore", "Singapore", false)

    private fun profile(id: String = "p1", token: String = "t-old") =
        DeviceProfile(profileId = id, token = token, ownerLabel = "Alice")

    private fun status(id: String = "p1", owner: Boolean = false, regions: List<RegionOption> = listOf(tokyo)) =
        ProfileStatus(profileId = id, isOwner = owner, regions = regions)

    // ---- token 续期 -------------------------------------------------------------

    @Test
    fun renewalAppliesWhenStoredTokenIsTheOneThatMadeTheRequest() {
        val renewed = profile(token = "t-old").withRenewedToken("t-old", "t-new")
        assertEquals("t-new", renewed.token)
        assertEquals("p1", renewed.profileId)
        assertEquals("Alice", renewed.ownerLabel)
    }

    @Test
    fun renewalIsDiscardedWhenStoredTokenChangedMeanwhile() {
        // 请求在途时设备被换成了另一个账号（token 不同）：迟到的续期结果不能写进去
        val other = profile(id = "p2", token = "t-other")
        val after = other.withRenewedToken("t-old", "t-new")
        assertSame(other, after)
        assertEquals("t-other", after.token)
    }

    @Test
    fun secondRenewalWithTheSameOldTokenLoses() {
        val first = profile(token = "t-old").withRenewedToken("t-old", "t-new-1")
        val second = first.withRenewedToken("t-old", "t-new-2")
        assertEquals("t-new-1", second.token)
        // 用新 token 发的请求回来的续期仍然有效
        assertEquals("t-new-3", second.withRenewedToken("t-new-1", "t-new-3").token)
    }

    // ---- /profile 刷新 -----------------------------------------------------------

    @Test
    fun syncAppliesOwnerAndRegionsAndKeepsEverythingElse() {
        val current = profile()
        val result = syncProfileFromServer(current, "singapore", "p1", status(owner = true, regions = listOf(tokyo, singapore)))
        assertNotNull(result)
        assertTrue(result!!.profile.isOwner)
        assertEquals(listOf("tokyo", "singapore"), result.profile.regions.map { it.id })
        assertEquals("t-old", result.profile.token)
        assertEquals("Alice", result.profile.ownerLabel)
        assertEquals("singapore", result.region)
    }

    @Test
    fun syncResetsRegionInTheSameResultWhenServerStopsOfferingIt() {
        val result = syncProfileFromServer(profile(), "singapore", "p1", status(owner = true, regions = listOf(tokyo)))!!
        assertEquals("tokyo", result.region)
        assertEquals(listOf("tokyo"), result.profile.regions.map { it.id })
    }

    @Test
    fun syncDiscardsWhenThereIsNoProfileAnymore() {
        assertNull(syncProfileFromServer(null, "tokyo", "p1", status()))
    }

    @Test
    fun syncDiscardsWhenDeviceWasSwitchedToAnotherAccountDuringTheRequest() {
        // 发请求时是 p1，写回时本地已经是 p2（退出后用别的邀请码重新激活）
        assertNull(syncProfileFromServer(profile(id = "p2"), "tokyo", "p1", status(id = "p1", owner = true, regions = listOf(tokyo, singapore))))
    }

    @Test
    fun syncDiscardsWhenTheResponseIsForADifferentProfile() {
        assertNull(syncProfileFromServer(profile(id = "p1"), "tokyo", "p1", status(id = "p9", owner = true)))
        assertNull(syncProfileFromServer(profile(id = "p1"), "tokyo", "p1", status(id = "", owner = true)))
    }

    @Test
    fun syncWithNeverSelectedRegionResolvesToServerDefault() {
        val result = syncProfileFromServer(profile(), null, "p1", status())!!
        assertEquals("tokyo", result.region)
        assertFalse(result.profile.isOwner)
    }
}
