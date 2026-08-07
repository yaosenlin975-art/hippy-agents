package com.lin.hippyagent.core.linux

import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 进程构建工具函数：根据命令、工作目录和环境变量创建系统进程。
 */
fun createLinuxProcess(
    command: List<String>,
    workingDir: File? = null,
    environment: Map<String, String> = emptyMap(),
    redirectErrorStream: Boolean = true
): Process {
    val processBuilder = ProcessBuilder(command).apply {
        workingDir?.let { directory(it) }
        environment().putAll(environment)
        redirectErrorStream(redirectErrorStream)
    }
    return processBuilder.start()
}

/**
 * 进程执行结果。
 */
internal data class LinuxProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean
)

/**
 * 运行已启动的进程，并在独立线程中并发读取其输出，避免管道死锁（WS-29）。
 *
 * 经典错误写法是在 waitFor() 之后才读取 stdout：当进程输出量超过 OS
 * 管道缓冲区（通常 64KB）时，进程会阻塞在写管道上永不退出，waitFor 一直
 * 等到超时。这里启动一个后台读取线程与 waitFor 并行消费输出，保证进程
 * 始终能写管道。
 *
 * @param process 已启动（ProcessBuilder.start() 之后）的进程
 * @param timeoutMillis 等待进程完成的超时时间（毫秒）
 * @param joinTimeoutMillis 进程结束/被销毁后，等待读取线程收尾的超时时间（毫秒）
 */
internal fun runLinuxProcess(
    process: Process,
    timeoutMillis: Long,
    joinTimeoutMillis: Long = 5_000L
): LinuxProcessResult {
    val output = StringBuilder()

    val readerThread = Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) {
                    output.appendLine(line)
                }
            }
        } catch (e: Exception) {
            // 进程被销毁导致流关闭时抛出 IOException，属预期行为
            Timber.w(e, "Failed to read process output")
        }
    }.apply {
        name = "linux-process-output-reader"
        isDaemon = true
        start()
    }

    return try {
        val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (completed) {
            joinQuietly(readerThread, joinTimeoutMillis)
            LinuxProcessResult(process.exitValue(), snapshot(output), timedOut = false)
        } else {
            process.destroyForcibly()
            joinQuietly(readerThread, joinTimeoutMillis)
            // -2 为约定超时标记，与 PRootEngine.exec 的历史返回码一致
            LinuxProcessResult(-2, snapshot(output), timedOut = true)
        }
    } catch (e: Exception) {
        process.destroyForcibly()
        joinQuietly(readerThread, joinTimeoutMillis)
        throw e
    }
}

private fun joinQuietly(thread: Thread, timeoutMillis: Long) {
    try {
        thread.join(timeoutMillis)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun snapshot(buffer: StringBuilder): String = synchronized(buffer) { buffer.toString() }

