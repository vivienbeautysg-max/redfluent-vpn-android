package com.redfluent.vpn

import android.app.Application
import android.os.Build
import com.redfluent.vpn.data.ProfileStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class RedFluentApp : Application() {

    companion object {
        lateinit var instance: RedFluentApp
            private set
    }

    val store: ProfileStore by lazy { ProfileStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupEngine()
    }

    /** libbox 需要先拿到可写目录才能启动引擎。全部放在应用私有目录下。 */
    private fun setupEngine() {
        val base = filesDir
        val working = File(getExternalFilesDir(null) ?: filesDir, "work").also { it.mkdirs() }
        val temp = cacheDir.also { it.mkdirs() }
        runCatching {
            Libbox.setup(
                SetupOptions().apply {
                    basePath = base.absolutePath
                    workingPath = working.absolutePath
                    tempPath = temp.absolutePath
                    // Android 上 Go 运行时需要修正线程栈，官方客户端同样开启
                    fixAndroidStack = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                    logMaxLines = 500L
                },
            )
        }
    }
}
