package com.lin.hippyagent.core.linux

import android.content.Context
import io.mockk.mockk
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PRootEngine.destroy() / killProcessTree() 回归测试（WS-31）。
 *
 * 旧实现 cleanup() 只把 engine 置 null，不销毁 PRoot 进程：
 * - 执行中的命令（如长耗时 apt install）的 PRoot 进程会继续运行；
 * - 容器内 `&`/nohup 启动的后台进程（如 sshd）会随 PRoot 一起变孤儿。
 * 修复后 destroy() 应强杀跟踪的进程；killProcessTree 应沿 /proc 递归
 * 收集全部后代并逐个 SIGKILL。
 */
class PRootEngineDestroyTest {

    private val osName: String = System.getProperty("os.name", "").lowercase()
    private val isWindows: Boolean = osName.contains("win")

    /** 静默挂起的命令（两个平台通用） */
    private fun hangCommand(): List<String> = if (isWindows) {
        listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 60")
    } else {
        listOf("sh", "-c", "sleep 60")
    }

    @Test(timeout = 30_000)
    fun destroy_killsTrackedProcesses() {
        val engine = PRootEngine(mockk<Context>(relaxed = true), ContainerConfig())
        val process = ProcessBuilder(hangCommand()).redirectErrorStream(true).start()

        engine.liveProcesses.add(process)
        engine.destroy()

        assertProcessTerminated(process)
        assertTrue("destroy() 后跟踪集合应清空", engine.liveProcesses.isEmpty())
    }

    @Test(timeout = 30_000)
    fun destroy_withNoTrackedProcesses_isNoOp() {
        val engine = PRootEngine(mockk<Context>(relaxed = true), ContainerConfig())

        engine.destroy()

        assertTrue(engine.liveProcesses.isEmpty())
    }

    /**
     * 仅在支持 /proc/<pid>/task/<tid>/children 的平台上验证进程树递归收集。
     * 注入记录型 signaler（单元测试中 android.os.Process.sendSignal 是
     * stub 且会误杀测试 JVM 的真实子进程），只验证收集结果不真正发信号。
     */
    @Test(timeout = 30_000)
    fun killProcessTree_collectsAllDescendants() {
        assumeFalse("需要 /proc 与 sh，Windows 跳过", isWindows)

        val engine = PRootEngine(mockk<Context>(relaxed = true), ContainerConfig())
        val tmpDir = Files.createTempDirectory("proot-destroy-test").toFile()
        val pidFile = File(tmpDir, "child.pid")

        // 父进程 sh 挂起等待子进程 sleep 60；子进程 PID 写入 pidFile
        val parent = ProcessBuilder(
            "sh", "-c", "sleep 60 & echo \$! > ${pidFile.absolutePath}; wait"
        ).redirectErrorStream(true).start()

        try {
            val childPid = awaitPidFile(pidFile)
            val signaled = mutableListOf<Int>()

            engine.killProcessTree(parent.pid()) { signaled += it }

            assertTrue(
                "killProcessTree 应收集到子进程 pid=$childPid，实际收集到 $signaled",
                childPid in signaled
            )
        } finally {
            parent.destroyForcibly()
            pidFile.parentFile?.deleteRecursively()
        }
    }

    private fun awaitPidFile(pidFile: File): Int {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!pidFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return pidFile.readText().trim().toInt()
    }

    private fun assertProcessTerminated(process: Process) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse("destroy() 后进程应已终止", process.isAlive)
        assertEquals(-1, process.exitValue())
    }
}
