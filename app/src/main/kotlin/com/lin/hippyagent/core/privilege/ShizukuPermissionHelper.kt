package com.lin.hippyagent.core.privilege

import android.content.pm.PackageManager
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
        Shizuku.requestPermission(0)
    }
}
