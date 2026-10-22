package com.redfluent.vpn.data

import kotlinx.serialization.Serializable
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
)

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

/**
 * 这台设备要画的百分比；null = 不画条。
 * 配额为空（或 0）→ null；服务器给了 percent 就用服务器的，否则按 used/quota 自己算。
 */
fun DeviceUsage.barPercent(): Double? {
    val quota = quotaGB ?: return null
    if (!(quota > 0.0)) return null
    val p = percent ?: (usedGB / quota * 100.0)
    return if (p.isNaN() || p < 0.0) 0.0 else p
}

// ---- 分组与排序 ------------------------------------------------------------

data class UsageGroup(
    val label: String,
    val totalGB: Double,
    val devices: List<OwnerDevice>,
)

fun OwnerDevice.groupLabel(): String =
    inviteLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: ownerLabel?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "Unassigned"

fun OwnerDevice.usedGB(): Double = usage?.usedGB ?: 0.0

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
