package com.lin.hippyagent.ui.settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.ui.components.HippyTopBar
import androidx.compose.material3.Scaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.json.JSONObject
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * 环境安装状态单例，跨 Composable 生命周期保持安装进程
 */
internal object EnvInstallState {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val installingItemName = mutableStateOf<String?>(null)
    val installProgress = mutableStateOf("")

    fun isInstalling(name: String): Boolean = installingItemName.value == name
}

/**
 * 环境项定义
 */
data class EnvItem(
    val name: String,
    val description: String,
    val checkCommands: List<String>,  // 检测命令列表，任一成功即视为已安装
    val installHint: String = "",     // 安装提示
    val category: String = "必备"     // 分类
)

/**
 * 环境检测状态
 */
@Immutable
data class EnvCheckState(
    val name: String,
    val isInstalled: Boolean?,
    val version: String = "",
    val isChecking: Boolean = false,
    val isInstalling: Boolean = false,
    val installOutput: String = "",
    val installProgress: String = ""
)

/**
 * 预定义的常用/必备环境列表
 */
val BUILTIN_ENV_ITEMS = listOf(
    // 必备环境
    EnvItem(
        name = "Node.js + npx",
        description = "JavaScript 运行时 + 包执行器，技能商店和部分工具依赖（需 v22+）",
        checkCommands = listOf("node --version", "node -v"),
        installHint = "nodesource",
        category = "必备"
    ),
    EnvItem(
        name = "Python",
        description = "Python 运行时，代码执行和脚本工具依赖",
        checkCommands = listOf("python3 --version", "python --version"),
        installHint = "apt-get update && apt-get install -y python3 python3-pip",
        category = "必备"
    ),
    EnvItem(
        name = "Git",
        description = "版本控制，代码仓库操作和技能安装依赖",
        checkCommands = listOf("git --version"),
        installHint = "apt-get update && apt-get install -y git",
        category = "常用"
    ),
    EnvItem(
        name = "pip",
        description = "Python 包管理器",
        checkCommands = listOf("pip3 --version", "pip --version"),
        installHint = "apt-get update && apt-get install -y python3-pip",
        category = "常用"
    ),
    EnvItem(
        name = "SSH",
        description = "远程连接和文件传输",
        checkCommands = listOf("ssh -V 2>&1"),
        installHint = "apt-get update && apt-get install -y openssh-client",
        category = "常用"
    ),
    EnvItem(
        name = "vim",
        description = "终端文本编辑器",
        checkCommands = listOf("vim --version | head -1"),
        installHint = "apt-get update && apt-get install -y vim",
        category = "常用"
    ),
    EnvItem(
        name = "jq",
        description = "JSON 命令行处理器",
        checkCommands = listOf("jq --version"),
        installHint = "apt-get update && apt-get install -y jq",
        category = "常用"
    ),
    EnvItem(
        name = "ffmpeg",
        description = "音视频处理工具",
        checkCommands = listOf("ffmpeg -version | head -1"),
        installHint = "apt-get update && apt-get install -y ffmpeg",
        category = "常用"
    ),
    EnvItem(
        name = "himalaya",
        description = "CLI 邮件客户端，支持 IMAP/SMTP 收发邮件",
        checkCommands = listOf("himalaya --version"),
        installHint = "curl -L https://github.com/pimalaya/himalaya/releases/latest/download/himalaya-linux-arm64 -o /usr/local/bin/himalaya && chmod +x /usr/local/bin/himalaya",
        category = "常用"
    ),
    // 网络（Android 原生实现，无需 Linux 环境）
    EnvItem(
        name = "网页访问（OkHttp + WebView）",
        description = "Android 原生网页访问，OkHttp 轻量 HTTP + WebView JS 渲染自动降级",
        checkCommands = listOf(""),
        installHint = "",
        category = "网络"
    ),
    EnvItem(
        name = "cURL（Linux 环境）",
        description = "Linux 终端 HTTP 客户端，仅 Linux 环境中使用",
        checkCommands = listOf("curl --version"),
        installHint = "apt-get update && apt-get install -y curl",
        category = "网络"
    ),
    // 语音扩展
    EnvItem(
        name = "Moonshine STT",
        description = "离线语音转文字，支持中/英/日/韩等多语言，长按麦克风即可语音输入",
        checkCommands = listOf(""),
        installHint = "",
        category = "语音扩展"
    ),
    EnvItem(
        name = "系统 TTS",
        description = "文字转语音播报，使用 Android 系统 TTS 引擎",
        checkCommands = listOf(""),
        installHint = "",
        category = "语音扩展"
    )
)

