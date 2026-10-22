package com.redfluent.vpn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 账号（邀请码）的到期状态，来自 /profile（1.2 起）。
 * expiresAt 是「生效的到期时间」（时长从激活起算，或旧的固定到期日）；null = 没有到期 / 还没激活。
 */
@Serializable
data class AccessInfo(
    val expiresAt: String? = null,
    val daysLeft: Int? = null,
    val expired: Boolean = false,
)

/**
 * 从 /profile 响应里取到期字段。
 * 一个都没带（旧后端）→ null，调用方保持本地原值；带了任何一个就以响应为准（缺的当 null / false）。
 * 字段格式不对（字符串当数字之类）按缺失处理，绝不抛异常。
 */
fun parseAccessInfo(root: JsonObject): AccessInfo? {
    if (!root.containsKey("expiresAt") && !root.containsKey("daysLeft") && !root.containsKey("expired")) return null
    val expiresAt = (root["expiresAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val daysLeft = (root["daysLeft"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull
        ?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
    val expired = (root["expired"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
    return AccessInfo(expiresAt = expiresAt, daysLeft = daysLeft, expired = expired)
}

// ---- 隧道被服务器叫停的原因（"无感"跟随发现账号到期 / 被吊销）-----------------------------------

enum class StopKind { EXPIRED, REVOKED }

data class StopReason(val kind: StopKind, val expiresAt: String? = null)

// ---- 日期与天数文案 ----------------------------------------------------------------------

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * "2026-11-03T00:00:00.000Z" → "3 Nov 2026"（按 ISO 里写的日期原样显示，不做时区换算，保证各端显示一致）。
 * 解析不了 → null。
 */
fun formatDay(iso: String?): String? {
    val s = iso?.trim().orEmpty()
    if (s.length < 10 || s[4] != '-' || s[7] != '-') return null
    val year = s.substring(0, 4).toIntOrNull() ?: return null
    val month = s.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = s.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    return "$day ${MONTHS[month - 1]} $year"
}

/** 状态行里的剩余天数："29 days left" / "1 day left" / "today"。 */
fun daysLeftText(days: Int): String = when {
    days <= 0 -> "today"
    days == 1 -> "1 day left"
    else -> "$days days left"
}

// ---- 主页上的到期提示 ---------------------------------------------------------------------

/** 剩余多少天及以内开始提醒。 */
const val DAYS_LEFT_WARN = 7

sealed interface AccessNotice {
    /** 已到期；date 已格式化好（"3 Nov 2026"），未知则为 null。 */
    data class Expired(val date: String?) : AccessNotice

    data object Revoked : AccessNotice

    data class DaysLeft(val days: Int) : AccessNotice
}

fun AccessNotice.text(): String = when (this) {
    is AccessNotice.Expired -> if (date != null) "Expired · $date" else "Expired"
    AccessNotice.Revoked -> "Revoked"
    is AccessNotice.DaysLeft -> when {
        days <= 0 -> "Expires today"
        days == 1 -> "1 day left"
        else -> "$days days left"
    }
}

/** 到期 / 被吊销期间不允许发起连接。 */
fun AccessNotice?.locksConnect(): Boolean = this is AccessNotice.Expired || this == AccessNotice.Revoked

/**
 * 主页要显示哪条提示：被吊销 > 已到期 > 快到期 > 无。
 * 「已到期」既可能来自 /profile（profile.expired），也可能来自隧道刚被服务器叫停（stop）——
 * 后者比刷新资料更早知道，先显示出来；资料刷新后以资料为准。
 */
fun accessNotice(
    expired: Boolean,
    expiresAt: String?,
    daysLeft: Int?,
    stop: StopReason?,
): AccessNotice? {
    if (stop?.kind == StopKind.REVOKED) return AccessNotice.Revoked
    if (expired || stop?.kind == StopKind.EXPIRED) {
        return AccessNotice.Expired(formatDay(expiresAt) ?: formatDay(stop?.expiresAt))
    }
    if (daysLeft != null && daysLeft in 0..DAYS_LEFT_WARN) return AccessNotice.DaysLeft(daysLeft)
    return null
}

fun accessNotice(profile: DeviceProfile?, stop: StopReason?): AccessNotice? =
    if (profile == null) null else accessNotice(profile.expired, profile.expiresAt, profile.daysLeft, stop)
