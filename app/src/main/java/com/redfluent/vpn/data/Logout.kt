package com.redfluent.vpn.data

/**
 * 1.2.1：owner 把某台设备 / 某个用户的所有设备登出（设备回到激活页；邀请码仍然有效，再输入一次就能重新激活）。
 * owner 自己的邀请码永远不能登出（服务器也会拒绝）。
 */

/** 这台设备能不能显示 Log out：所在用户不是 owner 自己的，并且设备仍然可用（已登出 / 停用的不再有操作）。 */
fun canLogOutDevice(group: UsageGroup, device: OwnerDevice): Boolean = !group.isOwnerInvite && device.isActive()

/** 这个用户能不能显示 "Log out all devices"：不是 owner 自己的，并且至少还有一台可用的设备。 */
fun canLogOutAll(group: UsageGroup): Boolean = !group.isOwnerInvite && group.activeDevices > 0

/** 登出整个用户时实际要处理的设备（只含仍可用的；owner 的邀请码返回空）。 */
fun logoutTargets(group: UsageGroup): List<OwnerDevice> =
    if (group.isOwnerInvite) emptyList() else group.devices.filter { it.isActive() }

/**
 * POST /owner/users/logout 不存在（后端还没上线这个接口）时，改成逐台设备 POST /owner/devices/revoke。
 * 判据：404，并且原因不是"邀请码不存在"这类带 invite 字样的 JSON 原因（接口不存在时是整页 HTML → 短说明 "not found"）。
 * 这个回退只会碰这个用户当前仍可用的设备，所以即使判断偏了（邀请码真的不存在），也不会误伤别人。
 */
fun logoutAllUnsupported(code: Int, reason: String): Boolean =
    code == 404 && !reason.lowercase().contains("invite")

fun confirmDeviceTitle(deviceTitle: String): String = "Log out $deviceTitle?"

const val CONFIRM_DEVICE_MESSAGE = "It will need the invite code to reconnect."

fun confirmAllTitle(count: Int, label: String): String =
    if (count == 1) "Log out 1 device of $label?" else "Log out all $count devices of $label?"

/** 在当前的用量数据里找这台设备；只有"现在真的可以登出"的设备才返回（owner 自己的、已登出的一律找不到）。 */
fun findLoggableDevice(groups: List<UsageGroup>, profileId: String): OwnerDevice? =
    groups.firstNotNullOfOrNull { g -> g.devices.firstOrNull { it.profileId == profileId && canLogOutDevice(g, it) } }

/** 在当前的用量数据里找这个用户；只有"现在真的可以整个登出"的才返回。 */
fun findLoggableGroup(groups: List<UsageGroup>, key: String): UsageGroup? =
    groups.firstOrNull { it.key == key && canLogOutAll(it) }

/** 登出失败时留在界面上的一句话。 */
fun logoutErrorText(code: Int, reason: String): String = when {
    code == 403 -> "Not allowed."
    code == 404 -> if (reason.lowercase().contains("invite")) "User not found." else "Device not found."
    code < 0 -> reason
    else -> cleanErrorReason(code, reason, "").replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
}

/** 逐台登出里有失败时的汇总。 */
fun partialLogoutText(failed: Int, total: Int): String =
    if (failed >= total) "Could not log out." else "Could not log out $failed of $total devices."

// ---- 设备这一侧：被登出后回到激活页 ------------------------------------------------------------

/** 设备被登出后激活页上显示的一句话（不是吓人的错误；1.3 起与 iOS 一样只写 "Signed out"，下面就是输入框）。 */
const val SIGNED_OUT_NOTICE = "Signed out"

/**
 * /profile 的结果是否说明"这台设备已被登出"：
 * 响应是给本地这个账号的（profileId 对得上），并且服务器明确说 enabled = false。
 * 请求在途时设备可能已换成别的账号——对不上就不处理。
 */
fun isSignedOutByProfile(current: DeviceProfile?, requestedProfileId: String, status: ProfileStatus): Boolean =
    current != null &&
        current.profileId == requestedProfileId &&
        status.profileId == current.profileId &&
        !status.enabled
