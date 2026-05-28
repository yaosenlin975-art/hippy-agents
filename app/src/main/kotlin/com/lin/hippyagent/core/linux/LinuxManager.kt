package com.lin.hippyagent.core.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Linux 环境管理器：统一管理 PRoot 容器的生命周期。
 */
class LinuxManager(
    private val context: Context
) {
    private var engine: PRootEngine? = null
    private var config: LinuxConfig? = null
    private val migrationManager = LinuxMigrationManager(context)
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _status = MutableStateFlow(LinuxStatus())
    val status: StateFlow<LinuxStatus> = _status.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        // Rootfs 下载地址 - Ubuntu 24.04 minimal for ARM64
        private const val ROOTFS_URL = "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        // 备用地址
        private const val ROOTFS_URL_BACKUP = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"
    }

    /**
     * 初始化 Linux 环境 — 完整 5 步流程（与参考 ALinux KeepAliveService 一致）
     *
     * Step 1: 检查迁移 + 初始化引擎
     * Step 2: 下载并解压 rootfs（必要时）
     * Step 3: 同步脚本到容器（CRLF 规范化）
     * Step 4: 配置 DNS
     * Step 5: 验证关键二进制存在
     */
    suspend fun initialize(forceReinstall: Boolean = false) {
        // 顶部快检：如果已经就绪且不是强制重装，直接跳过
        if (_isReady.value && !forceReinstall) {
            Timber.d("LinuxManager: initialize skipped, already ready")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                Timber.d("Linux: Step 1 — 初始化引擎...")

                // 检查是否需要迁移
                val migrationAction = migrationManager.checkMigration()
                if (migrationAction != MigrationAction.NONE) {
                    Timber.d("Linux: Migration needed: $migrationAction")
                    migrationManager.migrate(migrationAction)
                }

                // 创建必要的目录
                ensureDirectories()

                // 加载配置
                config = LinuxConfig(context)
                config?.load()

                // 初始化 PRoot 引擎
                val containerConfig = config?.toContainerConfig() ?: ContainerConfig()
                engine = PRootEngine(context, containerConfig)
                try {
                    Timber.d("Linux: PRoot version: ${engine?.version}")
                } catch (e: Throwable) {
                    Timber.w(e, "Failed to get PRoot version, continuing anyway")
                }

                // Step 2: 下载并解压 rootfs（必要时）
                if (forceReinstall || !isRootfsInstalled()) {
                    Timber.d("Linux: Step 2 — 下载并解压 rootfs...")
                    downloadRootfs()
                    extractRootfs()
                }

                // Step 3: 同步脚本到容器（带 CRLF 规范化）
                Timber.d("Linux: Step 3 — 同步脚本到容器...")
                syncScriptsToContainer()

                // Step 4: 配置 DNS
                Timber.d("Linux: Step 4 — 配置 DNS...")
                setupDns()

                // Step 5: 验证关键二进制存在
                Timber.d("Linux: Step 5 — 验证关键二进制...")
                if (!ensureBinaries(listOf("/bin/bash", "/bin/sh"))) {
                    Timber.w("Linux: bash/sh not found, retrying rootfs extraction...")
                    // 重新下载解压 rootfs 再试一次
                    downloadRootfs()
                    extractRootfs()
                    if (!ensureBinaries(listOf("/bin/bash", "/bin/sh"))) {
                        throw RuntimeException("Critical binaries (bash/sh) not found in rootfs after re-extraction.")
                    }
                }

                // Step 6: 自动检测并安装常用工具（python3、curl）
                Timber.d("Linux: Step 6 — 自动安装常用工具...")
                ensureCommonPackages()

                Timber.d("LinuxManager: _isReady = true")
                _isReady.value = true
                _status.value = LinuxStatus(
                    isInstalled = true,
                    isRunning = true,
                    rootfsPath = getRootfsPath(),
                    diskUsage = calculateDiskUsage()
                )

                Timber.i("Linux: 环境就绪 — 5 步初始化完成")
            } catch (e: Exception) {
                Timber.e(e, "Linux: 初始化失败")
                migrationManager.markMigrationFailed()
            Timber.d("LinuxManager: _isReady = false (exception)")
                _isReady.value = false
                _status.value = LinuxStatus(
                    isInstalled = false,
                    isRunning = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * 执行命令
     * @param command 要执行的命令
     * @param timeout 超时时间（毫秒）
     * @return Pair<exitCode, output>
     */
    suspend fun exec(
        command: String,
        timeout: Long = 30_000
    ): Pair<Int, String> {
        if (!_isReady.value) {
            return Pair(-1, "Linux environment not ready")
        }

        return withContext(Dispatchers.IO) {
            try {
                val rootfsDir = File(getRootfsPath())
                engine?.exec(rootfsDir, command, timeout)
                    ?: Pair(-1, "Engine not initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to execute command: $command")
                Pair(-3, "Execution failed: ${e.message}")
            }
        }
    }

    /**
     * 检查是否已安装 rootfs
     */
    fun isRootfsInstalled(): Boolean {
        val rootfsDir = File(getRootfsPath())
        return rootfsDir.exists() &&
               rootfsDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * 获取 Linux 环境状态
     */
    fun getStatus(): LinuxStatus {
        return _status.value.copy(
            diskUsage = calculateDiskUsage()
        )
    }

    /**
     * 清理 Linux 环境
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            engine = null
            _isReady.value = false
            _status.value = LinuxStatus()
            Timber.i("Linux environment cleaned up")
        }
    }

    /**
     * 获取 rootfs 路径
     */
    fun getRootfsPath(): String {
        return context.rootfsDir.absolutePath
    }

    /**
     * 获取共享目录路径
     */
    fun getSharedPath(): String {
        return context.sharedDir.absolutePath
    }

    /**
     * 创建必要的目录
     */
    private fun ensureDirectories() {
        val dirs = listOf(
            context.linuxBaseDir,
            context.rootfsDir,
            context.sharedDir,
            context.linuxConfigDir,
            context.linuxLogDir,
            context.linuxTmpDir,
            context.projectDir
        )
        dirs.forEach { it.mkdirs() }
    }

    /**
     * 下载 rootfs
     */
    private suspend fun downloadRootfs() {
        val rootfsDir = File(getRootfsPath())
        val tarFile = File(rootfsDir.parentFile, "rootfs.tar.gz")

        if (tarFile.exists()) {
            Timber.d("Rootfs tar file already exists, skipping download")
            return
        }

        Timber.d("Downloading rootfs from $ROOTFS_URL")

        try {
            val request = Request.Builder()
                .url(ROOTFS_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                // 尝试备用地址
                Timber.w("Primary download failed, trying backup URL")
                val backupRequest = Request.Builder()
                    .url(ROOTFS_URL_BACKUP)
                    .build()
                val backupResponse = client.newCall(backupRequest).execute()
                if (!backupResponse.isSuccessful) {
                    throw RuntimeException("Failed to download rootfs: ${backupResponse.code}")
                }
                downloadFile(backupResponse, tarFile)
            } else {
                downloadFile(response, tarFile)
            }

            Timber.d("Rootfs downloaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to download rootfs")
            throw e
        }
    }

    /**
     * 下载文件到本地
     */
    private fun downloadFile(response: okhttp3.Response, targetFile: File) {
        val body = response.body ?: throw RuntimeException("Empty response body")
        val contentLength = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (contentLength > 0) {
                        _downloadProgress.value = downloaded.toFloat() / contentLength
                    }
                }
            }
        }
    }

    /**
     * 解压 rootfs（路径安全 + 符号链接 + Unix 权限）
     * 参考 ALinux/LinuxManager.extractTar()
     */
    private suspend fun extractRootfs() {
        val rootfsDir = File(getRootfsPath())
        val tarFile = File(rootfsDir.parentFile, "rootfs.tar.gz")

        if (!tarFile.exists()) {
            throw RuntimeException("Rootfs tar file not found")
        }

        Timber.d("Extracting rootfs to ${rootfsDir.absolutePath}")

        // 预先分配文件大小（避免解压时磁盘空间不足）
        val freeSpace = rootfsDir.freeSpace
        val estimatedSize = tarFile.length() * 3  // tar.gz 解压比约 3x
        if (freeSpace < estimatedSize) {
            Timber.w("Low disk space: ${freeSpace / 1024 / 1024}MB free, estimated need ${estimatedSize / 1024 / 1024}MB")
        }

        try {
            // 清理旧 rootfs（如果存在且需要重新安装）
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val rootCanonical = rootfsDir.canonicalFile

            BufferedInputStream(tarFile.inputStream()).use { fileInput ->
                GzipCompressorInputStream(fileInput).use { gzipInput ->
                    TarArchiveInputStream(gzipInput).use { tarInput ->
                        var entry: TarArchiveEntry? = tarInput.nextTarEntry
                        while (entry != null) {
                            val targetPath = File(rootfsDir, entry.name)

                            // 路径穿越保护：resolve 后取 canonical，确保在 rootfs 内部
                            val targetCanonical = try {
                                targetPath.canonicalFile
                            } catch (e: Exception) {
                                Timber.w("Skipping unresolvable path: ${entry.name}")
                                entry = tarInput.nextTarEntry
                                continue
                            }
                            if (!targetCanonical.toPath().startsWith(rootCanonical.toPath())) {
                                Timber.w("Skipping path traversal entry: ${entry.name}")
                                entry = tarInput.nextTarEntry
                                continue
                            }

                            when {
                                entry.isDirectory -> {
                                    targetCanonical.mkdirs()
                                    applyMode(targetCanonical, entry.mode.toInt())
                                }
                                entry.isSymbolicLink -> {
                                    // 符号链接：读取链接目标并创建
                                    val linkTarget = entry.linkName
                                    if (linkTarget.isNotEmpty()) {
                                        targetCanonical.parentFile?.mkdirs()
                                        try {
                                            Files.createSymbolicLink(targetCanonical.toPath(), java.nio.file.Path.of(linkTarget))
                                        } catch (e: Exception) {
                                            Timber.w("Failed to create symlink ${entry.name} -> $linkTarget: ${e.message}")
                                        }
                                    }
                                }
                                else -> {
                                    targetCanonical.parentFile?.mkdirs()
                                    BufferedOutputStream(FileOutputStream(targetCanonical)).use { out ->
                                        tarInput.copyTo(out)
                                    }
                                    applyMode(targetCanonical, entry.mode.toInt())
                                }
                            }

                            entry = tarInput.nextTarEntry
                        }
                    }
                }
            }

            // 删除 tar 文件
            tarFile.delete()

            Timber.d("Rootfs extracted successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract rootfs")
            throw e
        }
    }

    /**
     * 设置 Unix 文件权限（参考 ALinux applyMode）
     */
    private fun applyMode(file: File, mode: Int) {
        try {
            file.setReadable(mode and 0x124 != 0, false)
            file.setWritable(mode and 0x92 != 0, false)
            file.setExecutable(mode and 0x49 != 0, false)
        } catch (e: Exception) {
            Timber.w("Failed to set mode ${mode.toString(8)} on ${file.absolutePath}: ${e.message}")
        }
    }

    /**
     * Step 3: 同步脚本到容器（CRLF 规范化，参考 ALinux syncScriptsToContainer）
     */
    private suspend fun syncScriptsToContainer() {
        Timber.d("Linux: 同步脚本到容器（CRLF 规范化）...")

        val scriptsDir = File(getRootfsPath(), "opt/scripts")
        scriptsDir.mkdirs()

        val nodejsDir = File(scriptsDir, "scripts")
        nodejsDir.mkdirs()

        // 脚本列表
        val scripts = listOf(
            "linux/init_packages.sh" to File(scriptsDir, "init_packages.sh"),
            "linux/setup_sshd.sh" to File(scriptsDir, "setup_sshd.sh"),
            "linux/scripts/install_nodejs.sh" to File(nodejsDir, "install_nodejs.sh"),
            "linux/alinux.conf" to File(getRootfsPath(), "etc/alinux.conf")
        )

        scripts.forEach { (assetPath, targetFile) ->
            try {
                copyAssetWithNormalization(assetPath, targetFile)
            } catch (e: Exception) {
                Timber.w("Failed to sync script $assetPath: ${e.message}")
            }
        }

        Timber.d("Linux: 脚本同步完成")
    }

    /**
     * Step 4: 配置 DNS（参考 ALinux setupDns）
     */
    private fun setupDns() {
        val rootfsDir = File(getRootfsPath())

        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isEmpty()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            Timber.d("Linux: DNS 配置写入 etc/resolv.conf")
        }

        val hostsFile = File(rootfsDir, "etc/hosts")
        if (!hostsFile.exists()) {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
    }

    /**
     * Step 5: 验证关键二进制是否存在（参考 ALinux ensureBinaries）
     */
    private fun ensureBinaries(binaries: List<String>): Boolean {
        val rootfsDir = File(getRootfsPath())
        binaries.forEach { binary ->
            val file = File(rootfsDir, binary)
            if (!file.exists() || !file.canExecute()) {
                Timber.e("Linux: 关键二进制不存在或不可执行: $binary")
                return false
            }
            Timber.d("Linux: 验证通过: $binary")
        }
        return true
    }

    /**
     * Step 6: 检测并安装常用工具包（python3、curl 等）。
     * 只在检测到缺失时才执行安装，避免每次启动都跑。
     * Node.js 通过 NodeSource 安装 v22（apt 默认只有 v18，不满足技能商店要求）。
     */
    private fun ensureCommonPackages() {
        val rootfsDir = File(getRootfsPath())

        // Node.js 单独处理 — 优先从 APK 内置预置包解压，其次 NodeSource，最后 apt 回退
        val nodeBin = File(rootfsDir, "/usr/bin/node")
        if (!nodeBin.exists() || !nodeBin.canExecute()) {
            Timber.i("Linux: 检测到 Node.js 缺失，尝试安装...")

            // 方案1: 从 APK assets 中解压预置的 Node.js
            val bundledNode = tryInstallBundledNode(rootfsDir)
            if (bundledNode) {
                Timber.i("Linux: Node.js 从内置预置包安装完成")
            } else {
                // 方案2: 通过 NodeSource 安装 v22
                try {
                    Timber.i("Linux: 内置预置包不可用，通过 NodeSource 安装 v22...")
                    engine?.exec(
                        rootfsDir,
                        "export DEBIAN_FRONTEND=noninteractive && " +
                        "kill $(pgrep -f 'apt-get') 2>/dev/null; " +
                        "apt-get update && " +
                        "apt-get install -y --no-install-recommends curl ca-certificates && " +
                        "curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && " +
                        "apt-get install -y --no-install-recommends nodejs && " +
                        "dpkg --configure -a 2>/dev/null || true",
                        timeout = 600_000
                    )
                    Timber.i("Linux: Node.js 22 (NodeSource) 安装完成")
                } catch (e: Exception) {
                    Timber.w(e, "Linux: NodeSource 安装失败，回退到 apt-get install nodejs")
                    try {
                        engine?.exec(
                            rootfsDir,
                            "export DEBIAN_FRONTEND=noninteractive && " +
                            "apt-get update && " +
                            "apt-get install -y --no-install-recommends nodejs npm && " +
                            "dpkg --configure -a 2>/dev/null || true",
                            timeout = 300_000
                        )
                        Timber.i("Linux: Node.js (apt 回退) 安装完成")
                    } catch (e2: Exception) {
                        Timber.w(e2, "Linux: Node.js 安装完全失败（非致命）")
                    }
                }
            }
        }

        // 其他必备工具 — 使用国内镜像加速
        val requiredPackages = mapOf(
            "python3" to "/usr/bin/python3",
            "curl" to "/usr/bin/curl",
            "git" to "/usr/bin/git"
        )

        val missing = requiredPackages.filter { (_, binaryPath) ->
            val file = File(rootfsDir, binaryPath)
            !file.exists() || !file.canExecute()
        }

        if (missing.isEmpty()) {
            Timber.i("Linux: 所有必备工具已就位")
            return
        }

        val packagesToInstall = missing.keys.joinToString(" ")
        Timber.i("Linux: 检测到缺失必备工具 $packagesToInstall，正在安装...")

        try {
            val rootfsPath = getRootfsPath()
            engine?.exec(
                rootfsDir,
                "export DEBIAN_FRONTEND=noninteractive && " +
                "kill $(pgrep -f 'apt-get') 2>/dev/null; " +
                "apt-get update && " +
                "apt-get install -y --no-install-recommends $packagesToInstall && " +
                "dpkg --configure -a 2>/dev/null || true",
                timeout = 300_000
            )
            Timber.i("Linux: 必备工具安装完成: $packagesToInstall")
        } catch (e: Exception) {
            Timber.w(e, "Linux: 必备工具安装失败（非致命）: $packagesToInstall")
        }
    }

    suspend fun silentEnsureEnvironment() {
        if (!_isReady.value) {
            withTimeoutOrNull(60_000L) {
                _isReady.first { it }
            } ?: run {
                Timber.w("silentEnsureEnvironment: timed out waiting for Linux to be ready")
                return
            }
        }
        withContext(Dispatchers.IO) {
            val checks = listOf(
                Triple("Node.js", "node --version", "nodejs"),
                Triple("npx", "npx --version", "npm"),
                Triple("Python", "python3 --version", "python3"),
            )
            for ((name, checkCmd, pkg) in checks) {
                try {
                    val (code, _) = exec(checkCmd, timeout = 10_000)
                    if (code == 0) {
                        Timber.d("Linux: $name already installed")
                        continue
                    }
                } catch (_: Exception) {}

                Timber.i("Linux: silent installing $name ($pkg)...")
                try {
                    exec(
                        "export DEBIAN_FRONTEND=noninteractive && " +
                        "kill \$(pgrep -f 'apt-get') 2>/dev/null; " +
                        "apt-get update && " +
                        "apt-get install -y --no-install-recommends $pkg && hash -r && " +
                        "dpkg --configure -a 2>/dev/null || true",
                        timeout = 300_000
                    )
                    Timber.i("Linux: $name installed successfully")
                } catch (e: Exception) {
                    Timber.w(e, "Linux: $name install failed (non-fatal)")
                }
            }
        }
    }

    /**
     * 复制 asset 文件到目标路径，带 CRLF→LF 规范化
     * 参考 ALinux/LinuxManager.copyAsset()（CRLF normalize）
     */
    private fun copyAssetWithNormalization(assetPath: String, targetFile: File) {
        context.assets.open(assetPath).use { input ->
            // 统一读取为文本再做 CRLF 规范化（.sh 文件）
            val content = InputStreamReader(input, Charsets.UTF_8).readText()
            val normalized = if (assetPath.endsWith(".sh")) {
                // CRLF/CR → LF（Windows 开发环境修复）
                content.replace("\r\n", "\n").replace("\r", "\n")
            } else {
                content
            }
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(normalized, Charsets.UTF_8)
            if (assetPath.endsWith(".sh")) {
                targetFile.setExecutable(true, false)
            }
        }
        Timber.d("Linux: 同步脚本 ${targetFile.name}（CRLF 已规范化）")
    }

    /**
     * 计算磁盘使用量
     */
    private fun calculateDiskUsage(): Long {
        val rootfsDir = File(getRootfsPath())
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return 0
        return try {
            rootfsDir.walkTopDown()
                .onEnter { it.isDirectory }
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (_: AssertionError) {
            0
        }
    }

    /**
     * 尝试从 APK assets 中解压预置的 Node.js 到 rootfs。
     * 预置文件路径: assets/bundled/node-v22-arm64.tar.xz
     * 如果预置文件不存在则返回 false，走网络安装流程。
     */
    private fun tryInstallBundledNode(rootfsDir: File): Boolean {
        return try {
            val assetPath = "bundled/node-v22-arm64.tar.xz"
            val inputStream = context.assets.open(assetPath)
            val tempFile = File(context.cacheDir, "node-bundled.tar.xz")
            tempFile.outputStream().use { output -> inputStream.copyTo(output) }

            Timber.i("Linux: 找到内置 Node.js 预置包，正在解压...")
            engine?.exec(
                rootfsDir,
                "tar -xJf /shared/node-bundled.tar.xz -C / --strip-components=1",
                timeout = 120_000
            )

            // 清理临时文件
            tempFile.delete()

            // 验证安装
            val nodeBin = File(rootfsDir, "/usr/bin/node")
            val success = nodeBin.exists() && nodeBin.canExecute()
            if (success) {
                Timber.i("Linux: 内置 Node.js 安装验证成功")
            } else {
                Timber.w("Linux: 内置 Node.js 安装后验证失败")
            }
            success
        } catch (e: Exception) {
            Timber.d("Linux: 内置 Node.js 预置包不可用: ${e.message}")
            false
        }
    }
}

/**
 * Linux 环境状态
 */
data class LinuxStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val rootfsPath: String = "",
    val diskUsage: Long = 0,
    val error: String? = null
)