data class SystemDiagnostic(
    val label: String,
    val value: String,
    val status: DiagnosticStatus = DiagnosticStatus.OK
)

enum class DiagnosticStatus { OK, WARN, FAIL }

suspend fun collectSystemDiagnostics(context: android.content.Context): List<SystemDiagnostic> = withContext(Dispatchers.IO) {
    val list = mutableListOf<SystemDiagnostic>()

    list.add(SystemDiagnostic("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
    list.add(SystemDiagnostic("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}"))
    list.add(SystemDiagnostic("CPU 架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))

    val runtime = Runtime.getRuntime()
    val usedMem = runtime.totalMemory() - runtime.freeMemory()
    val maxMem = runtime.maxMemory()
    list.add(SystemDiagnostic("JVM 内存", "${formatBytes(usedMem)} / ${formatBytes(maxMem)}"))

    try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        val total = stat.blockCountLong * stat.blockSizeLong
        val usedPercent = ((total - available).toFloat() / total * 100).toInt()
        list.add(SystemDiagnostic(
            "存储空间",
            "${formatBytes(available)} 可用 / ${formatBytes(total)} 总计",
            if (usedPercent > 90) DiagnosticStatus.WARN else DiagnosticStatus.OK
        ))
    } catch (_: Exception) {
        list.add(SystemDiagnostic("存储空间", "无法获取", DiagnosticStatus.WARN))
    }

    // 只统计 files/ 子目录大小，避免递归遍历整个 app 目录（含 cache/databases）导致卡死
    val filesDir = context.filesDir
    try {
        val appUsed = estimateFolderSize(filesDir, maxDepth = 3)
        list.add(SystemDiagnostic("应用占用", formatBytes(appUsed)))
    } catch (_: Exception) {
        list.add(SystemDiagnostic("应用占用", "获取失败"))
    }

    try {
        list.add(SystemDiagnostic("可用处理器", "${Runtime.getRuntime().availableProcessors()} 核"))
    } catch (_: Exception) {
    }

    list
}

/** 限制深度的文件夹大小估算，避免递归过深或遇到符号链接循环 */
private fun estimateFolderSize(dir: File, maxDepth: Int): Long {
    if (maxDepth <= 0 || !dir.exists()) return 0L
    return try {
        if (dir.isDirectory) {
            dir.listFiles()?.sumOf { child ->
                if (child.isDirectory) estimateFolderSize(child, maxDepth - 1)
                else child.length()
            } ?: 0L
        } else {
            dir.length()
        }
    } catch (_: Exception) { 0L }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}

@Composable
private fun SystemDiagnosticsCard(diagnostics: List<SystemDiagnostic>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "系统诊断",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            if (diagnostics.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在收集系统信息...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            diagnostics.forEach { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = d.label,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = d.value,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    val tint = when (d.status) {
                        DiagnosticStatus.OK -> Color(0xFF4CAF50)
                        DiagnosticStatus.WARN -> Color(0xFFFFC107)
                        DiagnosticStatus.FAIL -> Color(0xFFF44336)
                    }
                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                        drawCircle(tint)
                    }
                }
            }
        }
    }
}

