package com.lin.hippyagent.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.PermissionType
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.skill.SkillManager
import java.io.File
import com.lin.hippyagent.ui.chat.PlanProgressChip
import com.lin.hippyagent.ui.chat.PlanPanel
import com.lin.hippyagent.ui.settings.general.readChatFontSize
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.chat.components.QueueBottomSheet

private enum class ActivePanel { NONE, DRAWER, SEARCH }

val LocalChatFontSize = androidx.compose.runtime.compositionLocalOf { 14 }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    permissionViewModel: PermissionViewModel,
    planViewModel: PlanViewModel,
    inputViewModel: ChatInputViewModel,
    sessionId: String,
    agentId: String,
    onBackClick: () -> Unit,
    onNavigateToAgentConfig: (String) -> Unit = {},
    onNavigateToChat: (String, String) -> Unit = { _, _ -> },
    onNavigateToModelProvider: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val inputState by inputViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var chatFontSize by remember { mutableIntStateOf(readChatFontSize(context)) }
    val showAvatars by remember { mutableStateOf(com.lin.hippyagent.ui.settings.general.readShowAgentAvatar(context)) }
    val prefs = remember(context) {
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "chat_font_size_sp" || key == "app_font_scale") {
                chatFontSize = readChatFontSize(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalChatFontSize provides chatFontSize) {
    var showModelSwitch by remember { mutableStateOf(false) }
    var pendingSettingsNav by remember { mutableStateOf(false) }
    var activePanel by remember { mutableStateOf(ActivePanel.NONE) }
    val planEnabled by planViewModel.planEnabled.collectAsStateWithLifecycle()
    val currentPlan by planViewModel.currentPlan.collectAsStateWithLifecycle()
    var showPlanPanel by remember { mutableStateOf(false) }
    var collapseTrigger by remember { mutableIntStateOf(0) }
    var expandTrigger by remember { mutableIntStateOf(0) }
    var showQueueSheet by remember { mutableStateOf(false) }
    val queueItems by viewModel.messageQueueItems.collectAsStateWithLifecycle()
    var showPrivacyMenu by remember { mutableStateOf(false) }

    // ── STT 语音输入 ──
    val sttService: com.lin.hippyagent.core.voice.STTService = org.koin.compose.koinInject()
    val ttsState = rememberChatTtsState(context)
    var sttListening by remember { mutableStateOf(false) }
    var sttPartialText by remember { mutableStateOf<String?>(null) }
    val ttsSpeaking by ttsState.isSpeaking.collectAsStateWithLifecycle()

    // STT 启动内部逻辑
    val doStartStt: () -> Unit = {
        sttListening = true
        sttService.startListening(object : com.lin.hippyagent.core.voice.SttCallback {
            override fun onPartialResult(result: com.lin.hippyagent.core.voice.SttResult) {
                sttPartialText = result.text
            }
            override fun onFinalResult(result: com.lin.hippyagent.core.voice.SttResult) {
                sttListening = false
                sttPartialText = null
                if (result.text.isNotBlank()) {
                    val current = inputState.inputText
                    inputViewModel.onInputTextChanged(if (current.isNotBlank()) "$current ${result.text}" else result.text)
                }
            }
            override fun onError(error: Throwable) {
                sttListening = false
                sttPartialText = null
                coroutineScope.launch {
                    Toast.makeText(context, context.getString(R.string.chat_stt_failed, error.message), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // 录音权限请求（复用集中式 MicPermissionHandler，避免与 GroupChatScreen 漂移）
    val micPermission = rememberMicPermissionHandler(
        onGranted = { doStartStt() }
    )

    val startStt: () -> Unit = { micPermission.requestMicPermission() }

    val stopStt: () -> Unit = {
        sttListening = false
        sttPartialText = null
        sttService.stopListening()
    }

    // 获取当前智能体的可用技能列表（用于附件栏技能选择器）
    val skillManager = org.koin.compose.koinInject<SkillManager>()
    val agentRepository = org.koin.compose.koinInject<com.lin.hippyagent.data.repository.AgentRepository>()
    var agentSkills by remember(agentId) { mutableStateOf<List<com.lin.hippyagent.core.skill.SkillInfo>>(emptyList()) }
    // 所有智能体配置的缓存（群聊中用于查头像和名字）
    var agentProfiles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var agentAvatarUrls by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var disabledAgentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(agentId) {
        val profiles = try {
            agentRepository.loadAgentProfiles().first()
        } catch (_: Exception) { emptyMap<String, com.lin.hippyagent.core.agent.AgentProfile>() }

        // 缓存 agentId → name 映射
        agentProfiles = profiles.mapValues { (_, v) -> v.name.ifBlank { v.agentId } }
        agentAvatarUrls = profiles.mapValues { (_, v) -> v.avatarUrl }
        disabledAgentIds = profiles.filter { !it.value.enabled }.keys.toSet()

        val profile = profiles[agentId]
        val disabledSkills = profile?.disabledSkills ?: emptyList()
        val skillIds = (profile?.skills ?: emptyList()).filter { it !in disabledSkills }
        agentSkills = skillIds.mapNotNull { skillManager.getSkill(it) }
    }

    // ChatSearchViewModel 提升到顶层，保证单例复用
    val searchViewModel: ChatSearchViewModel = org.koin.androidx.compose.koinViewModel()

    val filePickers = rememberChatFilePickers(
        onImagePicked = { chip ->
            inputViewModel.addChip(chip)
        },
        onFilePicked = { chip ->
            inputViewModel.addChip(chip)
        }
    )

    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val photoUri = cameraPhotoUri
        if (success && photoUri != null) {
            val fileName = run {
                var name: String? = null
                if (photoUri.scheme == "content") {
                    context.contentResolver.query(photoUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = cursor.getString(idx)
                        }
                    }
                }
                name ?: photoUri.path?.substringAfterLast('/') ?: "photo_${System.currentTimeMillis()}.jpg"
            }
            inputViewModel.addChip(InputChip(
                type = InputChipType.IMAGE,
                label = fileName,
                uri = photoUri.toString()
            ))
        }
    }

    // 初始化语音服务
    val sessionState = rememberChatSessionState(viewModel, inputViewModel, ttsState.ttsService)

    val voiceManagerForInit: com.lin.hippyagent.core.voice.VoiceExtensionManager = org.koin.compose.koinInject()
    LaunchedEffect(Unit) {
        voiceManagerForInit.initialize()
        ttsState.initialize()
    }
    DisposableEffect(sessionId) {
        sessionState.setupForeground(sessionId)
        onDispose { sessionState.cleanup() }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            val photoFile = File(
                File(context.filesDir, "photos"),
                "photo_${System.currentTimeMillis()}.jpg"
            ).also { it.parentFile?.mkdirs() }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, context.getString(R.string.chat_camera_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    val takePicture: () -> Unit = {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionViewModel.clearMissingPermissions()
    }

    val permUiState by permissionViewModel.uiState.collectAsStateWithLifecycle()
    val currentMissingPerms = permUiState.missingPermissions
    LaunchedEffect(currentMissingPerms) {
        if (currentMissingPerms.isNotEmpty()) {
            val androidPerms = currentMissingPerms.map { perm ->
                when (perm) {
                    "CAMERA" -> android.Manifest.permission.CAMERA
                    "RECORD_AUDIO" -> android.Manifest.permission.RECORD_AUDIO
                    "ACCESS_FINE_LOCATION" -> android.Manifest.permission.ACCESS_FINE_LOCATION
                    "ACCESS_COARSE_LOCATION" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
                    "READ_CONTACTS" -> android.Manifest.permission.READ_CONTACTS
                    "READ_SMS" -> android.Manifest.permission.READ_SMS
                    "SEND_SMS" -> android.Manifest.permission.SEND_SMS
                    "CALL_PHONE" -> android.Manifest.permission.CALL_PHONE
                    "READ_CALL_LOG" -> android.Manifest.permission.READ_CALL_LOG
                    "READ_CALENDAR" -> android.Manifest.permission.READ_CALENDAR
                    "WRITE_CALENDAR" -> android.Manifest.permission.WRITE_CALENDAR
                    "BLUETOOTH_CONNECT" -> android.Manifest.permission.BLUETOOTH_CONNECT
                    "READ_MEDIA_IMAGES" -> android.Manifest.permission.READ_MEDIA_IMAGES
                    "READ_MEDIA_VIDEO" -> android.Manifest.permission.READ_MEDIA_VIDEO
                    "READ_MEDIA_AUDIO" -> android.Manifest.permission.READ_MEDIA_AUDIO
                    else -> "android.permission.$perm"
                }
            }.toTypedArray()
            androidPermissionLauncher.launch(androidPerms)
        }
    }

    LaunchedEffect(sessionId, agentId) {
        val effectiveSessionId = if (sessionId == "new") "" else sessionId
        sessionState.initialize(effectiveSessionId, agentId)
    }

    val view = LocalView.current
    var imeVisible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            imeVisible = keypadHeight > screenHeight * 0.15
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    val autoScrollState = rememberChatAutoScrollState(
        listState = listState,
        turns = uiState.turns,
        streamingContent = streamingState.streamingContent,
        agentStatus = uiState.agentStatus,
        streamingThinkingContent = streamingState.streamingThinkingContent,
        imeVisible = imeVisible,
        sessionId = uiState.sessionId
    )

    var showFullScreenImage by remember { mutableStateOf<String?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }

    fun exportSelectedMessages() {
        val markdown = viewModel.exportSelectedMessagesAsMarkdown()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.chat_message_export), markdown))
        Toast.makeText(context, context.getString(R.string.chat_messages_copied, uiState.selectedMessageIds.size), Toast.LENGTH_SHORT).show()
        viewModel.exitMultiSelectMode()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable { activePanel = ActivePanel.DRAWER }
                            .padding(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.sessionTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.session_list),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (uiState.agentStatus == AgentStatus.THINKING ||
                                uiState.agentStatus == AgentStatus.EXECUTING_TOOL) {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.cancelGeneration() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.chat_stop_generation),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        val modelDisplayName = uiState.selectedModel.substringAfterLast("/")
                        if (modelDisplayName.isNotEmpty()) {
                            // 与 ChatModeDropdown 视觉区分: 使用 SwapHoriz 图标(模型切换语义) +
                            // 圆角边框,明确"这是模型切换器,不是 mode 选择器"。
                            val modelLabel = stringResource(R.string.agent_model)
                            Row(
                                modifier = Modifier
                                    .clickable { showModelSwitch = true }
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = modelLabel,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "$modelLabel:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = modelDisplayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (inputState.offlineQueueSize > 0 || uiState.messageQueueSize > 0) {
                        Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showQueueSheet = true }
                    ) {
                        Icon(
                            Icons.Default.Inventory,
                            contentDescription = stringResource(R.string.chat_queue),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "${inputState.offlineQueueSize + uiState.messageQueueSize}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    }
                    if (!uiState.selectedModeLocked) {
                        val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
                        ChatModeDropdown(
                            selectedMode = selectedMode,
                            onModeSelected = { mode -> viewModel.selectMode(mode) },
                            autoDecidedModeReasoning = uiState.autoDecidedModeReasoning
                        )
                    }
                    IconButton(onClick = { planViewModel.togglePlanMode() }) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = stringResource(R.string.chat_plan),
                            tint = if (planEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { activePanel = ActivePanel.SEARCH }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { showPrivacyMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.privacy_mode))
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showPrivacyMenu,
                        onDismissRequest = { showPrivacyMenu = false }
                    ) {
                        PrivacyModeToggle(
                            privacyMode = uiState.privacyMode,
                            onToggle = { enabled ->
                                viewModel.togglePrivacyMode(enabled)
                            },
                            onDeviceModelReady = uiState.onDeviceModelReady
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (uiState.isModeDeciding) {
                    com.lin.hippyagent.ui.components.PulsingStatusDot(
                        isThinking = true,
                        label = stringResource(R.string.chat_deciding),
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                } else if (uiState.agentStatus == AgentStatus.THINKING ||
                    uiState.agentStatus == AgentStatus.EXECUTING_TOOL) {
                    com.lin.hippyagent.ui.components.PulsingStatusDot(
                        isThinking = uiState.agentStatus == AgentStatus.THINKING,
                        label = if (uiState.agentStatus == AgentStatus.THINKING) stringResource(R.string.thinking) else stringResource(R.string.chat_executing),
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                if (uiState.iterationExhausted) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.chat_iterations_exhausted),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                val activeMission = uiState.activeMission
                if (activeMission != null) {
                    MissionProgressBar(
                        mission = activeMission,
                        onCancel = { viewModel.cancelMission() }
                    )
                }
                val plan = currentPlan
                if (plan != null && plan.isActive) {
                    PlanProgressChip(
                        plan = plan,
                        onClick = { showPlanPanel = true }
                    )
                }
                val currentApproval by viewModel.currentSessionApproval.collectAsStateWithLifecycle()
                if (currentApproval != null) {
                    InlineApprovalCard(
                        task = currentApproval!!,
                        onApprove = { viewModel.onApprove(currentApproval!!.id) },
                        onDeny = { viewModel.onDeny(currentApproval!!.id) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                ChatInputBar(
                    state = ChatInputUiState(
                        value = inputState.inputText,
                        enabled = true,
                        isAgentThinking = uiState.agentStatus == AgentStatus.THINKING ||
                            uiState.agentStatus == AgentStatus.EXECUTING_TOOL,
                        queueSize = uiState.messageQueueSize,
                        chips = inputState.chips,
                        agentSkills = agentSkills,
                        isSttAvailable = sttService.isAvailableFlow.collectAsStateWithLifecycle().value,
                        isSttListening = sttListening,
                        sttPartialResult = sttPartialText,
                        sttEngineLabel = sttService.engineLabel,
                        quotedMessage = inputState.quotedMessage,
                        isMultiSelectMode = uiState.isMultiSelectMode,
                        selectedCount = uiState.selectedMessageIds.size,
                        isRecordingVoice = viewModel.isRecordingVoice.collectAsStateWithLifecycle().value,
                        recordingDurationMs = viewModel.recordingDurationMs.collectAsStateWithLifecycle().value
                    ),
                    callbacks = object : ChatInputCallbacks {
                        override fun onValueChange(value: String) { inputViewModel.onInputTextChanged(value) }
                        override fun onSend() {
                            if (inputState.inputText.isNotBlank() || inputState.chips.isNotEmpty()) {
                                val (text, chips, quoted) = inputViewModel.consumeInput()
                                if (text.isNotBlank() || chips.isNotEmpty()) {
                                    if (viewModel.checkFreeModelBeforeSend(text, chips)) {
                                        viewModel.sendMessage(text, chips = chips, quotedMessage = quoted)
                                    }
                                }
                            }
                        }
                        override fun onAttachImage() { filePickers.launchImagePicker() }
                        override fun onAttachFile() { filePickers.launchFilePicker() }
                        override fun onTakePicture() { takePicture() }
                        override fun onAddChip(chip: InputChip) { inputViewModel.addChip(chip) }
                        override fun onRemoveChip(chipId: String) { inputViewModel.removeChip(chipId) }
                        override fun onStartStt() { startStt() }
                        override fun onStopStt() { stopStt() }
                        override fun onRemoveQuote() { inputViewModel.clearQuotedMessage() }
                        override fun onForwardSelected() { showForwardDialog = true }
                        override fun onExportSelected() { exportSelectedMessages() }
                        override fun onDeleteSelected() { viewModel.deleteSelectedMessages() }
                        override fun onExitMultiSelect() { viewModel.exitMultiSelectMode() }
                        override fun onStartVoiceRecording() { viewModel.startVoiceRecording() }
                        override fun onStopVoiceRecording() { viewModel.stopVoiceRecording() }
                    }
                )
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            CompositionLocalProvider(
                LocalCollapseAll provides collapseTrigger,
                LocalExpandVisible provides expandTrigger
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ChatTurnList(
                    turns = uiState.turns,
                    listState = listState,
                    streamingState = streamingState,
                    agentStatus = uiState.agentStatus,
                    agentName = uiState.agentName,
                    agentProfiles = agentProfiles,
                    disabledAgentIds = disabledAgentIds,
                    agentAvatarUrls = agentAvatarUrls,
                    isGroupChat = false,
                    showAvatars = showAvatars,
                    currentAgentId = agentId,
                    viewModel = viewModel,
                    permissionViewModel = permissionViewModel,
                    ttsService = ttsState.ttsService,
                    onImageClick = { path -> showFullScreenImage = path },
                    onQuote = { messageId, content, senderName ->
                        inputViewModel.setQuotedMessage(messageId, content, senderName)
                    },
                    onLongClickAgentAvatar = { name ->
                        inputViewModel.appendAttachmentText("@$name ")
                    },
                    isMultiSelectMode = uiState.isMultiSelectMode,
                    selectedMessageIds = uiState.selectedMessageIds,
                    onToggleSelection = { viewModel.toggleMessageSelection(it) },
                    onEnterMultiSelect = { viewModel.enterMultiSelectMode(it) },
                    modifier = Modifier.fillMaxSize()
                )

                ChatOverlays(
                    turns = uiState.turns,
                    isAtBottom = autoScrollState.isAtBottom,
                    scrollToBottom = autoScrollState.scrollToBottom,
                    ttsSpeaking = ttsSpeaking,
                    onStopTts = { ttsState.stop() },
                    showFreeModelWarning = uiState.showFreeModelWarning,
                    onDismissFreeModelWarning = { viewModel.dismissFreeModelWarning() },
                    onSuppressFreeModelWarning = { viewModel.suppressFreeModelWarning() }
                )
            }
            }
        }
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            queueItems = queueItems,
            onRemove = { index -> viewModel.removeQueuedMessage(index) },
            onMove = { from, to -> viewModel.moveQueuedMessage(from, to) },
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showModelSwitch) {
        ModelSwitchSheet(
            selectedModel = uiState.selectedModel,
            selectedProviderId = uiState.selectedProviderId,
            availableModels = uiState.availableModels,
            onModelSelected = { modelName, providerId -> viewModel.selectModel(modelName, providerId) },
            onDismiss = { showModelSwitch = false },
            onNavigateToSettings = {
                showModelSwitch = false
                pendingSettingsNav = true
            }
        )
    }

    LaunchedEffect(showModelSwitch, pendingSettingsNav) {
        if (!showModelSwitch && pendingSettingsNav) {
            pendingSettingsNav = false
            onNavigateToModelProvider()
        }
    }

    if (activePanel == ActivePanel.DRAWER) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .clickable { activePanel = ActivePanel.NONE }
        ) {
            ChatSessionDrawer(
                sessions = uiState.allSessions,
                currentSessionId = uiState.sessionId,
                sessionBadges = uiState.sessionBadges,
                sessionUnreadCounts = uiState.sessionUnreadCounts,
                sessionStatuses = uiState.sessionStatuses,
                onSessionClick = { session ->
                    activePanel = ActivePanel.NONE
                    onNavigateToChat(session.id, session.agentId)
                },
                onNewSession = {
                    activePanel = ActivePanel.NONE
                    onNavigateToChat("new", agentId)
                },
                onDismiss = { activePanel = ActivePanel.NONE },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    if (activePanel == ActivePanel.SEARCH) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .clickable { activePanel = ActivePanel.NONE }
        ) {
            ChatSearchPanel(
                viewModel = searchViewModel,
                sessions = uiState.allSessions,
                onResultClick = { sId, aId ->
                    activePanel = ActivePanel.NONE
                    onNavigateToChat(sId, aId)
                },
                onDismiss = { activePanel = ActivePanel.NONE },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    // 图片全屏预览
    val fullScreenImage = showFullScreenImage
    if (fullScreenImage != null) {
        val imageModel: Any = if (fullScreenImage.startsWith("content://")) {
            android.net.Uri.parse(fullScreenImage)
        } else {
            File(fullScreenImage)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFullScreenImage = null }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showFullScreenImage = null },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = imageModel,
                    contentDescription = stringResource(R.string.chat_fullscreen_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { showFullScreenImage = null },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
    }

    // Shell 命令授权弹窗（PermissionManager PERMISSION_NEEDED）
    val pendingCommand = permUiState.pendingPermissionCommand
    if (pendingCommand != null) {
        PermissionRequestDialog(
            command = pendingCommand,
            onApproveOnce = { permissionViewModel.approvePermissionOnce(uiState.sessionId, uiState.agentId) },
            onApproveAlways = { permissionViewModel.approvePermissionAlways(uiState.sessionId, uiState.agentId) },
            onDenyOnce = { permissionViewModel.denyPermissionOnce(uiState.sessionId, uiState.agentId) },
            onDenyAlways = { permissionViewModel.denyPermissionAlways(uiState.sessionId, uiState.agentId) }
        )
    }

    val accessApproval = permissionViewModel.pendingApprovalRequest.collectAsStateWithLifecycle().value
    if (accessApproval != null) {
        val approval = accessApproval
        AlertDialog(
            onDismissRequest = { permissionViewModel.respondToApproval(false) },
            title = { Text(stringResource(R.string.chat_auth_request), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.chat_agent_accessibility_request), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.chat_action_label, approval.action), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    approval.target?.let { Text(stringResource(R.string.chat_target_label, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(stringResource(R.string.chat_risk_level_label, when (approval.riskLevel) {
                        RiskLevel.LOW -> stringResource(R.string.risk_low)
                        RiskLevel.MEDIUM -> stringResource(R.string.risk_medium)
                        RiskLevel.HIGH -> stringResource(R.string.risk_high)
                        else -> stringResource(R.string.risk_blocked)
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(onClick = { permissionViewModel.respondToApproval(true, com.lin.hippyagent.core.accessibility.ApprovalDuration.ONCE) }) {
                    Text(stringResource(R.string.chat_allow_this_time))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { permissionViewModel.respondToApproval(true, com.lin.hippyagent.core.accessibility.ApprovalDuration.SESSION) }) {
                        Text(stringResource(R.string.chat_allow_always))
                    }
                    TextButton(onClick = { permissionViewModel.respondToApproval(false) }) {
                        Text(stringResource(R.string.chat_deny))
                    }
                }
            }
        )
    }

    // 其他 session / 无 session 的 task / tool_approval 等待审批 → 弹 Dialog
    val otherApproval by viewModel.otherSessionApproval.collectAsStateWithLifecycle()
    if (otherApproval != null) {
        OtherSessionApprovalDialog(
            task = otherApproval!!,
            onApprove = { viewModel.onApprove(otherApproval!!.id) },
            onDeny = { viewModel.onDeny(otherApproval!!.id) },
            onDismiss = { viewModel.onDeny(otherApproval!!.id) }
        )
    }

    if (showPlanPanel && currentPlan != null) {
        PlanPanel(
            plan = currentPlan,
            onDismiss = { showPlanPanel = false }
        )
    }

    if (showForwardDialog) {
        AlertDialog(
            onDismissRequest = { showForwardDialog = false },
            title = { Text(stringResource(R.string.chat_forward_to)) },
            text = {
                LazyColumn {
                    items(agentProfiles.entries.toList(), key = { it.key }) { (agentId, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.forwardSelectedMessages(agentId)
                                    showForwardDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showForwardDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    MicPermissionRationaleDialog(
        show = micPermission.showRationaleDialog,
        onDismiss = micPermission.dismissRationaleDialog,
    )
    }
}

@Composable
internal fun MessageContentWithAttachments(
    content: String,
    isUser: Boolean,
    onImageClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    agentProfiles: Map<String, String> = emptyMap(),
    metadataJson: String? = null
) {
    val context = LocalContext.current
    val voiceMeta = parseVoiceMetadata(metadataJson)
    if (voiceMeta != null) {
        VoiceBubble(
            filePath = voiceMeta.filePath,
            durationMs = voiceMeta.durationMs,
            isUser = isUser,
            modifier = modifier
        )
        return
    }

    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface
    val chatFontSize = LocalChatFontSize.current
    val markdownStyle = MaterialTheme.typography.bodyLarge.copy(
        color = textColor,
        fontSize = chatFontSize.sp,
        lineHeight = (chatFontSize + 6).sp
    )

    data class TagMatch(val range: IntRange, val type: String, val value: String)

    val tagMatches = remember(content) {
        val matches = mutableListOf<TagMatch>()
        ATTACHMENT_CONTENT_REGEX.findAll(content).forEach {
            matches.add(TagMatch(it.range, "attachment", it.groupValues[1].trim()))
        }
        SKILL_TAG_REGEX.findAll(content).forEach {
            matches.add(TagMatch(it.range, "skill", it.groupValues[1]))
        }
        MENTION_REGEX.findAll(content).forEach {
            matches.add(TagMatch(it.range, "mention", it.groupValues[1]))
        }
        matches.sortedBy { it.range.first }
    }

    if (tagMatches.isEmpty()) {
        MarkdownText(
            markdown = sanitizeMarkdown(content, context),
            style = markdownStyle,
            modifier = modifier
        )
        return
    }

    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    val hasInlineTags = tagMatches.any { it.type == "mention" || it.type == "skill" }

    if (!hasInlineTags) {
        Column(modifier = modifier) {
            var lastEnd = 0
            for (tag in tagMatches) {
                val textBefore = content.substring(lastEnd, tag.range.first).trim()
                if (textBefore.isNotEmpty()) {
                    MarkdownText(markdown = sanitizeMarkdown(textBefore, context), style = markdownStyle)
                }
                val ext = tag.value.substringAfterLast(".", "").lowercase()
                if (ext in imageExtensions) {
                    coil.compose.AsyncImage(
                        model = File(tag.value),
                        contentDescription = stringResource(R.string.chat_image_attachment),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(
                                if (onImageClick != null) Modifier.clickable { onImageClick(tag.value) }
                                else Modifier
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                    )
                } else {
                    FileAttachmentCard(filePath = tag.value, isUser = isUser)
                }
                lastEnd = tag.range.last + 1
            }
            val textAfter = content.substring(lastEnd).trim()
            if (textAfter.isNotEmpty()) {
                MarkdownText(markdown = sanitizeMarkdown(textAfter, context), style = markdownStyle)
            }
        }
        return
    }

    val attachmentTags = tagMatches.filter { it.type == "attachment" }
    val inlineTags = tagMatches.filter { it.type == "mention" || it.type == "skill" }

    Column(modifier = modifier) {
        val annotatedText = buildAnnotatedString {
            var lastEnd = 0
            for (tag in inlineTags) {
                val textBefore = content.substring(lastEnd, tag.range.first)
                if (textBefore.isNotEmpty()) {
                    append(textBefore)
                }
                when (tag.type) {
                    "mention" -> {
                        val matchedByDisplayName = agentProfiles.entries.firstOrNull { it.value == tag.value }?.key
                        val matchedAgentId = matchedByDisplayName ?: agentProfiles.keys.firstOrNull { it == tag.value }
                        if (matchedAgentId != null) {
                            val displayName = agentProfiles[matchedAgentId] ?: tag.value
                            val hue = (matchedAgentId.hashCode().and(0x7FFFFFFF) % 360).toFloat()
                            val mentionBgColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.95f)))
                            val mentionTextColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.45f)))
                            withStyle(SpanStyle(
                                color = mentionTextColor,
                                background = mentionBgColor,
                                fontWeight = FontWeight.Bold
                            )) {
                                append("@$displayName")
                            }
                        } else {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("@${tag.value}")
                            }
                        }
                    }
                    "skill" -> {
                        val skillBgColor = if (isUser) Color(0xFFE1BEE7) else Color(0xFFF3E5F5)
                        val skillTextColor = if (isUser) Color(0xFF6A1B9A) else Color(0xFF7B1FA2)
                        withStyle(SpanStyle(
                            color = skillTextColor,
                            background = skillBgColor,
                            fontWeight = FontWeight.Medium
                        )) {
                            append("/${tag.value}")
                        }
                    }
                }
                lastEnd = tag.range.last + 1
            }
            val textAfter = content.substring(lastEnd)
            if (textAfter.isNotEmpty()) {
                append(textAfter)
            }
        }
        Text(
            text = annotatedText,
            style = markdownStyle,
            modifier = Modifier
        )
        for (tag in attachmentTags) {
            val ext = tag.value.substringAfterLast(".", "").lowercase()
            if (ext in imageExtensions) {
                coil.compose.AsyncImage(
                    model = File(tag.value),
                    contentDescription = stringResource(R.string.chat_image_attachment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .then(
                            if (onImageClick != null) Modifier.clickable { onImageClick(tag.value) }
                            else Modifier
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            } else {
                FileAttachmentCard(filePath = tag.value, isUser = isUser)
            }
        }
    }
}

/**
 * 1. 将图片语法 ![alt](url) 转为链接文本 [alt](url)，避免渲染为 "??"。
 * 2. 去除标题行首无法渲染的 emoji，避免显示为 "?"。
 */
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
private val MARKDOWN_EMOJI_HEADING_REGEX = Regex("^(#{1,6}\\s*)[\\u2600-\\u27BF\\uFE00-\\uFE0F\\u1F000-\\u1FFFF\\uD83C-\\uD83E][\\uFE0F\\u200D\\u2600-\\u27BF\\u1F000-\\u1FFFF\\uD83C-\\uD83E]*\\s*")
private val TRIPLE_BACKTICK_REGEX = Regex("```")
internal val ATTACHMENT_CONTENT_REGEX = Regex("\\[附件:\\s*(.+?)\\]")
private val SKILL_TAG_REGEX = Regex("(?<=^|\\s)/([^\\s/]+)")
private val MENTION_REGEX = Regex("@([\\w\\u4e00-\\u9fff-]+)")

private data class VoiceMetadata(val filePath: String, val durationMs: Long)

private fun parseVoiceMetadata(metadataJson: String?): VoiceMetadata? {
    if (metadataJson == null) return null
    return runCatching {
        val json = org.json.JSONObject(metadataJson)
        val filePath = json.optString("voiceFile") ?: return null
        if (filePath.isEmpty()) return null
        val duration = json.optLong("voiceDuration", 0L)
        VoiceMetadata(filePath, duration)
    }.getOrNull()
}

private fun sanitizeMarkdown(content: String, context: Context): String {
    var result = MARKDOWN_IMAGE_REGEX.replace(content) { matchResult ->
        val alt = matchResult.groupValues[1].ifBlank { context.getString(R.string.chat_image) }
        val url = matchResult.groupValues[2]
        "[$alt]($url)"
    }
    result = MARKDOWN_EMOJI_HEADING_REGEX.replace(result) {
        it.groupValues[1]
    }
    val codeBlockCount = result.count { it == '`' }
    if (codeBlockCount % 2 != 0) {
        result = result + "`"
    }
    val tripleMatches = TRIPLE_BACKTICK_REGEX.findAll(result).toList()
    if (tripleMatches.size % 2 != 0) {
        result = result + "\n```"
    }
    return result
}

