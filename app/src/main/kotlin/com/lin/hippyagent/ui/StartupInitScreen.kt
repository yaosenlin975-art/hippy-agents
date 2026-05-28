package com.lin.hippyagent.ui

// TODO: Wire up navigation — this screen has planned functionality (see docs/architecture-review.md)
//  Should be expanded into a full startup initialization flow. Currently not registered in NavHost.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

private val REQUIRED_ENV_ITEMS = listOf(
    Triple("Node.js", listOf("node --version", "node -v"), "nodesource"),
    Triple("Python", listOf("python3 --version", "python --version"), "apt-get update && apt-get install -y python3 python3-pip"),
    Triple("curl", listOf("curl --version"), "apt-get update && apt-get install -y curl")
)

@Immutable
private data class InitStep(
    val name: String,
    val status: StepStatus = StepStatus.PENDING
)

private enum class StepStatus { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED }

@Composable
fun StartupInitScreen(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    onComplete: () -> Unit
) {
    val isLinuxReady by linuxManager.isReady.collectAsState(initial = false)

    var phase by remember { mutableStateOf("init") }
    var steps by remember { mutableStateOf(listOf<InitStep>()) }
    var currentMessage by remember { mutableStateOf("正在初始化...") }
    var showSkipButton by remember { mutableStateOf(false) }
    var allDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Phase 1: 初始化 Linux
        phase = "linux"
        currentMessage = "正在启动 Linux 环境..."
        steps = listOf(InitStep("启动 Linux 环境", StepStatus.IN_PROGRESS))

        if (!isLinuxReady) {
            try {
                withContext(Dispatchers.IO) { linuxManager.initialize() }
            } catch (_: Exception) {}
        }

        delay(500)
        if (isLinuxReady) {
            steps = listOf(InitStep("启动 Linux 环境", StepStatus.DONE))
        } else {
            steps = listOf(InitStep("启动 Linux 环境", StepStatus.FAILED))
            currentMessage = "Linux 环境启动失败，部分功能不可用"
            showSkipButton = true
            return@LaunchedEffect
        }

        // Phase 2: 检测必备环境
        phase = "check"
        currentMessage = "正在检测必备环境..."
        val checkSteps = REQUIRED_ENV_ITEMS.map { InitStep(it.first, StepStatus.IN_PROGRESS) }
        steps = listOf(InitStep("启动 Linux 环境", StepStatus.DONE)) + checkSteps

        val checkResults = mutableListOf<Pair<String, Boolean>>()
        for ((name, commands, _) in REQUIRED_ENV_ITEMS) {
            val installed = withContext(Dispatchers.IO) { checkEnvInstalled(linuxManager, commands) }
            checkResults.add(name to installed)
            val idx = REQUIRED_ENV_ITEMS.indexOfFirst { it.first == name }
            steps = steps.toMutableList().also {
                it[idx + 1] = InitStep(name, if (installed) StepStatus.DONE else StepStatus.PENDING)
            }
            delay(300)
        }

        // Phase 3: 安装缺失环境
        val missing = checkResults.filter { !it.second }
        if (missing.isEmpty()) {
            currentMessage = "所有必备环境已就绪"
            allDone = true
            delay(800)
            onComplete()
            return@LaunchedEffect
        }

        phase = "install"
        currentMessage = "正在安装缺失环境..."
        showSkipButton = true

        for ((name, _) in missing) {
            val (_, _, installHint) = REQUIRED_ENV_ITEMS.first { it.first == name }
            val idx = REQUIRED_ENV_ITEMS.indexOfFirst { it.first == name }
            steps = steps.toMutableList().also {
                it[idx + 1] = InitStep(name, StepStatus.IN_PROGRESS)
            }
            currentMessage = "正在安装 $name..."

            val success = try {
                withContext(Dispatchers.IO) { installEnv(linuxManager, name, installHint) }
                true
            } catch (e: Exception) {
                Timber.w(e, "Install failed: $name")
                false
            }

            steps = steps.toMutableList().also {
                it[idx + 1] = InitStep(name, if (success) StepStatus.DONE else StepStatus.FAILED)
            }
            delay(500)
        }

        currentMessage = if (steps.any { it.status == StepStatus.FAILED }) "部分环境安装失败，可稍后手动安装" else "所有必备环境已就绪"
        allDone = true
        delay(1000)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                text = "🐾",
                fontSize = 48.sp
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Hippy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "正在初始化环境，请稍候...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            if (phase == "init") {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = currentMessage,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        steps.forEach { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (step.status) {
                                    StepStatus.DONE -> Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    StepStatus.IN_PROGRESS -> CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    StepStatus.FAILED -> Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        tint = Color(0xFFF44336),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    StepStatus.SKIPPED -> Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9800),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    StepStatus.PENDING -> Box(modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = step.name,
                                    fontSize = 13.sp,
                                    color = when (step.status) {
                                        StepStatus.DONE -> Color(0xFF4CAF50)
                                        StepStatus.FAILED -> Color(0xFFF44336)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(
                visible = showSkipButton && !allDone,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TextButton(onClick = onComplete) {
                    Text("跳过，稍后设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AnimatedVisibility(
                visible = allDone,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Button(onClick = onComplete) {
                    Text("进入应用")
                }
            }
        }
    }
}

private suspend fun checkEnvInstalled(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    commands: List<String>
): Boolean {
    for (cmd in commands) {
        try {
            val (code, output) = linuxManager.exec(cmd, timeout = 10_000)
            if (code == 0 && output.isNotBlank()) return true
        } catch (_: Exception) {}
    }
    val binName = commands.first().split(" ").first()
    try {
        val (code, output) = linuxManager.exec("command -v $binName", timeout = 5_000)
        if (code == 0 && output.isNotBlank()) return true
    } catch (_: Exception) {}
    return false
}

private suspend fun installEnv(
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    name: String,
    installHint: String
) {
    if (installHint == "nodesource") {
        linuxManager.exec("apt-get update 2>&1", timeout = 120_000)
        linuxManager.exec("DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates 2>&1 | tail -5", timeout = 120_000)
        linuxManager.exec("curl -fsSL https://deb.nodesource.com/setup_22.x | bash - 2>&1 | tail -20", timeout = 120_000)
        linuxManager.exec("DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs 2>&1 | tail -10", timeout = 300_000)
    } else {
        linuxManager.exec(installHint, timeout = 300_000)
    }
    try { linuxManager.exec("hash -r 2>/dev/null", timeout = 3_000) } catch (_: Exception) {}
}

