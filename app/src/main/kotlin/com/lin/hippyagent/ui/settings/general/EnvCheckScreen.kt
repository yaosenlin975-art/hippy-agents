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
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar
import androidx.compose.ui.res.stringResource
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
    val descriptionRes: Int,
    val checkCommands: List<String>,
    val installHint: String = "",
    val categoryRes: Int = R.string.env_cat_essential
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
    EnvItem(
        name = "Node.js + npx",
        descriptionRes = R.string.env_item_nodejs_desc,
        checkCommands = listOf("node --version", "node -v"),
        installHint = "nodesource",
        categoryRes = R.string.env_cat_essential
    ),
    EnvItem(
        name = "Python",
        descriptionRes = R.string.env_item_python_desc,
        checkCommands = listOf("python3 --version", "python --version"),
        installHint = "apt-get update && apt-get install -y python3 python3-pip",
        categoryRes = R.string.env_cat_essential
    ),
    EnvItem(
        name = "Git",
        descriptionRes = R.string.env_item_git_desc,
        checkCommands = listOf("git --version"),
        installHint = "apt-get update && apt-get install -y git",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "pip",
        descriptionRes = R.string.env_item_pip_desc,
        checkCommands = listOf("pip3 --version", "pip --version"),
        installHint = "apt-get update && apt-get install -y python3-pip",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "SSH",
        descriptionRes = R.string.env_item_ssh_desc,
        checkCommands = listOf("ssh -V 2>&1"),
        installHint = "apt-get update && apt-get install -y openssh-client",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "vim",
        descriptionRes = R.string.env_item_vim_desc,
        checkCommands = listOf("vim --version | head -1"),
        installHint = "apt-get update && apt-get install -y vim",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "jq",
        descriptionRes = R.string.env_item_jq_desc,
        checkCommands = listOf("jq --version"),
        installHint = "apt-get update && apt-get install -y jq",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "ffmpeg",
        descriptionRes = R.string.env_item_ffmpeg_desc,
        checkCommands = listOf("ffmpeg -version | head -1"),
        installHint = "apt-get update && apt-get install -y ffmpeg",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "himalaya",
        descriptionRes = R.string.env_item_himalaya_desc,
        checkCommands = listOf("himalaya --version"),
        installHint = "curl -L https://github.com/pimalaya/himalaya/releases/latest/download/himalaya-linux-arm64 -o /usr/local/bin/himalaya && chmod +x /usr/local/bin/himalaya",
        categoryRes = R.string.env_cat_common
    ),
    EnvItem(
        name = "网页访问（OkHttp + WebView）",
        descriptionRes = R.string.env_item_web_access_desc,
        checkCommands = listOf(""),
        installHint = "",
        categoryRes = R.string.env_cat_network
    ),
    EnvItem(
        name = "cURL（Linux 环境）",
        descriptionRes = R.string.env_item_curl_desc,
        checkCommands = listOf("curl --version"),
        installHint = "apt-get update && apt-get install -y curl",
        categoryRes = R.string.env_cat_network
    ),
    EnvItem(
        name = "Moonshine STT",
        descriptionRes = R.string.env_item_moonshine_desc,
        checkCommands = listOf(""),
        installHint = "",
        categoryRes = R.string.env_cat_voice
    ),
    EnvItem(
        name = "系统 TTS",
        descriptionRes = R.string.env_item_system_tts_desc,
        checkCommands = listOf(""),
        installHint = "",
        categoryRes = R.string.env_cat_voice
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

    list.add(SystemDiagnostic(context.getString(R.string.env_android_version), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
    list.add(SystemDiagnostic(context.getString(R.string.env_device_model), "${Build.MANUFACTURER} ${Build.MODEL}"))
    list.add(SystemDiagnostic(context.getString(R.string.env_cpu_arch), Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))

    val runtime = Runtime.getRuntime()
    val usedMem = runtime.totalMemory() - runtime.freeMemory()
    val maxMem = runtime.maxMemory()
    list.add(SystemDiagnostic(context.getString(R.string.env_jvm_memory), "${formatBytes(usedMem)} / ${formatBytes(maxMem)}"))

    try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        val total = stat.blockCountLong * stat.blockSizeLong
        val usedPercent = ((total - available).toFloat() / total * 100).toInt()
        list.add(SystemDiagnostic(
            context.getString(R.string.env_storage_space),
            context.getString(R.string.env_storage_available_format, formatBytes(available), formatBytes(total)),
            if (usedPercent > 90) DiagnosticStatus.WARN else DiagnosticStatus.OK
        ))
    } catch (_: Exception) {
        list.add(SystemDiagnostic(context.getString(R.string.env_storage_space), context.getString(R.string.env_cannot_get), DiagnosticStatus.WARN))
    }

    val filesDir = context.filesDir
    try {
        val appUsed = estimateFolderSize(filesDir, maxDepth = 3)
        list.add(SystemDiagnostic(context.getString(R.string.env_app_usage), formatBytes(appUsed)))
    } catch (_: Exception) {
        list.add(SystemDiagnostic(context.getString(R.string.env_app_usage), context.getString(R.string.env_get_failed)))
    }

    try {
        list.add(SystemDiagnostic(context.getString(R.string.env_available_processors), context.getString(R.string.env_cores, Runtime.getRuntime().availableProcessors())))
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
                text = stringResource(R.string.env_system_diagnostics),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            if (diagnostics.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.env_collecting_info), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (item.categoryRes == R.string.env_cat_network && item.name != "cURL（Linux 环境）") {
                    this[i] = this[i].copy(isInstalled = true, version = context.getString(R.string.env_native_always_available))
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
            checkAllEnvs(linuxManager, BUILTIN_ENV_ITEMS, context) { newStates ->
                val merged = envStates.toMutableList()
                newStates.forEachIndexed { i, s ->
                    val isNativeNetwork = BUILTIN_ENV_ITEMS[i].categoryRes == R.string.env_cat_network && BUILTIN_ENV_ITEMS[i].name != "cURL（Linux 环境）"
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
                title = stringResource(R.string.env_check),
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
                        stringResource(R.string.env_linux_initializing),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.env_linux_auto_init),
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
                                    text = stringResource(R.string.env_checking),
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
                                    text = stringResource(R.string.env_overview),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.env_ready_count, installed, total),
                                    fontSize = 15.sp,
                                    color = if (installed == total) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        isCheckingAll = true
                                        coroutineScope.launch {
                                            checkAllEnvs(linuxManager, BUILTIN_ENV_ITEMS, context) { newStates ->
                                                envStates = newStates
                                                isCheckingAll = false
                                                saveCachedEnvStates(context, newStates)
                                            }
                                        }
                                    }) {
                                        Text(stringResource(R.string.env_recheck))
                                    }
                                }
                            }
                        }
                    }
                }

                // 按分类分组显示
                val categories = BUILTIN_ENV_ITEMS.map { it.categoryRes }.distinct()
                categories.forEach { categoryRes ->
                    val categoryItems = BUILTIN_ENV_ITEMS.filter { it.categoryRes == categoryRes }
                    item {
                        Text(
                            text = stringResource(categoryRes),
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
                        if (categoryRes == R.string.env_cat_voice) {
                            VoiceExtensionCard(
                                envItem = envItem,
                                state = state
                            )
                        } else if (categoryRes == R.string.env_cat_network && envItem.name != "cURL（Linux 环境）") {
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
                                    val result = installEnv(linuxManager, envItem, context) { progress ->
                                        EnvInstallState.installProgress.value = progress
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            envStates = envStates.toMutableList().apply {
                                                this[stateIndex] = this[stateIndex].copy(installProgress = progress)
                                            }
                                        }
                                    }
                                    // 安装后等一下让文件系统同步 — proot 环境需要更长时间
                                    kotlinx.coroutines.delay(1500)
                                    var checkState = checkSingleEnv(linuxManager, envItem, context)
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
                                                                version = context.getString(R.string.env_installed_with_version, path, tOutput.lines().firstOrNull()?.take(40) ?: "")
                                                            )
                                                            break
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    val installSucceeded = result == context.getString(R.string.env_install_success)
                                    val newState = if (installSucceeded && checkState.isInstalled != true) {
                                        checkState.copy(
                                            isInstalled = null,
                                            installOutput = context.getString(R.string.env_install_verify_failed_msg, checkState.version.ifBlank { context.getString(R.string.env_version_unavailable) }),
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
                    contentDescription = stringResource(R.string.env_installed),
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                state.isInstalled == false -> Icon(
                    Icons.Default.Cancel,
                    contentDescription = stringResource(R.string.env_not_installed),
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                else -> Icon(
                    Icons.Default.Terminal,
                    contentDescription = stringResource(R.string.env_pending_check),
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
                    text = stringResource(envItem.descriptionRes),
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
                        Text(stringResource(R.string.env_installing), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (isOtherInstalling) {
                    Button(
                        onClick = {},
                        enabled = false,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.env_waiting), fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.env_install_btn), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 检测单个环境 — 先试 version 命令，再试 command -v，最后试 which + 文件存在性验证
 */
private suspend fun checkSingleEnv(linuxManager: com.lin.hippyagent.core.linux.LinuxManager, envItem: EnvItem, context: android.content.Context): EnvCheckState {
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
                        version = context.getString(R.string.env_installed_with_path, binPath) + " — " + context.getString(R.string.env_version_unavailable)
                    )
                }
            } catch (_: Exception) {}
            // command -v 找到了但 test -x 失败 — 可能是 proot/符号链接问题
            return EnvCheckState(
                name = envItem.name,
                isInstalled = false,
                version = context.getString(R.string.env_path_not_executable, binPath)
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
    context: android.content.Context,
    onResult: (List<EnvCheckState>) -> Unit
) {
    val states = items.map { item ->
        checkSingleEnv(linuxManager, item, context)
    }
    onResult(states)
}

/**
 * 安装环境
 */
private suspend fun installEnv(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    envItem: EnvItem,
    context: android.content.Context,
    onProgress: (String) -> Unit = {}
): String {
    if (envItem.installHint.isBlank()) return context.getString(R.string.env_no_install_command)
    return try {
        onProgress(context.getString(R.string.env_clearing_locks))
        try {
            linuxManager.exec("rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null", timeout = 5_000)
        } catch (_: Exception) {}

        if (envItem.installHint == "nodesource") {
            return installNodeSource(linuxManager, context, onProgress)
        }

        onProgress(context.getString(R.string.env_updating_packages))
        val (updateCode, updateOutput) = linuxManager.exec("apt-get update 2>&1", timeout = 120_000)
        if (updateCode != 0 && !updateOutput.contains("Reading package lists")) {
            Timber.w("apt-get update failed (code=$updateCode): ${updateOutput.take(200)}")
        }

        onProgress(context.getString(R.string.env_installing_name, envItem.name))
        val (code, output) = linuxManager.exec(
            "DEBIAN_FRONTEND=noninteractive " + envItem.installHint + " -y 2>&1 | tail -10",
            timeout = 300_000
        )
        onProgress("")
        if (code == 0) {
            try { linuxManager.exec("hash -r 2>/dev/null", timeout = 3_000) } catch (_: Exception) {}
            context.getString(R.string.env_install_success)
        } else {
            context.getString(R.string.env_install_failed) + " (exit=$code): ${output.take(300)}"
        }
    } catch (e: Exception) {
        context.getString(R.string.env_install_failed) + ": ${e.message}"
    }
}

/**
 * 通过 NodeSource 安装 Node.js 22 + npm/npx
 * apt-get install nodejs 只提供 v18，不满足 @lobehub/market-cli 的 >=22 要求
 */
private suspend fun installNodeSource(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    context: android.content.Context,
    onProgress: (String) -> Unit = {}
): String {
    return try {
        onProgress(context.getString(R.string.env_updating_packages))
        linuxManager.exec("apt-get update 2>&1", timeout = 120_000)

        onProgress(context.getString(R.string.env_installing_deps))
        linuxManager.exec("DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates 2>&1 | tail -5", timeout = 120_000)

        onProgress(context.getString(R.string.env_adding_nodesource))
        val (setupCode, setupOutput) = linuxManager.exec(
            "curl -fsSL https://deb.nodesource.com/setup_22.x | bash - 2>&1 | tail -20",
            timeout = 120_000
        )
        if (setupCode != 0) {
            Timber.w("NodeSource setup failed (code=$setupCode): ${setupOutput.take(200)}")
        }

        onProgress(context.getString(R.string.env_installing_nodejs))
        val (installCode, installOutput) = linuxManager.exec(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs 2>&1 | tail -10",
            timeout = 300_000
        )

        if (installCode == 0) {
            try { linuxManager.exec("hash -r 2>/dev/null", timeout = 3_000) } catch (_: Exception) {}
            val (verCode, verOutput) = linuxManager.exec("node --version && npm --version && npx --version", timeout = 10_000)
            if (verCode == 0) {
                context.getString(R.string.env_install_success_verify, verOutput)
            } else {
                context.getString(R.string.env_install_success_verify_failed)
            }
        } else {
            context.getString(R.string.env_install_failed) + " (exit=$installCode): ${installOutput.take(300)}"
        }
    } catch (e: Exception) {
        context.getString(R.string.env_install_failed) + ": ${e.message}"
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
                        contentDescription = stringResource(R.string.env_unsupported),
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(24.dp)
                    )
                } else if (voiceState.sttModel != null) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.env_installed),
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.env_pending_download),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                // TTS 始终可用
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.env_ready),
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
                    text = stringResource(envItem.descriptionRes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isSttItem) {
                    when {
                        voiceState.deviceUnsupported -> {
                            Text(
                                text = stringResource(R.string.env_needs_api_35),
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
                        text = stringResource(R.string.env_tts_no_download),
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
                contentDescription = stringResource(R.string.env_ready),
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
                    text = stringResource(envItem.descriptionRes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.env_native_always_available),
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}
