package com.lin.hippyagent.core.linux.security

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 资源限制器：限制容器内的资源使用
 */
class ResourceLimiter {
    companion object {
        // 默认限制
        const val DEFAULT_MAX_CPU_TIME_MS = 30_000L      // 30 秒
        const val DEFAULT_MAX_MEMORY_MB = 512L           // 512 MB
        const val DEFAULT_MAX_DISK_MB = 1024L            // 1 GB
        const val DEFAULT_MAX_PROCESSES = 100            // 100 个进程
        const val DEFAULT_MAX_FILE_SIZE_MB = 100L        // 100 MB
        const val DEFAULT_MAX_OPEN_FILES = 1024          // 1024 个文件
    }

    // 资源使用统计
    private val cpuTimeUsage = AtomicLong(0)
    private val memoryUsage = AtomicLong(0)
    private val diskUsage = AtomicLong(0)
    private val processCount = AtomicLong(0)
    private val openFiles = AtomicLong(0)

    // 活跃进程
    private val activeProcesses = ConcurrentHashMap<String, ProcessInfo>()

    // 限制配置
    private var maxCpuTimeMs = DEFAULT_MAX_CPU_TIME_MS
    private var maxMemoryMb = DEFAULT_MAX_MEMORY_MB
    private var maxDiskMb = DEFAULT_MAX_DISK_MB
    private var maxProcesses = DEFAULT_MAX_PROCESSES
    private var maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB
    private var maxOpenFiles = DEFAULT_MAX_OPEN_FILES

    /**
     * 设置限制
     */
    fun setLimits(
        cpuTimeMs: Long? = null,
        memoryMb: Long? = null,
        diskMb: Long? = null,
        processes: Int? = null,
        fileSizeMb: Long? = null,
        openFiles: Int? = null
    ) {
        cpuTimeMs?.let { maxCpuTimeMs = it }
        memoryMb?.let { maxMemoryMb = it }
        diskMb?.let { maxDiskMb = it }
        processes?.let { maxProcesses = it }
        fileSizeMb?.let { maxFileSizeMb = it }
        openFiles?.let { maxOpenFiles = it }

        Timber.d("Resource limits updated: CPU=${maxCpuTimeMs}ms, Memory=${maxMemoryMb}MB, Disk=${maxDiskMb}MB")
    }

    /**
     * 检查是否可以启动新进程
     */
    fun canStartProcess(): ResourceCheckResult {
        val currentProcesses = processCount.get()
        if (currentProcesses >= maxProcesses) {
            return ResourceCheckResult(
                allowed = false,
                reason = "Process limit reached: $currentProcesses/$maxProcesses"
            )
        }

        val currentMemory = memoryUsage.get()
        if (currentMemory >= maxMemoryMb * 1024 * 1024) {
            return ResourceCheckResult(
                allowed = false,
                reason = "Memory limit reached: ${currentMemory / 1024 / 1024}MB/$maxMemoryMb MB"
            )
        }

        return ResourceCheckResult(allowed = true)
    }

    /**
     * 检查文件操作是否允许
     */
    fun canPerformFileOperation(fileSize: Long): ResourceCheckResult {
        if (fileSize > maxFileSizeMb * 1024 * 1024) {
            return ResourceCheckResult(
                allowed = false,
                reason = "File size exceeds limit: ${fileSize / 1024 / 1024}MB/$maxFileSizeMb MB"
            )
        }

        val currentDisk = diskUsage.get()
        if (currentDisk + fileSize > maxDiskMb * 1024 * 1024) {
            return ResourceCheckResult(
                allowed = false,
                reason = "Disk space limit reached: ${currentDisk / 1024 / 1024}MB/$maxDiskMb MB"
            )
        }

        return ResourceCheckResult(allowed = true)
    }

    /**
     * 记录进程启动
     */
    fun recordProcessStart(processId: String, command: String) {
        processCount.incrementAndGet()
        activeProcesses[processId] = ProcessInfo(
            id = processId,
            command = command,
            startTime = System.currentTimeMillis()
        )
        Timber.d("Process started: $processId (total: ${processCount.get()})")
    }

    /**
     * 记录进程结束
     */
    fun recordProcessEnd(processId: String) {
        processCount.decrementAndGet()
        activeProcesses.remove(processId)
        Timber.d("Process ended: $processId (total: ${processCount.get()})")
    }

    /**
     * 记录 CPU 时间使用
     */
    fun recordCpuTime(ms: Long) {
        cpuTimeUsage.addAndGet(ms)
        if (cpuTimeUsage.get() > maxCpuTimeMs) {
            Timber.w("CPU time limit exceeded: ${cpuTimeUsage.get()}ms/$maxCpuTimeMs ms")
        }
    }

    /**
     * 记录内存使用
     */
    fun recordMemoryUsage(bytes: Long) {
        memoryUsage.set(bytes)
    }

    /**
     * 记录磁盘使用
     */
    fun recordDiskUsage(bytes: Long) {
        diskUsage.addAndGet(bytes)
    }

    /**
     * 记录文件打开
     */
    fun recordFileOpen() {
        openFiles.incrementAndGet()
        if (openFiles.get() > maxOpenFiles) {
            Timber.w("Open files limit exceeded: ${openFiles.get()}/$maxOpenFiles")
        }
    }

    /**
     * 记录文件关闭
     */
    fun recordFileClose() {
        openFiles.decrementAndGet()
    }

    /**
     * 获取资源使用统计
     */
    fun getUsage(): ResourceUsage {
        return ResourceUsage(
            cpuTimeMs = cpuTimeUsage.get(),
            memoryBytes = memoryUsage.get(),
            diskBytes = diskUsage.get(),
            processCount = processCount.get().toInt(),
            openFiles = openFiles.get().toInt()
        )
    }

    /**
     * 获取限制配置
     */
    fun getLimits(): ResourceLimits {
        return ResourceLimits(
            maxCpuTimeMs = maxCpuTimeMs,
            maxMemoryMb = maxMemoryMb,
            maxDiskMb = maxDiskMb,
            maxProcesses = maxProcesses,
            maxFileSizeMb = maxFileSizeMb,
            maxOpenFiles = maxOpenFiles
        )
    }

    /**
     * 重置统计
     */
    fun reset() {
        cpuTimeUsage.set(0)
        memoryUsage.set(0)
        diskUsage.set(0)
        processCount.set(0)
        openFiles.set(0)
        activeProcesses.clear()
        Timber.d("Resource usage reset")
    }

    /**
     * 获取活跃进程列表
     */
    fun getActiveProcesses(): List<ProcessInfo> {
        return activeProcesses.values.toList()
    }
}

/**
 * 资源检查结果
 */
data class ResourceCheckResult(
    val allowed: Boolean,
    val reason: String? = null
)

/**
 * 资源使用统计
 */
data class ResourceUsage(
    val cpuTimeMs: Long,
    val memoryBytes: Long,
    val diskBytes: Long,
    val processCount: Int,
    val openFiles: Int
)

/**
 * 资源限制配置
 */
data class ResourceLimits(
    val maxCpuTimeMs: Long,
    val maxMemoryMb: Long,
    val maxDiskMb: Long,
    val maxProcesses: Int,
    val maxFileSizeMb: Long,
    val maxOpenFiles: Int
)

/**
 * 进程信息
 */
data class ProcessInfo(
    val id: String,
    val command: String,
    val startTime: Long
)

