package com.redfluent.vpn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** GET /owner/devices 里每台设备的 usage（当前周期，东京 + 新加坡合计）。全部带默认值，旧后端没有该字段也能解码。 */
@Serializable
data class DeviceUsage(
    val usedGB: Double = 0.0,
    val quotaGB: Double? = null,
    val percent: Double? = null,
    val uploadGB: Double = 0.0,
    val downloadGB: Double = 0.0,
)

@Serializable
data class UsagePeriod(
    val start: String? = null,
    val end: String? = null,
)

/**
 * 只声明界面要用的字段，其余字段故意不声明——解码时被 ignoreUnknownKeys 丢掉。
 * inviteCode（明文邀请码）1.2.1 起要声明：把设备归到"用户"（邀请码）名下，并用于「Log out all devices」。
 * 它只留在内存里，toString 故意不含它——不显示、不落盘、不进日志。
 */
@Serializable
data class OwnerDevice(
    val profileId: String = "",
    val inviteCode: String? = null,
    val inviteLabel: String? = null,
    val ownerLabel: String? = null,
    val displayName: String? = null,
    val deviceName: String? = null,
    val appVersion: String? = null,
    val enabled: Boolean = true,
    val revokedAt: String? = null,
    val online: Boolean = false,
    val usage: DeviceUsage? = null,
    /** 1.2.1：这台设备最后一次真正产生流量的时间（ISO）；null = 从没用过 / 旧后端没有这个字段。 */
    val lastUsedAt: String? = null,
    /** 1.3：这台设备拨的东京 IPv6 入口；null = 只有 IPv4（新加坡的人没有）。 */
    val ipv6Entry: String? = null,
    /** 1.3：上面那个入口是它专属的（不是共用的）。 */
    val ipv6EntryDedicated: Boolean? = null,
) {
    override fun toString(): String = "OwnerDevice(${title()})"
}

/** 仍然可以使用的设备：没被停用、没被登出。 */
fun OwnerDevice.isActive(): Boolean = enabled && revokedAt == null

@Serializable
data class OwnerDevicesResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val devices: List<OwnerDevice> = emptyList(),
    val period: UsagePeriod? = null,
    /** 解码不了、被整台丢掉的设备数（0 = 全部读出来了）。 */
    val skipped: Int = 0,
)

// ---- 宽松解码 ---------------------------------------------------------------

private val lenientJson = Json { ignoreUnknownKeys = true }

/** 1.3 的 IPv6 入口字段：格式不对时最先丢掉（设备照样列出，只显示 "IPv4 only"）。 */
private val IPV6_FIELDS = setOf("ipv6Entry", "ipv6EntryDedicated")

/** 逐级放宽时依次丢掉的字段组合：先丢 IPv6 入口，再丢 lastUsedAt，再丢 usage，最后连 inviteCode 一起丢。 */
private val DEVICE_FALLBACKS: List<Set<String>> = listOf(
    IPV6_FIELDS,
    IPV6_FIELDS + "lastUsedAt",
    IPV6_FIELDS + "usage",
    IPV6_FIELDS + setOf("lastUsedAt", "usage"),
    IPV6_FIELDS + setOf("lastUsedAt", "usage", "inviteCode"),
)

/**
 * 逐台设备解码：一台设备的数据格式不对，只影响这一台，不能让整张用量表消失。
 *  1. 整台设备正常解码；
 *  2. 不行就逐级去掉 lastUsedAt / usage / inviteCode 再解码（这台设备仍然列出来，只是缺这些信息）；
 *  3. 还不行才丢掉这一台（计入 skipped）。
 */
