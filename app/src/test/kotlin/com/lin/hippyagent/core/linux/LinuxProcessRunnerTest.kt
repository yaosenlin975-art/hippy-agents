package com.lin.hippyagent.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runLinuxProcess 管道死锁回归测试（WS-29）。
 *
 * 旧实现先 waitFor 再读输出：进程输出量超过 OS 管道缓冲区（通常 64KB）时
 * 会阻塞在写管道上永不退出，waitFor 一直等到超时。以下用例均产生超过
 * 64KB 的输出，旧实现下会超时或输出不完整，新实现下应正常完成。
 */
class LinuxProcessRunnerTest {

    private val osName: String = System.getProperty("os.name", "").lowercase()
    private val isWindows: Boolean = osName.contains("win")

    /** 产生约 250KB 输出后正常退出的命令 */
    private fun largeOutputCommand(): List<String> = if (isWindows) {
        listOf("powershell", "-NoProfile", "-Command", "1..2500 | ForEach-Object { 'x' * 100 }")
    } else {
        listOf("sh", "-c", "yes x | head -c 250000")
    }

    /** 持续输出、永不自行退出的命令 */
    private fun endlessOutputCommand(): List<String> = if (isWindows) {
        listOf("powershell", "-NoProfile", "-Command", "while (`$true) { 'y' * 100 }")
    } else {
        listOf("sh", "-c", "while true; do echo yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy; done")
    }

    /** 静默挂起的命令 */
    private fun silentHangCommand(): List<String> = if (isWindows) {
        listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 60")
    } else {
        listOf("sh", "-c", "sleep 60")
    }

    @Test(timeout = 60_000)
    fun largeOutput_completesWithFullOutput() {
        val process = ProcessBuilder(largeOutputCommand()).redirectErrorStream(true).start()

        val result = runLinuxProcess(process, timeoutMillis = 60_000L)

        assertFalse("大输出命令不应超时（管道死锁回归）", result.timedOut)
        assertEquals(0, result.exitCode)
        assertTrue(
            "输出应超过 64KB 管道缓冲区，实际 ${result.output.length} 字节",
            result.output.length > 64 * 1024
        )
    }

    @Test(timeout = 30_000)
    fun silentHang_timesOutAndKillsProcess() {
        val process = ProcessBuilder(silentHangCommand()).redirectErrorStream(true).start()

        val result = runLinuxProcess(process, timeoutMillis = 2_000L)

        assertTrue("挂起命令应超时", result.timedOut)
        assertEquals(-2, result.exitCode)
        assertProcessTerminated(process)
    }

    @Test(timeout = 30_000)
    fun endlessOutput_timesOutAndKillsProcess() {
        val process = ProcessBuilder(endlessOutputCommand()).redirectErrorStream(true).start()

        val startMs = System.currentTimeMillis()
        val result = runLinuxProcess(process, timeoutMillis = 2_000L)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("持续输出命令应超时", result.timedOut)
        assertEquals(-2, result.exitCode)
        assertTrue(
            "超时应由 waitFor 触发而非被输出阻塞（预期约 2 秒，实际 ${elapsedMs}ms）",
            elapsedMs < 15_000L
        )
        assertTrue("被杀前应已读取到部分输出", result.output.isNotEmpty())
        assertProcessTerminated(process)
    }

    private fun assertProcessTerminated(process: Process) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse("destroyForcibly 后进程应已终止", process.isAlive)
    }
}
