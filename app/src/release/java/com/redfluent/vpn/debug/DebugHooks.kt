package com.redfluent.vpn.debug

import android.content.Context
import android.content.Intent

/**
 * release 变体里的空实现。真正的测试钩子只在 src/debug 里，release 包里没有任何相关代码或字符串。
 */
object DebugHooks {
    fun onAppCreate(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun onActivityCreate(context: Context, intent: Intent?) = Unit

    /** 正式版永远用 5 分钟的固定间隔。 */
    @Suppress("UNUSED_PARAMETER")
    fun followIntervalMs(context: Context): Long? = null
}
