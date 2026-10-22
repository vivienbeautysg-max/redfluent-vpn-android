package com.redfluent.vpn.data

/** 把 /profile 的结果合并进本地 profile 之后的样子，以及此时应当生效的区域。 */
data class ProfileSyncResult(
    val profile: DeviceProfile,
    val region: String,
)

/**
 * 纯逻辑：/profile 的结果能不能写回本地、写成什么。
 *
 * 请求在途时，这台设备可能已经被换成别的账号（退出后用另一个邀请码重新激活）。
 * 只有同时满足下面三条才写回，否则丢弃（返回 null）：
 *  1. 本地现在还有 profile；
 *  2. 它的 profileId 仍是发请求时的那个；
 *  3. 服务器响应里的 profileId 也就是它（响应是给这个账号的）。
 * 区域的重置与 isOwner / regions 在同一次计算里完成，调用方把两者放进同一次 DataStore 写入。
 */
fun syncProfileFromServer(
    current: DeviceProfile?,
    storedRegion: String?,
    requestedProfileId: String,
    status: ProfileStatus,
): ProfileSyncResult? {
    if (current == null) return null
    if (current.profileId != requestedProfileId) return null
    if (status.profileId != current.profileId) return null
    val regions = normalizeRegions(status.regions)
    return ProfileSyncResult(
        profile = current.copy(isOwner = status.isOwner, regions = regions),
        region = resolveRegion(storedRegion, regions),
    )
}
