package com.lin.hippyagent.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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

    // 录音权限请求
    var showAudioPermissionDialog by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            com.lin.hippyagent.core.voice.AndroidBuiltinTranscriber.notifyAppOps(context)
            doStartStt()
        } else {
            val activity = context as? Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.RECORD_AUDIO)) {
                showAudioPermissionDialog = true
            } else {
                Toast.makeText(context, context.getString(R.string.chat_mic_permission_needed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val startStt: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            doStartStt()
        } else {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val stopStt: () -> Unit = {
        sttListening = false
        sttPartialText = null
        sttService.stopListening()
    }

    // 获取当前智能体的可用技能列表（用于附件栏技能选择器）
    val skillManager = org.koin.compose.koinInject<SkillManager>()
    var agentSkills by remember(agentId) { mutableStateOf<List<com.lin.hippyagent.core.skill.SkillInfo>>(emptyList()) }
    // 所有智能体配置的缓存（群聊中用于查头像和名字）
    var agentProfiles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var agentAvatarUrls by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var disabledAgentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(agentId) {
        val agentRepository = try {
            org.koin.java.KoinJavaComponent.getKoin().get<com.lin.hippyagent.data.repository.AgentRepository>()
        } catch (_: Exception) { null }

        val profiles = if (agentRepository != null) {
            try {
                agentRepository.loadAgentProfiles().first()
            } catch (_: Exception) { emptyMap<String, com.lin.hippyagent.core.agent.AgentProfile>() }
        } else emptyMap<String, com.lin.hippyagent.core.agent.AgentProfile>()

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
                                text = uiState.sessionTitle.take(5).ifEmpty { uiState.sessionTitle },
                                fontSize = 16.sp,
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
                            Row(
                                modifier = Modifier
                                    .clickable { showModelSwitch = true }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = modelDisplayName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    }
                    IconButton(onClick = { planViewModel.togglePlanMode() }) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = "Plan",
                            tint = if (planEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { activePanel = ActivePanel.SEARCH }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (uiState.agentStatus == AgentStatus.THINKING ||
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
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (uiState.activeMission != null) {
                    MissionProgressBar(
                        mission = uiState.activeMission!!,
                        onCancel = { viewModel.cancelMission() }
                    )
                }
                if (currentPlan != null && currentPlan!!.isActive) {
                    PlanProgressChip(
                        plan = currentPlan!!,
                        onClick = { showPlanPanel = true }
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
    if (showFullScreenImage != null) {
        val imageModel: Any = if (showFullScreenImage!!.startsWith("content://")) {
            android.net.Uri.parse(showFullScreenImage!!)
        } else {
            File(showFullScreenImage!!)
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
    if (permUiState.pendingPermissionCommand != null) {
        PermissionRequestDialog(
            command = permUiState.pendingPermissionCommand!!,
            onApproveOnce = { permissionViewModel.approvePermissionOnce(uiState.sessionId, uiState.agentId) },
            onApproveAlways = { permissionViewModel.approvePermissionAlways(uiState.sessionId, uiState.agentId) },
            onDenyOnce = { permissionViewModel.denyPermissionOnce(uiState.sessionId, uiState.agentId) },
            onDenyAlways = { permissionViewModel.denyPermissionAlways(uiState.sessionId, uiState.agentId) }
        )
    }

    val accessApproval by permissionViewModel.pendingApprovalRequest.collectAsStateWithLifecycle()
    if (accessApproval != null) {
        val approval = accessApproval!!
        AlertDialog(
            onDismissRequest = { permissionViewModel.respondToApproval(false) },
            title = { Text(stringResource(R.string.chat_auth_request), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.chat_agent_accessibility_request), fontSize = 14.sp)
                    Text(stringResource(R.string.chat_action_label, approval.action), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    approval.target?.let { Text(stringResource(R.string.chat_target_label, it), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(stringResource(R.string.chat_risk_level_label, when (approval.riskLevel) {
                        com.lin.hippyagent.core.accessibility.RiskLevel.LOW -> stringResource(R.string.risk_low)
                        com.lin.hippyagent.core.accessibility.RiskLevel.MEDIUM -> stringResource(R.string.risk_medium)
                        com.lin.hippyagent.core.accessibility.RiskLevel.HIGH -> stringResource(R.string.risk_high)
                        else -> stringResource(R.string.risk_blocked)
                    }), fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
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
                    items(agentProfiles.entries.toList()) { (agentId, name) ->
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

    if (showAudioPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAudioPermissionDialog = false },
            title = { Text(stringResource(R.string.chat_mic_permission_title)) },
            text = { Text(stringResource(R.string.chat_mic_permission_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    showAudioPermissionDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.chat_go_to_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showAudioPermissionDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
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
    val markdownStyle = TextStyle(
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
            markdown = sanitizeMarkdown(content),
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
                    MarkdownText(markdown = sanitizeMarkdown(textBefore), style = markdownStyle)
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
                MarkdownText(markdown = sanitizeMarkdown(textAfter), style = markdownStyle)
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

@Composable
private fun SkillTagChip(
    name: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isUser) Color(0xFFE1BEE7) else Color(0xFFF3E5F5)
    val contentColor = if (isUser) Color(0xFF6A1B9A) else Color(0xFF7B1FA2)
    Surface(
        modifier = modifier.padding(vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 12.sp,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MentionTagChip(
    name: String,
    isUser: Boolean,
    agentId: String? = null,
    modifier: Modifier = Modifier
) {
    // 群聊中根据 agentId 生成专属颜色，否则使用默认黄色
    val agentColor = if (agentId != null) {
        val hue = (agentId.hashCode().and(0x7FFFFFFF) % 360).toFloat()
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.95f)))
    } else Color(0xFFFFF8E1)
    val agentTextColor = if (agentId != null) {
        val hue = (agentId.hashCode().and(0x7FFFFFFF) % 360).toFloat()
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.45f)))
    } else Color(0xFFF57F17)
    val containerColor = if (isUser && agentId == null) Color(0xFFFFF8E1) else agentColor
    val contentColor = if (isUser && agentId == null) Color(0xFFF57F17) else agentTextColor
    Surface(
        modifier = modifier.padding(vertical = 1.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "@",
                fontSize = 11.sp,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                text = name,
                fontSize = 12.sp,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun FileAttachmentCard(
    filePath: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fileName = filePath.substringAfterLast("/", filePath.substringAfterLast("\\"))
    val ext = filePath.substringAfterLast(".", "").lowercase()
    val fileSize = remember(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            val size = file.length()
            when {
                size >= 1_073_741_824 -> "%.1f GB".format(size / 1_073_741_824.0)
                size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
                size >= 1024 -> "%.1f KB".format(size / 1024.0)
                else -> "$size B"
            }
        } else null
    }

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isUser)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = when (ext) {
                    "doc", "docx", "txt", "md" -> Icons.Outlined.Article
                    else -> Icons.Outlined.AttachFile
                },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ext.isNotEmpty()) {
                        Text(
                            text = ext.uppercase(),
                            fontSize = 11.sp,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (fileSize != null) {
                        if (ext.isNotEmpty()) Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = fileSize,
                            fontSize = 11.sp,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_cannot_open, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Article,
                    contentDescription = stringResource(R.string.chat_view),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        val dest = File(downloadsDir, fileName)
                        file.copyTo(dest, overwrite = true)
                        Toast.makeText(context, context.getString(R.string.chat_saved_to_downloads, fileName), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_save_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = stringResource(R.string.chat_save),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) {
                            Toast.makeText(context, context.getString(R.string.error_file_not_found), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext) ?: "*/*"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.chat_share_file)))
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.chat_share_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.chat_share),
                    modifier = Modifier.size(18.dp),
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

internal fun getFileName(context: Context, uri: Uri): String {
    var fileName = "unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            fileName = cursor.getString(nameIndex)
        }
    }
    return fileName
}

@Composable
internal fun SystemTurnCard(
    turn: ChatTurn.SystemTurn,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (turn.type) {
        com.lin.hippyagent.core.chat.SystemTurnType.INFO -> MaterialTheme.colorScheme.tertiaryContainer
        com.lin.hippyagent.core.chat.SystemTurnType.SUCCESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        com.lin.hippyagent.core.chat.SystemTurnType.WARNING -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }
    val textColor = when (turn.type) {
        com.lin.hippyagent.core.chat.SystemTurnType.INFO -> MaterialTheme.colorScheme.onTertiaryContainer
        com.lin.hippyagent.core.chat.SystemTurnType.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        com.lin.hippyagent.core.chat.SystemTurnType.WARNING -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = turn.content,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
internal fun PermissionTurnCard(
    turn: ChatTurn.PermissionTurn,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severityColor = when (turn.permissionType) {
        PermissionType.SHELL_COMMAND -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        PermissionType.CUSTOM_TOOL -> MaterialTheme.colorScheme.tertiaryContainer
        PermissionType.ACCESSIBILITY -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        PermissionType.ANDROID_RUNTIME -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainerColor = when (turn.permissionType) {
        PermissionType.SHELL_COMMAND -> MaterialTheme.colorScheme.onErrorContainer
        PermissionType.CUSTOM_TOOL -> MaterialTheme.colorScheme.onTertiaryContainer
        PermissionType.ACCESSIBILITY -> MaterialTheme.colorScheme.onErrorContainer
        PermissionType.ANDROID_RUNTIME -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = severityColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = onContainerColor
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = turn.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainerColor
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = turn.description,
                fontSize = 12.sp,
                color = onContainerColor.copy(alpha = 0.85f)
            )
            if (!turn.pendingCommand.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = turn.pendingCommand,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            if (!turn.isResolved) {
                if (turn.findings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (finding in turn.findings.take(3)) {
                            Text(
                                text = "• [${finding.severity.value}] ${finding.title}",
                                fontSize = 10.sp,
                                color = onContainerColor.copy(alpha = 0.7f)
                            )
                        }
                        if (turn.findings.size > 3) {
                            Text(
                                text = stringResource(R.string.chat_more_items, turn.findings.size - 3),
                                fontSize = 10.sp,
                                color = onContainerColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = onDenyOnce,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) { Text(stringResource(R.string.chat_deny), fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = onDenyAlways,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.chat_deny_always), fontSize = 11.sp) }
                    Button(
                        onClick = onApproveOnce,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) { Text(stringResource(R.string.chat_approve_once), fontSize = 11.sp) }
                    Button(
                        onClick = onApproveAlways,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(stringResource(R.string.chat_approve_always), fontSize = 11.sp) }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = onContainerColor.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_processed),
                        fontSize = 11.sp,
                        color = onContainerColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClarificationTurnCard(
    turn: ChatTurn.ClarificationTurn,
    onSelectOption: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typeColor = when (turn.clarificationType) {
        "missing_info" -> MaterialTheme.colorScheme.tertiaryContainer
        "approach_choice" -> MaterialTheme.colorScheme.secondaryContainer
        "risk_confirmation" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        "scope_ambiguity" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val typeIcon = when (turn.clarificationType) {
        "missing_info" -> Icons.Default.HelpOutline
        "approach_choice" -> Icons.Default.SwapHoriz
        "risk_confirmation" -> Icons.Default.Warning
        "scope_ambiguity" -> Icons.Default.Search
        else -> Icons.Default.ChatBubble
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = typeColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    typeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = turn.question,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (turn.context.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = turn.context,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (turn.options.isNotEmpty() && !turn.isResolved) {
                Spacer(Modifier.height(8.dp))
                turn.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onSelectOption(option) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(option, fontSize = 12.sp)
                    }
                }
            }
            if (!turn.isResolved && turn.options.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSkip,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.common_skip), fontSize = 12.sp) }
                }
            }
            if (turn.isResolved) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (turn.selectedOption != null) stringResource(R.string.chat_option_selected, turn.selectedOption) else stringResource(R.string.chat_skipped),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

private fun sanitizeMarkdown(content: String): String {
    var result = MARKDOWN_IMAGE_REGEX.replace(content) { matchResult ->
        val alt = matchResult.groupValues[1].ifBlank { "图片" }
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
