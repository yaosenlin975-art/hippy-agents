package com.lin.hippyagent.core.linux

import android.content.Context
import java.io.File

/** Linux 基础目录 */
val Context.linuxBaseDir: File
    get() = File(filesDir, "linux")

/** Rootfs 目录（存放解压后的 rootfs） */
val Context.rootfsDir: File
    get() = File(linuxBaseDir, "rootfs")

/** 共享目录（与 Android 文件系统共享） */
val Context.sharedDir: File
    get() = File(linuxBaseDir, "shared")

/** 配置目录 */
val Context.linuxConfigDir: File
    get() = File(linuxBaseDir, "config")

/** 日志目录 */
val Context.linuxLogDir: File
    get() = File(linuxBaseDir, "logs")

/** 临时目录 */
val Context.linuxTmpDir: File
    get() = File(linuxBaseDir, "tmp")

/** 用户主目录（在 rootfs 内） */
val Context.userHomeDir: File
    get() = File(rootfsDir, "root")

/** 项目目录（在共享目录内） */
val Context.projectDir: File
    get() = File(sharedDir, "project")

