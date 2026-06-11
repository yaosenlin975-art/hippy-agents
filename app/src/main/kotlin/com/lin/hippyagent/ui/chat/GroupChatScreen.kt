package com.lin.hippyagent.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.collaboration.AgentStatusManager
import com.lin.hippyagent.core.agent.collaboration.AgentWorkState
import com.lin.hippyagent.core.voice.STTService
import com.lin.hippyagent.core.voice.SttCallback
import com.lin.hippyagent.core.voice.SttResult
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    viewModel: ChatViewModel,
    permissionViewModel: PermissionViewModel,
    inputViewModel: ChatInputViewModel,
    sessionId: String,
    agentId: String,
    onBackClick: () -> Unit,
    onNavigateToAgentConfig: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val inputState by inputViewModel.uiState.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = LocalContext.current
    val showAvatars by remember { mutableStateOf(com.lin.hippyagent.ui.settings.general.readShowAgentAvatar(context)) }

    val sttService: STTService = org.koin.compose.koinInject()
    var sttListening by remember { mutableStateOf(false) }
    var sttPartialText by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val doStartStt: () -> Unit = {
        sttListening = true
        sttService.startListening(object : SttCallback {
            override fun onPartialResult(result: SttResult) {
                sttPartialText = result.text
            }
            override fun onFinalResult(result: SttResult) {
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

    // 录音权限请求（复用集中式 MicPermissionHandler）
    val micPermission = rememberMicPermissionHandler(
        onGranted = { doStartStt() }
    )

    val startStt: () -> Unit = { micPermission.requestMicPermission() }

    val stopStt: () -> Unit = {
        sttListening = false
        sttPartialText = null
        sttService.stopListening()
    }

    val ttsState = rememberChatTtsState(context)
    val ttsSpeaking by ttsState.isSpeaking.collectAsStateWithLifecycle()

    val sessionState = rememberChatSessionState(viewModel, inputViewModel, ttsState.ttsService)

    val filePickers = rememberChatFilePickers(
        onImagePicked = { chip ->
            inputViewModel.addChip(chip)
        },
        onFilePicked = { chip ->
            inputViewModel.addChip(chip)
        }
    )

    LaunchedEffect(Unit) { ttsState.initialize() }
    DisposableEffect(sessionId) {
        sessionState.setupForeground(sessionId)
        onDispose { sessionState.cleanup() }
    }

    val agentRepository = remember {
        try { org.koin.java.KoinJavaComponent.getKoin().get<com.lin.hippyagent.data.repository.AgentRepository>() } catch (_: Exception) { null }
    }
    LaunchedEffect(agentRepository) {
        agentRepository?.loadAgentProfiles()?.first()
    }
    val agentProfiles by remember(agentRepository) {
        agentRepository?.getProfiles() ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val agentProfileNames = remember(agentProfiles) {
        agentProfiles.mapValues { (_, v) -> v.name.ifBlank { v.agentId } }
    }
    val agentAvatarUrls = remember(agentProfiles) {
        agentProfiles.mapValues { (_, v) -> v.avatarUrl }
    }

    val groupMembers by viewModel.observeGroupMembers(sessionId).collectAsStateWithLifecycle(initialValue = emptyMap())

    val agentStatusManager = org.koin.java.KoinJavaComponent.getKoin().get<AgentStatusManager>()
    val allStatuses by agentStatusManager.allStatuses.collectAsStateWithLifecycle()

    val workingAgentIds by remember(allStatuses) {
        derivedStateOf { allStatuses.filterValues { it.state == AgentWorkState.WORKING }.keys }
    }
    val groupMemberIds by remember {
        derivedStateOf { groupMembers.keys.toList() }
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
        imeVisible = imeVisible
    )

    val workingCount = remember(allStatuses) {
        allStatuses.values.count { it.state == AgentWorkState.WORKING }
    }

    androidx.compose.material3.Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (workingCount > 0) {
                            Text(
                                text = stringResource(R.string.chat_agents_working, workingCount),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    AgentListBadge(
                        workingCount = workingCount,
                        onClick = { onNavigateToAgentConfig(agentId) },
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                state = ChatInputUiState(
                    value = inputState.inputText,
                    enabled = true,
                    isAgentThinking = uiState.agentStatus == AgentStatus.THINKING ||
                        uiState.agentStatus == AgentStatus.EXECUTING_TOOL,
                    queueSize = uiState.messageQueueSize,
                    chips = inputState.chips,
                    isGroupChat = true,
                    groupMembers = groupMembers,
                    quotedMessage = inputState.quotedMessage,
                    isRecordingVoice = viewModel.isRecordingVoice.collectAsStateWithLifecycle().value,
                    recordingDurationMs = viewModel.recordingDurationMs.collectAsStateWithLifecycle().value,
                    isSttAvailable = sttService.isAvailableFlow.collectAsStateWithLifecycle().value,
                    isSttListening = sttListening,
                    sttPartialResult = sttPartialText,
                    sttEngineLabel = sttService.engineLabel
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
                    override fun onTakePicture() {}
                    override fun onAddChip(chip: InputChip) { inputViewModel.addChip(chip) }
                    override fun onRemoveChip(chipId: String) { inputViewModel.removeChip(chipId) }
                    override fun onStartStt() { startStt() }
                    override fun onStopStt() { stopStt() }
                    override fun onRemoveQuote() { inputViewModel.clearQuotedMessage() }
                    override fun onStartVoiceRecording() { viewModel.startVoiceRecording() }
                    override fun onStopVoiceRecording() { viewModel.stopVoiceRecording() }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                ChatTurnList(
                    turns = uiState.turns,
                    listState = listState,
                    streamingState = streamingState,
                    agentStatus = uiState.agentStatus,
                    agentName = uiState.agentName,
                    agentProfiles = agentProfileNames,
                    disabledAgentIds = agentProfiles.filter { !it.value.enabled }.keys.toSet(),
                    agentAvatarUrls = agentAvatarUrls,
                    isGroupChat = true,
                    workingAgentIds = workingAgentIds,
                    groupMemberIds = groupMemberIds,
                    showAvatars = showAvatars,
                    viewModel = viewModel,
                    permissionViewModel = permissionViewModel,
                    ttsService = ttsState.ttsService,
                    onImageClick = {},
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

    MicPermissionRationaleDialog(
        show = micPermission.showRationaleDialog,
        onDismiss = micPermission.dismissRationaleDialog,
    )
}
