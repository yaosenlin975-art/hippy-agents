package com.lin.hippyagent.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.network.NetworkMonitor
import com.lin.hippyagent.core.network.NetworkState
import com.lin.hippyagent.core.network.OfflineMessageQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 输入栏 Chip 类型 */
enum class InputChipType { FILE, IMAGE, SKILL, MENTION }

/** 输入栏中的附件/技能 Chip */
@Immutable
data class InputChip(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: InputChipType,
    val label: String,
    val uri: String? = null   // 文件/图片的 content URI，技能为 null
)

@Immutable
data class QuotedMessage(
    val messageId: String,
    val content: String,
    val senderName: String
)

@Immutable
data class ChatInputState(
    val inputText: String = "",
    val chips: List<InputChip> = emptyList(),
    val isComposing: Boolean = false,
    val canSend: Boolean = false,
    val offlineQueueSize: Int = 0,
    val networkState: NetworkState = NetworkState(),
    val quotedMessage: QuotedMessage? = null
)

class ChatInputViewModel(
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatInputState())
    val uiState: StateFlow<ChatInputState> = _uiState.asStateFlow()

    private val networkMonitor = NetworkMonitor(context)
    private val offlineQueue = OfflineMessageQueue(context)

    private val draftPrefs = context.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)
    private var currentDraftKey: String = ""

    init {
        viewModelScope.launch {
            networkMonitor.observeNetworkState().collect { state ->
                _uiState.update { it.copy(networkState = state) }
                if (state.isConnected && !offlineQueue.isEmpty()) {
                    flushOfflineQueue()
                }
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update {
            it.copy(
                inputText = text,
                canSend = text.isNotBlank() || it.chips.isNotEmpty()
            )
        }
    }

    fun appendAttachmentText(insert: String) {
        _uiState.update {
            val current = it.inputText
            val newText = if (current.isBlank()) insert else "$current $insert"
            it.copy(
                inputText = newText,
                canSend = newText.isNotBlank() || it.chips.isNotEmpty()
            )
        }
    }

    /** 添加一个 Chip（附件/图片/技能） */
    fun addChip(chip: InputChip) {
        _uiState.update {
            it.copy(
                chips = it.chips + chip,
                canSend = it.inputText.isNotBlank() || (it.chips + chip).isNotEmpty()
            )
        }
    }

    /** 移除指定 Chip */
    fun removeChip(chipId: String) {
        _uiState.update {
            val newChips = it.chips.filter { c -> c.id != chipId }
            it.copy(
                chips = newChips,
                canSend = it.inputText.isNotBlank() || newChips.isNotEmpty()
            )
        }
    }

    /** 清空所有 Chip */
    fun clearChips() {
        _uiState.update {
            it.copy(chips = emptyList())
        }
    }

    /** 兼容旧接口：设置单个附件 URI（自动转为 Chip） */
    fun setAttachedFileUri(uri: String?) {
        _uiState.update {
            val newChips = if (uri != null) {
                it.chips.filter { c -> c.uri != uri } + InputChip(type = InputChipType.FILE, label = uri.substringAfterLast('/'), uri = uri)
            } else {
                it.chips.filter { c -> c.type != InputChipType.FILE }
            }
            it.copy(
                chips = newChips,
                canSend = it.inputText.isNotBlank() || newChips.isNotEmpty()
            )
        }
    }

    fun setComposing(composing: Boolean) {
        _uiState.update { it.copy(isComposing = composing) }
    }

    fun setQuotedMessage(messageId: String, content: String, senderName: String) {
        _uiState.update { it.copy(quotedMessage = QuotedMessage(messageId, content, senderName)) }
    }

    fun clearQuotedMessage() {
        _uiState.update { it.copy(quotedMessage = null) }
    }

    fun consumeInput(): Triple<String, List<InputChip>, QuotedMessage?> {
        val text = _uiState.value.inputText
        val chips = _uiState.value.chips
        val quoted = _uiState.value.quotedMessage
        _uiState.update {
            it.copy(inputText = "", chips = emptyList(), canSend = false, quotedMessage = null)
        }
        clearDraft()
        return Triple(text, chips, quoted)
    }

    fun saveDraft() {
        val state = _uiState.value
        if (currentDraftKey.isBlank()) return
        if (state.inputText.isBlank() && state.chips.isEmpty()) {
            draftPrefs.edit().remove(currentDraftKey).apply()
            return
        }
        val json = JSONObject().apply {
            put("text", state.inputText)
            val chipsArr = JSONArray()
            for (chip in state.chips) {
                chipsArr.put(JSONObject().apply {
                    put("id", chip.id)
                    put("type", chip.type.name)
                    put("label", chip.label)
                    if (chip.uri != null) put("uri", chip.uri)
                })
            }
            put("chips", chipsArr)
        }
        draftPrefs.edit().putString(currentDraftKey, json.toString()).apply()
    }

    fun restoreDraft(sessionId: String) {
        saveDraft()
        currentDraftKey = "draft_$sessionId"
        val saved = draftPrefs.getString(currentDraftKey, null)
        if (saved != null) {
            try {
                val json = JSONObject(saved)
                val text = json.optString("text", "")
                val chipsArr = json.optJSONArray("chips")
                val chips = mutableListOf<InputChip>()
                if (chipsArr != null) {
                    for (i in 0 until chipsArr.length()) {
                        val obj = chipsArr.getJSONObject(i)
                        chips.add(InputChip(
                            id = obj.getString("id"),
                            type = InputChipType.valueOf(obj.getString("type")),
                            label = obj.getString("label"),
                            uri = obj.optString("uri", null)
                        ))
                    }
                }
                _uiState.update {
                    it.copy(
                        inputText = text,
                        chips = chips,
                        canSend = text.isNotBlank() || chips.isNotEmpty()
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(inputText = "", chips = emptyList(), canSend = false) }
            }
        } else {
            _uiState.update { it.copy(inputText = "", chips = emptyList(), canSend = false) }
        }
    }

    private fun clearDraft() {
        if (currentDraftKey.isNotBlank()) {
            draftPrefs.edit().remove(currentDraftKey).apply()
        }
    }

    fun isOnline(): Boolean = networkMonitor.isOnline()

    fun enqueueOfflineMessage(sessionId: String, content: String) {
        viewModelScope.launch {
            offlineQueue.enqueue(com.lin.hippyagent.core.network.QueuedMessage(
                sessionId = sessionId,
                content = content,
                channelId = "console"
            ))
            val size = offlineQueue.size()
            _uiState.update { it.copy(offlineQueueSize = size) }
        }
    }

    private suspend fun flushOfflineQueue() {
        val queued = offlineQueue.getAll()
        for (msg in queued) {
            offlineQueue.remove(msg.id)
        }
        _uiState.update { it.copy(offlineQueueSize = 0) }
    }

    fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }
}