fun parseOwnerDevices(root: JsonObject): OwnerDevicesResponse {
    val ok = (root["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
    val error = (root["error"] as? JsonPrimitive)?.contentOrNull
    val period = (root["period"] as? JsonObject)?.let {
        runCatching { lenientJson.decodeFromJsonElement(UsagePeriod.serializer(), it) }.getOrNull()
    }
    val rawDevices = (root["devices"] as? JsonArray).orEmpty()
    var skipped = 0
    val devices = ArrayList<OwnerDevice>(rawDevices.size)
    for (item in rawDevices) {
        val obj = item as? JsonObject
        val device = obj?.let { o ->
            runCatching { lenientJson.decodeFromJsonElement(OwnerDevice.serializer(), o) }.getOrNull()
                ?: DEVICE_FALLBACKS.firstNotNullOfOrNull { drop ->
                    runCatching {
                        lenientJson.decodeFromJsonElement(OwnerDevice.serializer(), JsonObject(o - drop))
                    }.getOrNull()
                }
        }
        if (device == null) skipped++ else devices += device
    }
    return OwnerDevicesResponse(ok = ok, error = error, devices = devices, period = period, skipped = skipped)
}

// ---- 用量条的颜色阈值 ------------------------------------------------------

enum class UsageLevel { NORMAL, WARNING, CRITICAL }

const val USAGE_WARN_PERCENT = 70.0
const val USAGE_CRITICAL_PERCENT = 90.0

/** <70 正常；70 ≤ p < 90 警告；≥90 严重（可以超过 100）。 */
fun usageLevel(percent: Double): UsageLevel = when {
    percent >= USAGE_CRITICAL_PERCENT -> UsageLevel.CRITICAL
    percent >= USAGE_WARN_PERCENT -> UsageLevel.WARNING
    else -> UsageLevel.NORMAL
}

// ---- 数值保护：非有限数 / 负数不能进入显示和合计 ------------------------------------

/** 已用流量：NaN、无穷、负数一律当 0（同时意味着不计入合计）。 */
fun DeviceUsage.safeUsedGB(): Double = if (usedGB.isFinite() && usedGB >= 0.0) usedGB else 0.0

/** 配额：必须是有限的正数，否则当「没有配额」。 */
fun DeviceUsage.safeQuotaGB(): Double? = quotaGB?.takeIf { it.isFinite() && it > 0.0 }

/**
 * 这台设备要画的百分比；null = 不画条。
 * 配额为空 / 非法 → null；服务器给了有限的 percent 就用服务器的（负数夹到 0），否则按 used/quota 自己算。
 */
fun DeviceUsage.barPercent(): Double? {
    val quota = safeQuotaGB() ?: return null
    val server = percent?.takeIf { it.isFinite() }?.let { if (it < 0.0) 0.0 else it }
    return server ?: (safeUsedGB() / quota * 100.0).takeIf { it.isFinite() }
}

// ---- 分组与排序 -----------------------------------------------------------

/**
 * 一个用户（= 一个邀请码）。devices 里包含已登出 / 停用的设备：它们的用量仍然计入 totalGB，
 * 但不计入 activeDevices，也不再有 Log out。
 * code 只留在内存里（toString 不含它）；后端没给 inviteCode（旧后端）时为 null，按标签分组。
 */
data class UsageGroup(
    val label: String,
    val totalGB: Double,
    val devices: List<OwnerDevice>,
    val code: String? = null,
    /** 这个用户所有设备里最近一次产生流量的时间（毫秒）；null = 从没用过。 */
    val lastUsedAtMs: Long? = null,
    /** 有任何一台仍可用的设备在线。 */
    val online: Boolean = false,
    /** 仍可用（没停用、没登出）的设备数。 */
    val activeDevices: Int = 0,
    /** 这是 owner 自己的邀请码：没有 Log out，服务器也会拒绝。 */
    val isOwnerInvite: Boolean = false,
) {
    /** 列表里的稳定标识（展开状态按它记）。 */
    val key: String get() = code?.let { "c:" + normalizeInviteCode(it) } ?: "l:$label"

    override fun toString(): String = "UsageGroup(label=$label, devices=${devices.size})"
}

private fun normalizeInviteCode(code: String): String =
    java.text.Normalizer.normalize(code.trim(), java.text.Normalizer.Form.NFKC).lowercase()

fun OwnerDevice.groupLabel(): String =
    inviteLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: ownerLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "Unassigned"

fun OwnerDevice.usedGB(): Double = usage?.safeUsedGB() ?: 0.0

private fun OwnerDevice.groupKey(): String =
    inviteCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "c:" + normalizeInviteCode(it) } ?: "l:${groupLabel()}"

/**
 * 按用户（邀请码；旧后端没有邀请码时按标签）分组。
 * 组按总用量降序；用量相同时最近用过的在前（从没用过的在后），再按名字，保证排序稳定。组内设备按用量降序。
 * ownerCode / ownerLabel 用来认出 owner 自己的邀请码（没有 Log out）。
 */
fun groupByUser(
    devices: List<OwnerDevice>,
    ownerCode: String? = null,
    ownerLabel: String? = null,
): List<UsageGroup> =
    devices
        .groupBy { it.groupKey() }
        .map { (_, list) ->
            val label = list.first().groupLabel()
            val code = list.firstNotNullOfOrNull { it.inviteCode?.trim()?.takeIf { c -> c.isNotEmpty() } }
            UsageGroup(
                label = label,
                totalGB = list.sumOf { it.usedGB() },
                devices = list.sortedWith(
                    compareByDescending<OwnerDevice> { it.usedGB() }.thenBy { it.title().lowercase() },
                ),
                code = code,
                lastUsedAtMs = list.mapNotNull { parseInstantMillis(it.lastUsedAt) }.maxOrNull(),
                online = list.any { it.online && it.isActive() },
                activeDevices = list.count { it.isActive() },
                isOwnerInvite = isOwnerInvite(code, label, ownerCode, ownerLabel),
            )
        }
        .sortedWith(
            compareByDescending<UsageGroup> { it.totalGB }
                .thenByDescending { it.lastUsedAtMs ?: Long.MIN_VALUE }
                .thenBy { it.label.lowercase() }
                .thenBy { it.key },
        )

/** owner 自己的邀请码：激活时用的码一致，或标签与 owner 自己的标签一致（与 Users 页的 protectedKind 同一规则）。 */
fun isOwnerInvite(code: String?, label: String, ownerCode: String?, ownerLabel: String?): Boolean =
    sameInviteCode(code, ownerCode) ||
        (ownerLabel?.trim()?.isNotEmpty() == true && label.trim().equals(ownerLabel.trim(), ignoreCase = true))

/** 设备行的标题：名字。 */
fun OwnerDevice.title(): String =
    displayName?.trim().takeUnless { it.isNullOrEmpty() }
        ?: deviceName?.trim().takeUnless { it.isNullOrEmpty() }
        ?: profileId.take(8).ifEmpty { "Device" }

/** 设备行的副标题：型号 · App 版本（型号与标题相同时不重复）。 */
fun OwnerDevice.subtitle(): String {
    val model = deviceName?.trim().takeUnless { it.isNullOrEmpty() || it == title() }
    val version = appVersion?.trim().takeUnless { it.isNullOrEmpty() }
    return listOfNotNull(model, version).joinToString(" · ")
}

// ---- 数字格式 --------------------------------------------------------------

fun formatGB(value: Double): String = String.format(Locale.US, "%.1f", value)

/** 配额通常是整数 GB：1000 显示 1000，而不是 1000.0。 */
fun formatQuotaGB(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else formatGB(value)

fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
