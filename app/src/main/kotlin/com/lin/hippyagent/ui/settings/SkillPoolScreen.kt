package com.lin.hippyagent.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPoolScreen(
    skillManager: SkillManager,
    onBackClick: () -> Unit,
    onNavigateToStore: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val skills = remember(refreshTrigger) { skillManager.listSkills() }
    var showDeleteDialog by remember { mutableStateOf<SkillInfo?>(null) }
    var showInstallResult by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var selectedSkillDetail by remember { mutableStateOf<SkillInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ZIP 文件选择器
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val tempFile = File(context.cacheDir, "skill_install.zip")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val result = skillManager.installSkillFromZip(tempFile.absolutePath)
                    result.fold(
                        onSuccess = { skill ->
                            showInstallResult = "技能 ${skill.name} 安装成功"
                            refreshTrigger++
                        },
                        onFailure = { e ->
                            showInstallResult = "安装失败: ${e.message}"
                        }
                    )

                    tempFile.delete()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install skill from ZIP")
                    showInstallResult = "安装失败: ${e.message}"
                }
            }
        }
    }

    // URI 添加（从 URL 下载）
    var showUriInput by remember { mutableStateOf(false) }
    var uriInputText by remember { mutableStateOf("") }
    if (showUriInput) {
        AlertDialog(
            onDismissRequest = { showUriInput = false },
            title = { Text("从 URL 添加技能") },
            text = {
                OutlinedTextField(
                    value = uriInputText,
                    onValueChange = { uriInputText = it },
                    label = { Text("技能 URL") },
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (uriInputText.isNotBlank()) {
                            scope.launch {
                                try {
                                    val result = skillManager.installSkillFromUrl(uriInputText.trim())
                                    result.fold(
                                        onSuccess = { skill ->
                                            showInstallResult = "技能 ${skill.name} 安装成功"
                                            refreshTrigger++
                                        },
                                        onFailure = { e ->
                                            showInstallResult = "安装失败: ${e.message}"
                                        }
                                    )
                                } catch (e: Exception) {
                                    showInstallResult = "安装失败: ${e.message}"
                                }
                                showUriInput = false
                                uriInputText = ""
                            }
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUriInput = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 添加方式 BottomSheet
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "添加技能",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ListItem(
                    headlineContent = { Text("从文件安装") },
                    supportingContent = { Text("选择本地 ZIP 文件") },
                    leadingContent = {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        zipPickerLauncher.launch("application/zip")
                    }
                )

                ListItem(
                    headlineContent = { Text("从 URL 添加") },
                    supportingContent = { Text("输入技能包下载地址") },
                    leadingContent = {
                        Icon(Icons.Default.Link, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        showUriInput = true
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "技能池",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = onNavigateToStore) {
                        Icon(Icons.Default.Store, "商店", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "添加", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        if (skills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无技能", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("点击右上角 + 添加技能", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(skills, key = { it.id }) { skill ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selectedSkillDetail = skill }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(skill.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${skill.version}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!skill.isBuiltin) {
                                IconButton(onClick = { showDeleteDialog = skill }) {
                                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 安装结果提示
    showInstallResult?.let { message ->
        AlertDialog(
            onDismissRequest = { showInstallResult = null },
            title = { Text("安装结果") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { showInstallResult = null }) { Text("确定") } }
        )
    }

    showDeleteDialog?.let { skill ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除技能") },
            text = { Text("确定要删除 ${skill.name} 吗？") },
            confirmButton = { TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    skillManager.uninstallSkill(skill.id)
                }
                refreshTrigger++; showDeleteDialog = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("取消") } }
        )
    }

    selectedSkillDetail?.let { skill ->
        com.lin.hippyagent.ui.agent.SkillDetailDialog(
            skill = skill,
            skillManager = skillManager,
            onDismiss = { selectedSkillDetail = null }
        )
    }
}