@Composable
fun EnvCheckScreen(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    onBackClick: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isLinuxReady by linuxManager.isReady.collectAsState(initial = false)
    Timber.d("EnvCheckScreen: isLinuxReady=%s", isLinuxReady)
    var envStates by remember { mutableStateOf(BUILTIN_ENV_ITEMS.map { EnvCheckState(name = it.name, isInstalled = null) }) }
    var isCheckingAll by remember { mutableStateOf(false) }

    // 从单例读取当前安装状态
    val installingName by EnvInstallState.installingItemName
    val currentInstallProgress by EnvInstallState.installProgress
    var installingIndex by remember { mutableStateOf(-1) }

    // 同步单例安装状态到本地 installingIndex
    LaunchedEffect(installingName) {
        if (installingName != null) {
            val idx = BUILTIN_ENV_ITEMS.indexOfFirst { it.name == installingName }
            installingIndex = idx
        } else {
            installingIndex = -1
        }
    }

    // 网络类（Android 原生）直接设为已就绪
    LaunchedEffect(Unit) {
        envStates = envStates.toMutableList().apply {
            BUILTIN_ENV_ITEMS.forEachIndexed { i, item ->
                if (item.category == "网络" && item.name != "cURL（Linux 环境）") {
                    this[i] = this[i].copy(isInstalled = true, version = "Android 原生组件，始终可用")
                }
            }
        }
    }

    // 加载缓存状态（优先显示缓存结果，Linux 就绪后自动检测会覆盖）
    LaunchedEffect(Unit) {
        val cached = loadCachedEnvStates(context)
        if (cached != null) {
            envStates = cached
        }
    }

    // Linux 未就绪时自动尝试初始化（延迟等待 StateFlow 初始值同步）
    LaunchedEffect(Unit) {
        // collectAsState(initial=false) 在 composition 时可能还没收到 flow 的真实值
        // 延迟一帧确保 StateFlow 的最新值已到达
        kotlinx.coroutines.delay(100)
        if (!isLinuxReady) {
            try {
                Timber.d("EnvCheckScreen: calling linuxManager.initialize() because isLinuxReady=%s", isLinuxReady)
                linuxManager.initialize()
            } catch (_: Exception) {
            }
        }
    }

    // Linux 就绪后自动检测（首次进入或从未就绪变为就绪时均触发）
    LaunchedEffect(isLinuxReady) {
        if (isLinuxReady) {
            isCheckingAll = true
            checkAllEnvs(linuxManager, BUILTIN_ENV_ITEMS) { newStates ->
                // 保留原生网络项的状态和正在安装的项的状态不被覆盖
                val merged = envStates.toMutableList()
                newStates.forEachIndexed { i, s ->
                    val isNativeNetwork = BUILTIN_ENV_ITEMS[i].category == "网络" && BUILTIN_ENV_ITEMS[i].name != "cURL（Linux 环境）"
                    val isCurrentlyInstalling = EnvInstallState.installingItemName.value == BUILTIN_ENV_ITEMS[i].name
                    if (!isNativeNetwork && !isCurrentlyInstalling) {
                        merged[i] = s
                    }
                }
                envStates = merged
                isCheckingAll = false
                saveCachedEnvStates(context, newStates)
            }
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "环境检测",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        if (!isLinuxReady) {
            // Linux 未就绪
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = androidx.compose.ui.graphics.Color(0xFFFF9800)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Linux 环境初始化中",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "正在自动初始化 Linux 环境，请稍候...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        } else {
            var diagnostics by remember { mutableStateOf(emptyList<SystemDiagnostic>()) }
            LaunchedEffect(Unit) {
                diagnostics = collectSystemDiagnostics(context)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    SystemDiagnosticsCard(diagnostics)
                }

                item {
                    val installed = envStates.count { it.isInstalled == true }
                    val total = envStates.size
                    val checking = envStates.any { it.isChecking }
                    val isCheckingAny = checking || isCheckingAll

                    if (isCheckingAny) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "正在检测环境...",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "环境概览",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "$installed / $total 已就绪",
                                    fontSize = 15.sp,
                                    color = if (installed == total) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        isCheckingAll = true
                                        coroutineScope.launch {
                                            checkAllEnvs(linuxManager, BUILTIN_ENV_ITEMS) { newStates ->
                                                envStates = newStates
                                                isCheckingAll = false
                                                saveCachedEnvStates(context, newStates)
                                            }
                                        }
                                    }) {
                                        Text("重新检测")
                                    }
                                }
                            }
                        }
                    }
                }

                // 按分类分组显示
                val categories = BUILTIN_ENV_ITEMS.map { it.category }.distinct()
                categories.forEach { category ->
                    val categoryItems = BUILTIN_ENV_ITEMS.filter { it.category == category }
                    item {
                        Text(
                            text = category,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    items(categoryItems, key = { it.name }) { envItem ->
                        val stateIndex = BUILTIN_ENV_ITEMS.indexOf(envItem)
                        val state = envStates.getOrNull(stateIndex) ?: EnvCheckState(envItem.name, null)
                        val anyInstalling = installingIndex >= 0

                        // 语音扩展分类使用专用卡片
                        if (category == "语音扩展") {
                            VoiceExtensionCard(
                                envItem = envItem,
                                state = state
                            )
                        } else if (category == "网络" && envItem.name != "cURL（Linux 环境）") {
                            NativeNetworkCard(
                                envItem = envItem,
                                state = state
                            )
                        } else {
                        EnvCheckCard(
                            envItem = envItem,
                            state = state,
                            isInstalling = installingIndex == stateIndex,
                            isOtherInstalling = anyInstalling && installingIndex != stateIndex,
                            onInstall = {
                                if (installingIndex >= 0) return@EnvCheckCard // 安装进行中，禁止重复点击
                                installingIndex = stateIndex
                                EnvInstallState.installingItemName.value = envItem.name
                                EnvInstallState.scope.launch {
                                    val result = installEnv(linuxManager, envItem) { progress ->
                                        EnvInstallState.installProgress.value = progress
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            envStates = envStates.toMutableList().apply {
                                                this[stateIndex] = this[stateIndex].copy(installProgress = progress)
                                            }
                                        }
                                    }
                                    // 安装后等一下让文件系统同步 — proot 环境需要更长时间
                                    kotlinx.coroutines.delay(1500)
                                    var checkState = checkSingleEnv(linuxManager, envItem)
                                    // 如果首次验证失败，再尝试一次宽松检测（直接执行版本命令）
                                    if (checkState.isInstalled != true) {
                                        kotlinx.coroutines.delay(1000)
                                        for (cmd in envItem.checkCommands) {
                                            try {
                                                val (code, output) = linuxManager.exec(cmd, timeout = 10_000)
                                                if (code == 0 && output.isNotBlank()) {
                                                    checkState = checkState.copy(
                                                        isInstalled = true,
                                                        version = output.lines().firstOrNull()?.take(60) ?: ""
                                                    )
                                                    break
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    // 三次验证：尝试 dpkg -L 找到实际二进制路径并直接执行
                                    if (checkState.isInstalled != true) {
                                        kotlinx.coroutines.delay(500)
                                        try {
                                            val pkgName = envItem.installHint.substringAfter("install ").trim().split(" ").firstOrNull() ?: envItem.name.lowercase()
                                            val (lsCode, lsOutput) = linuxManager.exec("dpkg -L $pkgName 2>/dev/null | grep -E 'bin/' | head -5", timeout = 5_000)
                                            if (lsCode == 0 && lsOutput.isNotBlank()) {
                                                val binPaths = lsOutput.lines().filter { it.contains("bin/") && it.isNotBlank() }
                                                for (binPath in binPaths) {
                                                    val path = binPath.trim()
                                                    try {
                                                        val (tCode, tOutput) = linuxManager.exec("$path --version 2>&1 || $path -v 2>&1 || $path version 2>&1", timeout = 5_000)
                                                        if (tCode == 0 || tOutput.isNotBlank()) {
                                                            checkState = checkState.copy(
                                                                isInstalled = true,
                                                                version = "已安装 ($path) — ${tOutput.lines().firstOrNull()?.take(40) ?: ""}"
                                                            )
                                                            break
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    val installSucceeded = result == "安装成功"
                                    val newState = if (installSucceeded && checkState.isInstalled != true) {
                                        // apt 返回成功但验证不通过 — 可能是 proot 环境缓存问题
                                        checkState.copy(
                                            isInstalled = null,
                                            installOutput = "安装命令已执行成功，但验证未通过。\n" +
                                                "这通常是因为 proot 环境的 PATH/链接缓存未刷新。\n\n" +
                                                "建议操作：\n" +
                                                "1. 重启应用后再次检测（最有效）\n" +
                                                "2. 在终端手动执行 hash -r 刷新命令缓存\n\n" +
                                                "验证详情: ${checkState.version.ifBlank { "无法获取版本信息"}}",
                                            installProgress = ""
                                        )
                                    } else {
                                        checkState.copy(installOutput = result, installProgress = "")
                                    }
                                    EnvInstallState.installingItemName.value = null
                                    EnvInstallState.installProgress.value = ""
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        try {
                                            envStates = envStates.toMutableList().apply {
                                                this[stateIndex] = newState
                                            }
                                            installingIndex = -1
                                        } catch (_: Exception) {
                                        }
                                        val cachedStates = loadCachedEnvStates(context)?.toMutableList() ?: BUILTIN_ENV_ITEMS.map { EnvCheckState(name = it.name, isInstalled = null) }.toMutableList()
                                        val cachedIndex = cachedStates.indexOfFirst { it.name == BUILTIN_ENV_ITEMS[stateIndex].name }
                                        if (cachedIndex >= 0) {
                                            cachedStates[cachedIndex] = newState
                                        }
                                        saveCachedEnvStates(context, cachedStates)
                                    }
                                }
                            }
                        )
                        } // end if/else voice/network extension
                    }
                }

                // 底部留白
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun EnvCheckCard(
    envItem: EnvItem,
    state: EnvCheckState,
    isInstalling: Boolean,
    isOtherInstalling: Boolean = false,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            when {
                state.isChecking -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                state.isInstalled == true -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已安装",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                state.isInstalled == false -> Icon(
                    Icons.Default.Cancel,
                    contentDescription = "未安装",
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                else -> Icon(
                    Icons.Default.Terminal,
                    contentDescription = "待检测",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 名称和描述
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = envItem.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = envItem.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.version.isNotBlank()) {
                    Text(
                        text = state.version,
                        fontSize = 11.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
                if (state.installOutput.isNotBlank()) {
                    Text(
                        text = state.installOutput,
                        fontSize = 11.sp,
                        color = if (state.isInstalled == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                }
                if (state.installProgress.isNotBlank()) {
                    Text(
                        text = state.installProgress,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 安装按钮
            if (state.isInstalled == false && envItem.installHint.isNotBlank()) {
                if (isInstalling) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("安装中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (isOtherInstalling) {
                    Button(
                        onClick = {},
                        enabled = false,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("等待中", fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("安装", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 检测单个环境 — 先试 version 命令，再试 command -v，最后试 which + 文件存在性验证
 */
private suspend fun checkSingleEnv(linuxManager: com.lin.hippyagent.core.linux.LinuxManager, envItem: EnvItem): EnvCheckState {
    // 第一轮：尝试 version 命令（最可靠 — 确认二进制实际可执行）
    for (cmd in envItem.checkCommands) {
        try {
            val (code, output) = linuxManager.exec(cmd, timeout = 10_000)
            if (code == 0 && output.isNotBlank()) {
                return EnvCheckState(
                    name = envItem.name,
                    isInstalled = true,
                    version = output.lines().firstOrNull()?.take(60) ?: ""
                )
            }
        } catch (e: Exception) {
            Timber.d(e, "Check failed for ${envItem.name}: $cmd")
        }
    }
    // 第二轮：command -v 验证（确认二进制在 PATH 中）
    val binName = envItem.checkCommands.firstOrNull()?.split(" ")?.firstOrNull() ?: envItem.name.lowercase()
    try {
        val (code, output) = linuxManager.exec("command -v $binName", timeout = 5_000)
        if (code == 0 && output.isNotBlank()) {
            // 找到路径，再验证文件是否真实存在且可执行
            val binPath = output.trim()
            try {
                val (lsCode, _) = linuxManager.exec("test -x $binPath && echo ok", timeout = 3_000)
                if (lsCode == 0) {
                    return EnvCheckState(
                        name = envItem.name,
                        isInstalled = true,
                        version = "已安装 ($binPath) — 版本信息获取失败"
                    )
                }
            } catch (_: Exception) {}
            // command -v 找到了但 test -x 失败 — 可能是 proot/符号链接问题
            return EnvCheckState(
                name = envItem.name,
                isInstalled = false,
                version = "路径存在但不可执行 ($binPath)"
            )
        }
    } catch (_: Exception) {}
    return EnvCheckState(name = envItem.name, isInstalled = false)
}

/**
 * 批量检测所有环境
 */
private suspend fun checkAllEnvs(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    items: List<EnvItem>,
    onResult: (List<EnvCheckState>) -> Unit
) {
    val states = items.map { item ->
        checkSingleEnv(linuxManager, item)
    }
    onResult(states)
}

/**
 * 安装环境
 */
private suspend fun installEnv(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    envItem: EnvItem,
    onProgress: (String) -> Unit = {}
): String {
    if (envItem.installHint.isBlank()) return "无安装命令"
    return try {
        // 先清理可能残留的 dpkg 锁
        onProgress("清理锁文件...")
        try {
            linuxManager.exec("rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null", timeout = 5_000)
        } catch (_: Exception) {}

        // NodeSource 特殊安装路径 — 安装 Node.js 22 + npm/npx
        if (envItem.installHint == "nodesource") {
            return installNodeSource(linuxManager, onProgress)
        }

        onProgress("正在更新包列表...")
        // apt-get update — 捕获完整输出以便诊断
        val (updateCode, updateOutput) = linuxManager.exec("apt-get update 2>&1", timeout = 120_000)
        if (updateCode != 0 && !updateOutput.contains("Reading package lists")) {
            // update 失败但可能有部分成功，继续尝试安装
            Timber.w("apt-get update failed (code=$updateCode): ${updateOutput.take(200)}")
        }

        onProgress("正在安装 ${envItem.name}...")
        val (code, output) = linuxManager.exec(
            "DEBIAN_FRONTEND=noninteractive " + envItem.installHint + " -y 2>&1 | tail -10",
            timeout = 300_000
        )
        onProgress("")
        if (code == 0) {
            // 安装后刷新命令缓存
            try { linuxManager.exec("hash -r 2>/dev/null", timeout = 3_000) } catch (_: Exception) {}
            "安装成功"
        } else {
            "安装失败 (exit=$code): ${output.take(300)}"
        }
    } catch (e: Exception) {
        "安装失败: ${e.message}"
    }
}

/**
 * 通过 NodeSource 安装 Node.js 22 + npm/npx
 * apt-get install nodejs 只提供 v18，不满足 @lobehub/market-cli 的 >=22 要求
 */
private suspend fun installNodeSource(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    onProgress: (String) -> Unit = {}
): String {
    return try {
        onProgress("正在更新包列表...")
        linuxManager.exec("apt-get update 2>&1", timeout = 120_000)

        onProgress("正在安装依赖...")
        linuxManager.exec("DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates 2>&1 | tail -5", timeout = 120_000)

        onProgress("正在添加 NodeSource 仓库 (Node.js 22)...")
        val (setupCode, setupOutput) = linuxManager.exec(
            "curl -fsSL https://deb.nodesource.com/setup_22.x | bash - 2>&1 | tail -20",
            timeout = 120_000
        )
        if (setupCode != 0) {
            Timber.w("NodeSource setup failed (code=$setupCode): ${setupOutput.take(200)}")
        }

        onProgress("正在安装 Node.js 22...")
        val (installCode, installOutput) = linuxManager.exec(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs 2>&1 | tail -10",
            timeout = 300_000
        )

        if (installCode == 0) {
            // 验证版本
            try { linuxManager.exec("hash -r 2>/dev/null", timeout = 3_000) } catch (_: Exception) {}
            val (verCode, verOutput) = linuxManager.exec("node --version && npm --version && npx --version", timeout = 10_000)
            if (verCode == 0) {
                "安装成功\n$verOutput"
            } else {
                "安装成功（版本验证失败）"
            }
        } else {
            "安装失败 (exit=$installCode): ${installOutput.take(300)}"
        }
    } catch (e: Exception) {
        "安装失败: ${e.message}"
    }
}

/** 缓存 key */
private const val ENV_CACHE_PREFS = "env_check_cache"
private const val ENV_CACHE_KEY = "cached_states"

/** 保存环境检测状态到 SharedPreferences */
private fun saveCachedEnvStates(context: android.content.Context, states: List<EnvCheckState>) {
    try {
        val json = JSONObject()
        states.forEach { state ->
            val obj = JSONObject().apply {
                put("name", state.name)
                put("isInstalled", state.isInstalled ?: JSONObject.NULL)
                put("version", state.version)
            }
            json.put(state.name, obj)
        }
        context.getSharedPreferences(ENV_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(ENV_CACHE_KEY, json.toString())
            .apply()
    } catch (e: Exception) {
        Timber.w(e, "Failed to save env cache")
    }
}

/** 从 SharedPreferences 读取上次缓存的环境检测状态 */
private fun loadCachedEnvStates(context: android.content.Context): List<EnvCheckState>? {
    try {
        val prefs = context.getSharedPreferences(ENV_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(ENV_CACHE_KEY, null) ?: return null
        val json = JSONObject(raw)
        return BUILTIN_ENV_ITEMS.map { item ->
            val obj = json.optJSONObject(item.name) ?: return@map EnvCheckState(name = item.name, isInstalled = null)
            val isInstalled = if (obj.isNull("isInstalled")) null else obj.getBoolean("isInstalled")
            EnvCheckState(
                name = item.name,
                isInstalled = isInstalled,
                version = obj.optString("version", "")
            )
        }
    } catch (e: Exception) {
        Timber.w(e, "Failed to load env cache")
        return null
    }
}

/**
 * 语音扩展专用卡片 — 不走 apt-get 安装流程，显示模型下载状态
 */
@Composable
private fun VoiceExtensionCard(
    envItem: EnvItem,
    state: EnvCheckState
) {
    val context = LocalContext.current
    val voiceManager = remember { com.lin.hippyagent.core.voice.VoiceExtensionManager(context) }
    val voiceState by voiceManager.state.collectAsState(initial = com.lin.hippyagent.core.voice.VoiceExtensionState())
    val isSttItem = envItem.name == "Moonshine STT"

    // 初始化
    LaunchedEffect(Unit) {
        voiceManager.initialize()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            if (isSttItem) {
                if (voiceState.deviceUnsupported) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "不支持",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(24.dp)
                    )
                } else if (voiceState.sttModel != null) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已安装",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = "待下载",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                // TTS 始终可用
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已就绪",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 名称和描述
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = envItem.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = envItem.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isSttItem) {
                    when {
                        voiceState.deviceUnsupported -> {
                            Text(
                                text = "需要 Android 15+ (API 35) 设备",
                                fontSize = 11.sp,
                                color = Color(0xFFF44336)
                            )
                        }
                        voiceState.sttModel != null -> {
                            Text(
                                text = "${voiceState.sttModel!!.size.displayName} · ${voiceState.sttModel!!.language.uppercase()} · ${voiceState.sttModel!!.size.approxSize}",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        voiceState.isSttDownloading -> {
                            LinearProgressIndicator(
                                progress = voiceState.downloadProgress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = "使用 Android 系统 TTS 引擎，无需下载",
                        fontSize = 11.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/**
 * 原生网络组件专用卡片 — OkHttp + WebView 始终可用
 */
@Composable
private fun NativeNetworkCard(
    envItem: EnvItem,
    state: EnvCheckState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "已就绪",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = envItem.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = envItem.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Android 原生组件，始终可用",
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}
