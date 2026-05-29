package com.lin.hippyagent.ui.channel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.ui.components.HippyTopBar
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import androidx.activity.compose.BackHandler

@Immutable
data class ChannelTypeInfo(
    val id: String,
    val name: String,
    val iconRes: Int,
    val descriptionResId: Int,
    val configFields: List<ConfigField>,
    val authType: AuthType = AuthType.MANUAL
)

@Immutable
data class ConfigField(
    val key: String,
    val labelResId: Int,
    val placeholderResId: Int,
    val isSecret: Boolean = false
)

enum class AuthType {
    MANUAL,
    QR_CODE
}

val SUPPORTED_CHANNELS = listOf(
    ChannelTypeInfo(
        id = "feishu",
        name = "飞书",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_feishu,
        descriptionResId = R.string.channel_config_feishu_desc,
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", R.string.channel_config_webhook_url, R.string.channel_config_feishu_webhook_hint),
            ConfigField("appId", R.string.channel_config_app_id, R.string.channel_config_auto_obtain),
            ConfigField("appSecret", R.string.channel_config_app_secret, R.string.channel_config_auto_obtain, isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "dingtalk",
        name = "钉钉",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_dingtalk,
        descriptionResId = R.string.channel_config_dingtalk_desc,
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", R.string.channel_config_webhook_url, R.string.channel_config_dingtalk_webhook_hint),
            ConfigField("secret", R.string.channel_config_secret_key, R.string.channel_config_secret_key_desc, isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "wechat",
        name = "企业微信",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_wechat,
        descriptionResId = R.string.channel_config_wechat_desc,
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", R.string.channel_config_webhook_url, R.string.channel_config_wechat_webhook_hint),
            ConfigField("corpId", R.string.channel_config_corp_id, R.string.channel_config_corp_id_desc),
            ConfigField("agentId", R.string.channel_config_agent_id, R.string.channel_config_agent_id_desc)
        )
    ),
    ChannelTypeInfo(
        id = "weixin",
        name = "个人微信",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_weixin,
        descriptionResId = R.string.channel_config_weixin_desc,
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("botToken", R.string.channel_config_bot_token, R.string.channel_config_bot_token_desc_weixin),
            ConfigField("baseUrl", R.string.channel_config_api_address, R.string.channel_config_weixin_api_url_hint)
        )
    ),
    ChannelTypeInfo(
        id = "qq",
        name = "QQ 机器人",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_qq,
        descriptionResId = R.string.channel_config_qq_desc,
        configFields = listOf(
            ConfigField("appId", R.string.channel_config_app_id, R.string.channel_config_bot_token_desc_qq),
            ConfigField("appSecret", R.string.channel_config_app_secret, R.string.channel_config_bot_token_desc_qq_secret, isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "telegram",
        name = "Telegram",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_telegram,
        descriptionResId = R.string.channel_config_telegram_desc,
        configFields = listOf(
            ConfigField("botToken", R.string.channel_config_bot_token, R.string.channel_config_telegram_bot_token_hint, isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "discord",
        name = "Discord",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_discord,
        descriptionResId = R.string.channel_config_discord_desc,
        configFields = listOf(
            ConfigField("webhookUrl", R.string.channel_config_webhook_url, R.string.channel_config_discord_webhook_hint)
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelConfigScreen(
    agentId: String,
    onBackClick: () -> Unit = {},
    onQrAuthClick: (agentId: String, channelId: String) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configStore = remember(context, agentId) {
        com.lin.hippyagent.core.channel.ChannelConfigStore(
            java.io.File(
                com.lin.hippyagent.core.storage.StorageManager(context).getWorkingDir(),
                "workspaces/$agentId/channels"
            )
        )
    }

    var selectedChannel by remember { mutableStateOf<ChannelTypeInfo?>(null) }
    var savedConfigs by remember { mutableStateOf(configStore.listConfigs()) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { configStore.listConfigs() }
            .collect { savedConfigs = it }
    }

    BackHandler(enabled = selectedChannel != null) {
        selectedChannel = null
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = if (selectedChannel != null) selectedChannel!!.name else stringResource(R.string.channel_config),
                showBackButton = true,
                onBackClick = {
                    if (selectedChannel != null) {
                        selectedChannel = null
                    } else {
                        onBackClick()
                    }
                }
            )
        }
    ) { padding ->
        if (selectedChannel != null) {
            ChannelEditView(
                channelType = selectedChannel!!,
                agentId = agentId,
                existingConfig = savedConfigs[selectedChannel!!.id],
                onSave = { config ->
                    configStore.saveConfig(selectedChannel!!.id, config)
                    savedConfigs = configStore.listConfigs()
                    showSaveSuccess = true
                    selectedChannel = null
                },
                onDelete = {
                    configStore.deleteConfig(selectedChannel!!.id)
                    savedConfigs = configStore.listConfigs()
                    selectedChannel = null
                },
                onBack = { selectedChannel = null },
                onQrAuthClick = onQrAuthClick
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.channel_config_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                items(SUPPORTED_CHANNELS, key = { it.id }) { channel ->
                    val isConfigured = savedConfigs.containsKey(channel.id)
                    ChannelCard(
                        channel = channel,
                        isConfigured = isConfigured,
                        onClick = { selectedChannel = channel }
                    )
                }
            }
        }
    }

    if (showSaveSuccess) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            showSaveSuccess = false
        }
        SnackbarHost(
            hostState = remember { SnackbarHostState() }.apply {
                LaunchedEffect(showSaveSuccess) {
                    if (showSaveSuccess) showSnackbar(context.getString(R.string.channel_config_saved))
                }
            }
        )
    }
}

@Composable
private fun ChannelCard(
    channel: ChannelTypeInfo,
    isConfigured: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = channel.iconRes),
                contentDescription = channel.name,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isConfigured) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.channel_configured),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(channel.descriptionResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun ChannelEditView(
    channelType: ChannelTypeInfo,
    agentId: String,
    existingConfig: Map<String, String>?,
    onSave: (Map<String, String>) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onQrAuthClick: (agentId: String, channelId: String) -> Unit = { _, _ -> }
) {
    val configValues = remember {
        mutableStateMapOf<String, String>().apply {
            channelType.configFields.forEach { field ->
                this[field.key] = existingConfig?.get(field.key) ?: ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(8.dp))
            Image(
                painter = painterResource(id = channelType.iconRes),
                contentDescription = channelType.name,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                channelType.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(channelType.descriptionResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (channelType.authType == AuthType.QR_CODE) {
            OutlinedButton(
                onClick = { onQrAuthClick(agentId, channelType.id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.channel_qr_bind))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.channel_manual_config),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        channelType.configFields.forEach { field ->
            OutlinedTextField(
                value = configValues[field.key] ?: "",
                onValueChange = { configValues[field.key] = it },
                label = { Text(stringResource(field.labelResId)) },
                placeholder = { Text(stringResource(field.placeholderResId)) },
                visualTransformation = if (field.isSecret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                singleLine = true
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onSave(configValues.toMap()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.channel_save_config))
        }

        if (existingConfig != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.channel_delete_config))
            }
        }
    }
}
