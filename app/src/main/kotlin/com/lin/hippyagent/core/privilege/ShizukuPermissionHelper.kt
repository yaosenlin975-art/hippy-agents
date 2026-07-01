package com.lin.hippyagent.core.privilege

import android.content.pm.PackageManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Shizuku 运行时权限请求封装。
 */
object ShizukuPermissionHelper {

    fun isShizukuAvailable(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        var timeoutJob: Job? = null
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                timeoutJob?.cancel()
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        timeoutJob = MainScope().launch {
            delay(60_000)
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
        Shizuku.requestPermission(0)
    }
}
