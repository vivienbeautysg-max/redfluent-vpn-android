package com.redfluent.vpn.debug

import android.content.Intent
import androidx.compose.runtime.Composable
import com.redfluent.vpn.MainActivity

/**
 * release 变体里的空实现：正式版没有演示模式，也没有任何演示数据（真正的实现只在 src/debug）。
 */
object DemoMode {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun content(activity: MainActivity, intent: Intent?): (@Composable () -> Unit)? = null
}
