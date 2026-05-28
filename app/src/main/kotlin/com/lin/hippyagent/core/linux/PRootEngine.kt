package com.lin.hippyagent.core.linux

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import com.lin.hippyagent.core.security.PermissionManager
import com.lin.hippyagent.core.security.ShellPermissionResult
import kotlinx.coroutines.runBlocking

/**
 * PRoot 引擎：负责构建命令行、环境变量，并在容器中启动进程。
 * 
 * 集成 PermissionManager 进行 Shell 命令权限检查
 */
class PRootEngine(
    private val context: Context,
    private val config: ContainerConfig = ContainerConfig(),
    private val permissionManager: PermissionManager? = null
) {
    val version: String by lazy {
        try {
            PRootBridge.getVersion()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to get PRoot version")
            "unknown"
        }
    }

    /**
     * 执行命令（带权限检查）
     * @param rootfsDir rootfs 目录
     * @param command 要执行的命令
     * @param timeout 超时时间（毫秒）
     * @return Pair<exitCode, output>
     */
    fun exec(
        rootfsDir: File,
        command: String,
        timeout: Long = 30_000L
    ): Pair<Int, String> {
        // ======= 权限检查（如果 PermissionManager 已配置）=======
        val permissionManager = this.permissionManager
        if (permissionManager != null) {
            val result = runBlocking { permissionManager.checkShellCommand(command) }
            when (result) {
                ShellPermissionResult.BLOCKED -> {
                    Timber.w("Command blocked: $command")
                    return Pair(-1, "Command blocked: $command")
                }
                ShellPermissionResult.NEEDS_APPROVAL -> {
                    // 返回特殊标记，由调用层处理用户确认
                    Timber.d("Command needs approval: $command")
                    return Pair(-1, "PERMISSION_NEEDED: $command")
                }
                ShellPermissionResult.DENIED -> {
                    Timber.w("Command denied: $command")
                    return Pair(-1, "Command denied: $command")
                }
                ShellPermissionResult.AUTO_APPROVED -> {
                    // 继续执行
                }
            }
        }
        
        val cmd = buildCommand(rootfsDir, command)

        Timber.d("Executing: ${cmd.joinToString(" ")}")

        // PRoot 二进制本身需要在外层（Android 进程）看到这些环境变量
        // PROOT_TMP_DIR: PRoot 启动时创建 glue rootfs 的临时目录
        // PROOT_LOADER: PRoot 的 ARM 转译器
        val prootEnv = mutableMapOf<String, String>()
        context.linuxEnvironment.forEach { (key, value) ->
            if (key.startsWith("PROOT_")) {
                prootEnv[key] = value
            }
        }

        val process = createLinuxProcess(
            command = cmd,
            workingDir = rootfsDir,
            environment = prootEnv
        )

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()

        return try {
            val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (completed) {
                reader.forEachLine { line ->
                    output.appendLine(line)
                }
                val exitCode = process.exitValue()
                Pair(exitCode, output.toString())
            } else {
                process.destroyForcibly()
                Pair(-2, "Command timed out after ${timeout}ms")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute command")
            process.destroyForcibly()
            Pair(-3, "Execution failed: ${e.message}")
        }
    }

    /**
     * 构建完整的 PRoot 命令行参数
     */
    private fun buildCommand(rootfsDir: File, command: String): List<String> {
        val cmd = mutableListOf<String>()

        // PRoot 二进制
        cmd.add(context.prootBinary.absolutePath)

        // 基本参数
        cmd.add("-0")  // root mode
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsDir.absolutePath)

        // 工作目录
        cmd.add("-w")
        cmd.add(config.workingDir)

        // 注意：--kernel-release 不是 PRoot 运行所必需的，且会导致
        // "proot error: --kernel-release" 错误，已移除。
        // 如果需要设置 hostname，PRoot 会自动使用默认值。

        // 添加必要的绑定
        addEssentialBinds(cmd, rootfsDir)

        // 添加自定义绑定
        config.binds.forEach { binding ->
            cmd.add("-b")
            cmd.add(binding.toString())
        }

        // 执行命令
        cmd.addAll(listOf("/usr/bin/env", "-i"))

        // 环境变量（修复：只使用一个 -i 参数）
        val env = buildEnvironment()
        env.forEach { (key, value) ->
            cmd.add("$key=$value")
        }

        // 执行 bash 命令
        cmd.addAll(listOf("/bin/bash", "-c", command))

        return cmd
    }

    /**
     * 构建容器进程的环境变量
     */
    private fun buildEnvironment(): Map<String, String> {
        val baseEnv = context.linuxEnvironment.toMutableMap()

        // 合并配置中的环境变量
        config.env.forEach { (key, value) ->
            baseEnv[key] = value
        }

        // 设置用户
        if (config.user.isNotEmpty()) {
            baseEnv["USER"] = config.user
            baseEnv["LOGNAME"] = config.user
        }

        return baseEnv
    }

    /**
     * 绑定容器运行所需的设备节点、文件系统和网络配置
     */
    private fun addEssentialBinds(cmd: MutableList<String>, rootfsDir: File) {
        // 绑定基础设备节点
        listOf("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ptmx", "/dev/tty").forEach { dev ->
            if (File(dev).exists()) {
                cmd.add("-b")
                cmd.add(dev)
            }
        }

        // 绑定 PTTY 伪终端
        if (File("/dev/pts").exists()) {
            cmd.add("-b")
            cmd.add("/dev/pts")
        }

        // 绑定 /proc（整个目录，已包含所有子文件）
        // 注意：不要额外绑定 /proc/stat 等子文件，PRoot sanitize 时会权限冲突
        if (File("/proc").exists()) {
            cmd.add("-b")
            cmd.add("/proc:/proc")
        }

        if (File("/sys").exists()) {
            cmd.add("-b")
            cmd.add("/sys:/sys")
        }

        // 绑定 Android 系统分区
        listOf("/system", "/vendor").forEach { path ->
            if (File(path).exists()) {
                cmd.add("-b")
                cmd.add("$path:$path")
            }
        }

        // 绑定共享目录
        val sharedDir = context.sharedDir
        if (sharedDir.exists()) {
            cmd.add("-b")
            cmd.add("${sharedDir.absolutePath}:/mnt/shared")
        }

        // 确保 DNS 和 hosts 文件存在
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        }

        val hostsFile = File(rootfsDir, "etc/hosts")
        if (!hostsFile.exists()) {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
    }

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: ContainerConfig) {
        // 注意：由于 config 是 val，需要创建新实例
        // 这里通过 LinuxManager 来管理配置更新
        Timber.d("Config update requested")
    }
}

