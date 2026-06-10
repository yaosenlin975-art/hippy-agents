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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.curator.skillopt.SkillAudit
import com.lin.hippyagent.core.skill.curator.skillopt.SkillAuditRecord
import com.lin.hippyagent.ui.components.HippyTopBar
import com.lin.hippyagent.R
import androidx.compose.ui.res.stringResource
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

    val skillAudit = remember(context) { SkillAudit(context) }
    val scoreMap by produceState<Map<String, SkillAuditRecord>>(
        initialValue = emptyMap(),
        key1 = refreshTrigger,
        key2 = skillAudit
    ) {
        value = skillAudit.loadReport().records.associateBy { it.skillId }
    }

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
                            showInstallResult = context.getString(R.string.skill_pool_install_success, skill.name)
                            refreshTrigger++
                        },
                        onFailure = { e ->
                            showInstallResult = context.getString(R.string.skill_pool_install_failed, e.message)
                        }
                    )

                    tempFile.delete()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to install skill from ZIP")
                    showInstallResult = context.getString(R.string.skill_pool_install_failed, e.message)
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
            title = { Text(stringResource(R.string.skill_pool_add_from_url_title)) },
            text = {
                OutlinedTextField(
                    value = uriInputText,
                    onValueChange = { uriInputText = it },
                    label = { Text(stringResource(R.string.skill_pool_url_label)) },
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
                                            showInstallResult = context.getString(R.string.skill_pool_install_success, skill.name)
                                            refreshTrigger++
                                        },
                                        onFailure = { e ->
                                            showInstallResult = context.getString(R.string.skill_pool_install_failed, e.message)
                                        }
                                    )
                                } catch (e: Exception) {
                                    showInstallResult = context.getString(R.string.skill_pool_install_failed, e.message)
                                }
                                showUriInput = false
                                uriInputText = ""
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUriInput = false }) {
                    Text(stringResource(R.string.cancel))
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
                    text = stringResource(R.string.skill_pool_add_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.skill_pool_from_file)) },
                    supportingContent = { Text(stringResource(R.string.skill_pool_from_file_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAddSheet = false
                        zipPickerLauncher.launch("application/zip")
                    }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.skill_pool_from_url)) },
                    supportingContent = { Text(stringResource(R.string.skill_pool_from_url_desc)) },
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
                title = stringResource(R.string.skill_pool),
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            skillAudit.runAudit(knownSkillIds = skills.map { it.id })
                            refreshTrigger++
                        }
                    }) {
                        Icon(Icons.Default.WarningAmber, "运行评分", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = onNavigateToStore) {
                        Icon(Icons.Default.Store, stringResource(R.string.skill_pool_store), tint = MaterialTheme.colorScheme.onSurface)
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
                Icon(Icons.Default.Add, stringResource(R.string.add), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        if (skills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.skill_pool_no_skills), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.skill_pool_add_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(skills, key = { it.id }) { skill ->
                    val record = scoreMap[skill.id]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selectedSkillDetail = skill }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (record != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "score ${"%.2f".format(record.score)}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (record.status == "WARN") {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.WarningAmber,
                                                contentDescription = "低分警告",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        if (record.archived) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.Archive,
                                                contentDescription = "已归档",
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                text = "已归档",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                                Text(skill.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${skill.version}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!skill.isBuiltin) {
                                IconButton(onClick = { showDeleteDialog = skill }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
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
            title = { Text(stringResource(R.string.skill_pool_install_result)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { showInstallResult = null }) { Text(stringResource(R.string.ok)) } }
        )
    }

    showDeleteDialog?.let { skill ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.skill_pool_delete_title)) },
            text = { Text(stringResource(R.string.skill_pool_delete_confirm, skill.name)) },
            confirmButton = { TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    skillManager.uninstallSkill(skill.id)
                }
                refreshTrigger++; showDeleteDialog = null
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(R.string.cancel)) } }
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

