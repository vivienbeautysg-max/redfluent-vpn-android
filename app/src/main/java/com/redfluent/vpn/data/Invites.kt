package com.redfluent.vpn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer

/**
 * GET /owner/invites 里的一条邀请码（= 一个用户）。1.2 新增的字段全部可选：旧后端没有也能解码。
 * code 只在内存里用来调用 update 接口，不显示、不落盘、不进日志（toString 里故意不含它）。
 */
@Serializable
data class OwnerInvite(
    val code: String = "",
    val label: String = "",
    val enabled: Boolean = true,
    val region: String? = null,
    val durationDays: Int? = null,
    val activatedAt: String? = null,
    val effectiveExpiresAt: String? = null,
    /** 旧的固定到期日（没有时长时仍然有效）。 */
    val expiresAt: String? = null,
    val daysLeft: Int? = null,
    val expired: Boolean = false,
) {
    /** 分配的区域；没有 / 空 = 东京。 */
    val regionId: String get() = normalizeRegionId(region).ifEmpty { REGION_TOKYO }

    override fun toString(): String = "OwnerInvite(label=$label, region=$regionId)"
}

@Serializable
data class OwnerInvitesResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val invites: List<OwnerInvite> = emptyList(),
    val skipped: Int = 0,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

private val NEW_INVITE_FIELDS = listOf("region", "durationDays", "activatedAt", "effectiveExpiresAt", "daysLeft", "expired")

/**
 * 逐条解码：某条的新字段格式不对，只丢掉新字段、这一条照样列出来；整条都解不了才丢掉这一条（计入 skipped）。
 */
