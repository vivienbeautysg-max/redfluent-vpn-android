package com.redfluent.vpn.data

/**
 * 1.3：每个「点了要等服务器」的操作（激活、保存个人页、删除、恢复、全部登出、登出一台、改名）共用一套反馈：
 *  - 开始 → 触发它的按钮进入忙碌状态，忙的时候同一时间不接第二个操作（不会连点两次发两次请求）；
 *  - 成功 → 收起键盘、CONFIRM 触感、页面顶部一条确认（不用滚动就看得到）；
 *  - 失败 → REJECT 触感、页面顶部一条原因（同样不用滚动），按钮恢复可点。
 * 纯状态机，便于单测；界面只读它。
 */
enum class OwnerAction { ACTIVATE, SAVE, DELETE, RESTORE, LOG_OUT_ALL, LOG_OUT_DEVICE, RENAME }

/** 顶部横幅：seq 每次结果 +1，界面靠它判断「这是一条新结果」（触感、收键盘只做一次）。 */
data class Banner(val seq: Long, val text: String, val success: Boolean)

data class ActionFeedback(
    val busy: OwnerAction? = null,
    val banner: Banner? = null,
    val seq: Long = 0,
) {
    fun isBusy(action: OwnerAction): Boolean = busy == action
}

object FeedbackMachine {
    /** 开始一个操作。已有操作在进行 → null（调用方什么也不做）。开始时清掉上一条横幅。 */
    fun start(s: ActionFeedback, action: OwnerAction): ActionFeedback? =
        if (s.busy != null) null else s.copy(busy = action, banner = null)

    fun succeed(s: ActionFeedback, text: String): ActionFeedback =
        (s.seq + 1).let { n -> s.copy(busy = null, seq = n, banner = Banner(n, text, success = true)) }

    fun fail(s: ActionFeedback, text: String): ActionFeedback =
        (s.seq + 1).let { n -> s.copy(busy = null, seq = n, banner = Banner(n, text.ifBlank { "Something went wrong." }, success = false)) }

    /** 收起某一条横幅（自动消失 / 点掉）；已经被更新的结果顶替时不动。 */
    fun dismiss(s: ActionFeedback, seq: Long): ActionFeedback =
        if (s.banner?.seq == seq) s.copy(banner = null) else s

    /** 成功横幅停多久；失败的停更久（要读原因）。 */
    fun showMillis(banner: Banner): Long = if (banner.success) 2_600L else 6_000L
}

/** 成功时顶部那一句。 */
object FeedbackText {
    const val ACTIVATED = "Activated"
    const val SAVED = "Saved"
    const val RENAMED = "Renamed"

    fun deleted(label: String): String = "$label deleted"

    fun restored(label: String): String = "$label restored · ${RecentlyDeleted.RESTORED_NOTE}"

    fun loggedOutAll(count: Int): String = if (count == 1) "1 device logged out" else "$count devices logged out"

    fun loggedOutDevice(name: String): String = "$name logged out"
}
