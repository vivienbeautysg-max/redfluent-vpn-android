package com.redfluent.vpn.api

import com.redfluent.vpn.data.UsageGroup
import com.redfluent.vpn.data.logoutAllUnsupported
import com.redfluent.vpn.data.logoutErrorText
import com.redfluent.vpn.data.logoutTargets
import com.redfluent.vpn.data.partialLogoutText

/** owner 的登出接口（抽出来是为了在 JVM 测试里用假的实现验证回退逻辑）。 */
interface OwnerLogoutApi {
    /** POST /owner/users/logout；返回服务器报告的台数。接口不存在时抛 ApiException(404)。 */
    suspend fun logoutUser(code: String): Int?

    /** POST /owner/devices/revoke。 */
    suspend fun revokeDevice(profileId: String)
}

sealed interface LogoutAllResult {
    /** 全部登出了。viaFallback = 用的是逐台回退。 */
    data class Done(val viaFallback: Boolean) : LogoutAllResult

    data class Failed(val message: String) : LogoutAllResult
}

/**
 * 把一个用户（邀请码）的所有可用设备登出：
 *  1. 优先 POST /owner/users/logout {code}（一次原子完成）；
 *  2. 接口还不存在（404 且不是"邀请码不存在"），或后端没给 inviteCode → 逐台 POST /owner/devices/revoke，
 *     只处理这个用户当前仍可用的设备，某台失败不影响其余，最后汇总；
 *  3. 其它错误（403 owner 邀请码 / 5xx / 网络）原样报告，不回退。
 * owner 自己的邀请码没有目标设备，直接失败（界面本来就不会显示这个操作）。
 */
suspend fun logOutAll(api: OwnerLogoutApi, group: UsageGroup): LogoutAllResult {
    val targets = logoutTargets(group)
    if (targets.isEmpty()) return LogoutAllResult.Failed("Not allowed.")

    val code = group.code
    if (code != null) {
        try {
            api.logoutUser(code)
            return LogoutAllResult.Done(viaFallback = false)
        } catch (e: ApiException) {
            if (!logoutAllUnsupported(e.code, e.reason)) {
                return LogoutAllResult.Failed(logoutErrorText(e.code, e.reason))
            }
        }
    }

    var failed = 0
    for (device in targets) {
        try {
            api.revokeDevice(device.profileId)
        } catch (e: ApiException) {
            failed++
        }
    }
    return if (failed == 0) LogoutAllResult.Done(viaFallback = true) else LogoutAllResult.Failed(partialLogoutText(failed, targets.size))
}
