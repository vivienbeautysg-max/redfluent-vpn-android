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
 * 只声明界面要用的字段，其余（包括 inviteCode 明文）故意不声明——
 * 解码时被 ignoreUnknownKeys 丢掉，永远不会进内存里的模型，更不会被显示。
 */
@Serializable
data class OwnerDevice(
    val profileId: String = "",
    val inviteLabel: String? = null,
    val ownerLabel: String? = null,
    val displayName: String? = null,
    val deviceName: String? = null,
    val appVersion: String? = null,
    val enabled: Boolean = true,
    val revokedAt: String? = null,
    val online: Boolean = false,
    val usage: DeviceUsage? = null,
)

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

/**
 * 逐台设备解码：一台设备的数据格式不对，只影响这一台，不能让整张用量表消失。
 *  1. 整台设备正常解码；
 *  2. 不行就去掉 usage 再解码（这台设备仍然列出来，只是没有进度条）；
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
                ?: runCatching {
                    lenientJson.decodeFromJsonElement(OwnerDevice.serializer(), JsonObject(o - "usage"))
                }.getOrNull()
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

data class UsageGroup(
    val label: String,
    val totalGB: Double,
    val devices: List<OwnerDevice>,
)

fun OwnerDevice.groupLabel(): String =
    inviteLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: ownerLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "Unassigned"

fun OwnerDevice.usedGB(): Double = usage?.safeUsedGB() ?: 0.0

/** 按邀请标签（= 用户）分组；组按总用量降序，组内设备按用量降序。用量相同时按名字，保证排序稳定。 */
fun groupByUser(devices: List<OwnerDevice>): List<UsageGroup> =
    devices
        .groupBy { it.groupLabel() }
        .map { (label, list) ->
            UsageGroup(
                label = label,
                totalGB = list.sumOf { it.usedGB() },
                devices = list.sortedWith(
                    compareByDescending<OwnerDevice> { it.usedGB() }.thenBy { it.title().lowercase() },
                ),
            )
        }
        .sortedWith(compareByDescending<UsageGroup> { it.totalGB }.thenBy { it.label.lowercase() })

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