fun parseOwnerInvites(root: JsonObject): OwnerInvitesResponse {
    val ok = (root["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
    val error = (root["error"] as? JsonPrimitive)?.contentOrNull
    val raw = (root["invites"] as? JsonArray).orEmpty()
    var skipped = 0
    val invites = ArrayList<OwnerInvite>(raw.size)
    for (item in raw) {
        val obj = item as? JsonObject
        val invite = obj?.let { o ->
            runCatching { lenientJson.decodeFromJsonElement(OwnerInvite.serializer(), o) }.getOrNull()
                ?: runCatching {
                    lenientJson.decodeFromJsonElement(OwnerInvite.serializer(), JsonObject(o - NEW_INVITE_FIELDS.toSet()))
                }.getOrNull()
        }
        if (invite == null) skipped++ else invites += invite
    }
    return OwnerInvitesResponse(ok = ok, error = error, invites = invites, skipped = skipped)
}

/** 列表按用户名排序（不区分大小写）；名字相同时保持原顺序。 */
fun sortInvites(invites: List<OwnerInvite>): List<OwnerInvite> =
    invites.sortedBy { it.label.trim().lowercase() }

// ---- 状态行 --------------------------------------------------------------------------------

/**
 * 每个用户一行的状态：
 *  "Starts on activation" | "Expires 21 Nov 2026 · 29 days left" | "Expired 3 Nov 2026" | "No limit"
 */
fun inviteStatusLine(invite: OwnerInvite): String {
    val day = formatDay(invite.effectiveExpiresAt ?: invite.expiresAt)
    return when {
        invite.expired -> if (day != null) "Expired $day" else "Expired"
        day != null -> "Expires $day" + (invite.daysLeft?.let { " · ${daysLeftText(it)}" } ?: "")
        invite.durationDays != null && invite.activatedAt.isNullOrBlank() -> "Starts on activation"
        else -> "No limit"
    }
}

// ---- 受保护的邀请码（服务器也会拒绝，这里提前把控件禁用）---------------------------------------------

enum class ProtectedKind { OWNER, REVIEW }

const val REVIEW_INVITE_LABEL = "Apple Review"

/** 邀请码比较：trim + NFKC + 不区分大小写（与后端 sameInviteCode 一致）。 */
fun sameInviteCode(a: String?, b: String?): Boolean {
    fun norm(s: String?) = Normalizer.normalize(s.orEmpty().trim(), Normalizer.Form.NFKC).lowercase()
    val x = norm(a)
    return x.isNotEmpty() && x == norm(b)
}

/**
 * 这条邀请码是不是 owner 自己的 / Apple 审核专用的：
 *  - owner：本机激活时用的邀请码一致，或标签与 owner 自己的标签一致；
 *  - Apple Review：标签是 "Apple Review"。
 * owner 的邀请码不能改区域和时长；审核码不能设时长。
 */
fun protectedKind(invite: OwnerInvite, ownerCode: String?, ownerLabel: String?): ProtectedKind? = when {
    sameInviteCode(invite.code, ownerCode) -> ProtectedKind.OWNER
    ownerLabel?.trim()?.isNotEmpty() == true && invite.label.trim().equals(ownerLabel.trim(), ignoreCase = true) ->
        ProtectedKind.OWNER
    invite.label.trim().equals(REVIEW_INVITE_LABEL, ignoreCase = true) -> ProtectedKind.REVIEW
    else -> null
}

// ---- 编辑（区域 + 使用时长）-------------------------------------------------------------------------

const val MIN_DURATION_DAYS = 1
const val MAX_DURATION_DAYS = 3650

/** 预设时长（天）。 */
val ACCESS_PRESETS = listOf(7, 30, 90, 180, 365)

sealed interface AccessChoice {
    data object NoLimit : AccessChoice
    data class Days(val days: Int) : AccessChoice
}

/** 打开编辑面板时 Access 的初始选择。 */
fun accessChoiceOf(invite: OwnerInvite): AccessChoice =
    invite.durationDays?.let { AccessChoice.Days(it) } ?: AccessChoice.NoLimit

/** 自定义天数：1..3650 的整数，否则 null。 */
fun parseCustomDays(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in MIN_DURATION_DAYS..MAX_DURATION_DAYS }

/** 可选的区域：东京在前，再加上 owner 自己的资料里有的区域（新加坡只有 owner 自己能用才出现）；已分配的区域一定在内。 */
fun regionChoices(ownerRegions: List<RegionOption>, current: String?): List<RegionOption> {
    val list = ArrayList<RegionOption>()
    list += RegionOption(REGION_TOKYO, "Tokyo", true)
    for (r in normalizeRegions(ownerRegions)) {
        if (list.none { it.id == r.id }) list += r
    }
    val cur = normalizeRegionId(current)
    if (cur.isNotEmpty() && list.none { it.id == cur }) list += RegionOption(cur)
    return list
}

sealed interface DurationChange {
    data object Unchanged : DurationChange
    data object Clear : DurationChange
    data class Set(val days: Int) : DurationChange
}

/** 要发给 /owner/invites/update 的内容：只含改了的字段。 */
data class InviteUpdate(
    val code: String,
    val region: String?,
    val duration: DurationChange,
) {
    override fun toString(): String = "InviteUpdate(region=$region, duration=$duration)"
}

/**
 * 比较「原来」和「现在选的」，只留下真正改了的字段；什么都没改 → null（保存按钮保持禁用）。
 * owner 的邀请码什么都不能改；审核码只能改区域。
 */
fun buildInviteUpdate(
    original: OwnerInvite,
    region: String,
    access: AccessChoice,
    kind: ProtectedKind? = null,
): InviteUpdate? {
    if (kind == ProtectedKind.OWNER) return null
    val newRegion = normalizeRegionId(region).takeIf { it.isNotEmpty() && it != original.regionId }
    val duration: DurationChange = if (kind == ProtectedKind.REVIEW) {
        DurationChange.Unchanged
    } else {
        when (access) {
            AccessChoice.NoLimit ->
                if (original.durationDays == null) DurationChange.Unchanged else DurationChange.Clear
            is AccessChoice.Days -> when {
                access.days !in MIN_DURATION_DAYS..MAX_DURATION_DAYS -> DurationChange.Unchanged
                access.days == original.durationDays -> DurationChange.Unchanged
                else -> DurationChange.Set(access.days)
            }
        }
    }
    if (newRegion == null && duration == DurationChange.Unchanged) return null
    return InviteUpdate(code = original.code, region = newRegion, duration = duration)
}

fun InviteUpdate.toJson(): JsonObject = buildJsonObject {
    put("code", code)
    region?.let { put("region", it) }
    when (val d = duration) {
        DurationChange.Unchanged -> Unit
        DurationChange.Clear -> put("durationDays", JsonNull)
        is DurationChange.Set -> put("durationDays", d.days)
    }
}

/** 保存失败时面板里显示的一句话：已知原因用固定文案，其余用服务器给的短原因（首字母大写）。 */
fun inviteErrorText(code: Int, reason: String): String {
    if (code < 0) return reason
    return when (reason.trim().lowercase()) {
        "region unavailable" -> "This region is unavailable right now."
        "region not allowed", "forbidden" -> "Not allowed."
        "invite not found" -> "User not found."
        else -> {
            val r = cleanErrorReason(code, reason, "")
            r.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
        }
    }
}
