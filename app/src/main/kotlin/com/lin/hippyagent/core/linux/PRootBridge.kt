package com.lin.hippyagent.core.linux

import timber.log.Timber

/**
 * PRoot JNI 桥接：加载原生 proot_ext 库并提供版本查询。
 *
 * 注意：libproot_ext.so 可能不包含 JNI getVersion 实现，
 * 因此所有 native 调用都做了安全包装，永不崩溃。
 */
object PRootBridge {

    private var isLoaded = false

    init {
        try {
            System.loadLibrary("proot_ext")
            isLoaded = true
            Timber.d("PRoot native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "Failed to load PRoot native library")
            isLoaded = false
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error loading PRoot native library")
            isLoaded = false
        }
    }

    /** 检查原生库是否已加载 */
    fun isLibraryLoaded(): Boolean = isLoaded

    /**
     * 获取 PRoot 版本号（安全包装，永不崩溃）
     * 如果 native 方法不存在，返回 "unknown"
     */
    @JvmStatic
    fun getVersion(): String {
        if (!isLoaded) return "unknown (library not loaded)"
        return try {
            nativeGetVersion()
        } catch (e: UnsatisfiedLinkError) {
            Timber.w("PRoot native getVersion not implemented in .so")
            "unknown (native method not found)"
        } catch (e: Exception) {
            Timber.w(e, "Failed to get PRoot version")
            "unknown (error)"
        }
    }

    @JvmStatic
    private external fun nativeGetVersion(): String
}

