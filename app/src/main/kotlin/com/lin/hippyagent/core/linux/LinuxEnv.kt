package com.lin.hippyagent.core.linux

import android.content.Context
import java.io.File

/** PRoot 二进制文件路径 */
val Context.prootBinary: File
    get() = File(applicationInfo.nativeLibraryDir, "libproot.so")

/** 构建 PRoot 进程所需的环境变量 */
val Context.linuxEnvironment: Map<String, String>
    get() {
        val env = mutableMapOf<String, String>()
        val nativeLibDir = File(applicationInfo.nativeLibraryDir)
        
        val loaderPath = File(nativeLibDir, "libproot_loader.so")
        if (loaderPath.exists()) {
            env["PROOT_LOADER"] = loaderPath.absolutePath
        }

        val loader32Path = File(nativeLibDir, "libproot_loader32.so")
        if (loader32Path.exists()) {
            env["PROOT_LOADER_32"] = loader32Path.absolutePath
        }

        val tmpDir = cacheDir
        tmpDir.mkdirs()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "C.UTF-8"
        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = "/data"
        return env
    }

