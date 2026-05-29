package com.lin.hippyagent.ui.settings.general

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar

private const val PREFS_NAME = "ui_settings"
private const val KEY_INACTIVE_THRESHOLD = "inactive_threshold_minutes"
private const val DEFAULT_INACTIVE_THRESHOLD = 60
private const val KEY_CHAT_FONT_SIZE = "chat_font_size_sp"
private const val KEY_FONT_SCALE = "app_font_scale"
private const val KEY_SHOW_AVATAR = "show_agent_avatar"
private const val DEFAULT_CHAT_FONT_SIZE = 14
private const val MIN_CHAT_FONT_SIZE = 8
private const val MAX_CHAT_FONT_SIZE = 24

fun readShowAgentAvatar(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_SHOW_AVATAR, true)
}

fun writeShowAgentAvatar(context: Context, show: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SHOW_AVATAR, show)
        .apply()
}

fun readInactiveThreshold(context: Context): Int {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_INACTIVE_THRESHOLD, DEFAULT_INACTIVE_THRESHOLD)
}

fun writeInactiveThreshold(context: Context, minutes: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_INACTIVE_THRESHOLD, minutes)
        .apply()
}

fun readChatFontSize(context: Context): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val base = if (!prefs.contains(KEY_CHAT_FONT_SIZE)) {
        val systemFontScale = context.resources.configuration.fontCoerce
        (DEFAULT_CHAT_FONT_SIZE * systemFontScale).toInt()
    } else {
        prefs.getInt(KEY_CHAT_FONT_SIZE, DEFAULT_CHAT_FONT_SIZE)
    }
    // 应用全局字号缩放比例
    val scale = readFontScale(context)
    return (base * scale).toInt().coerceIn(MIN_CHAT_FONT_SIZE, MAX_CHAT_FONT_SIZE)
}

fun readFontScale(context: Context): Float {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return if (prefs.contains(KEY_FONT_SCALE)) {
        prefs.getFloat(KEY_FONT_SCALE, 1.0f)
    } else {
        context.resources.configuration.fontCoerce
    }
}

private val Configuration.fontCoerce: Float
    get() = fontScale.coerceIn(0.5f, 2.0f)

fun writeFontScale(context: Context, scale: Float) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY_FONT_SCALE, scale.coerceIn(0.5f, 2.0f))
        .apply()
}

fun writeChatFontSize(context: Context, sizeSp: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_CHAT_FONT_SIZE, sizeSp.coerceIn(MIN_CHAT_FONT_SIZE, MAX_CHAT_FONT_SIZE))
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiSettingsScreen(
    onBack: () -> Unit,
    onThresholdChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thresholdText by remember {
        mutableStateOf(readInactiveThreshold(context).toString())
    }
    var isError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.settings_ui_settings),
                onBackClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.ui_session_list),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ui_inactive_threshold),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.ui_inactive_threshold_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        OutlinedTextField(
                            value = thresholdText,
                            onValueChange = { v ->
                                thresholdText = v
                                val parsed = v.toIntOrNull()
                                isError = parsed == null || parsed < 0
                                if (!isError && parsed != null) {
                                    writeInactiveThreshold(context, parsed)
                                    onThresholdChanged(parsed)
                                }
                            },
                            modifier = Modifier.width(88.dp),
                            singleLine = true,
                            isError = isError,
                            suffix = { Text(stringResource(R.string.ui_minutes), fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }

            item {
                if (isError) {
                    Text(
                        text = stringResource(R.string.ui_invalid_number),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.ui_chat_interface),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
                )
            }

            item {
            var fontScale by remember {
                mutableFloatStateOf(readFontScale(context))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ui_font_scale),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.ui_font_scale_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ui_font_small),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = fontScale,
                            onValueChange = {
                                fontScale = it
                                writeFontScale(context, it)
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.ui_font_large),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(fontScale * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.ui_preview),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Text(
                                text = stringResource(R.string.ui_preview_text),
                                fontSize = (14 * fontScale).sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                lineHeight = (20 * fontScale).sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "The best design is invisible.",
                                fontSize = (12 * fontScale).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = (16 * fontScale).sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.ui_example_tag), fontSize = (11 * fontScale).sp) }
                                )
                                OutlinedButton(
                                    onClick = {},
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.ui_example_button), fontSize = (12 * fontScale).sp)
                                }
                            }
                        }
                    }
                }
            }
        }

            item {
                var showAvatar by remember { mutableStateOf(readShowAgentAvatar(context)) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ui_show_agent_avatar), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                            Text(stringResource(R.string.ui_show_agent_avatar_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = showAvatar, onCheckedChange = {
                            showAvatar = it
                            writeShowAgentAvatar(context, it)
                        })
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

