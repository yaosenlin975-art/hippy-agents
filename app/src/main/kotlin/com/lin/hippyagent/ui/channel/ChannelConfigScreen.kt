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
import androidx.activity.compose.BackHandler

@Immutable
data class ChannelTypeInfo(
    val id: String,
    val name: String,
    val iconRes: Int,
    val description: String,
    val configFields: List<ConfigField>,
    val authType: AuthType = AuthType.MANUAL
)

@Immutable
data class ConfigField(
    val key: String,
    val label: String,
    val placeholder: String,
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
        description = "通过飞书机器人发送消息，支持扫码自动配置",
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", "Webhook URL", "https://open.feishu.cn/open-apis/bot/v2/hook/..."),
            ConfigField("appId", "App ID", "扫码登录后自动获取"),
            ConfigField("appSecret", "App Secret", "扫码登录后自动获取", isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "dingtalk",
        name = "钉钉",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_dingtalk,
        description = "通过钉钉机器人发送消息，支持扫码自动配置",
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", "Webhook URL", "https://oapi.dingtalk.com/robot/send?access_token=..."),
            ConfigField("secret", "加签密钥", "SEC...（可选，用于签名验证）", isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "wechat",
        name = "企业微信",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_wechat,
        description = "通过企业微信机器人发送消息，支持扫码自动配置",
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("webhookUrl", "Webhook URL", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."),
            ConfigField("corpId", "企业 ID", "ww...（接收消息时需要）"),
            ConfigField("agentId", "应用 ID", "1000002（接收消息时需要）")
        )
    ),
    ChannelTypeInfo(
        id = "weixin",
        name = "个人微信",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_weixin,
        description = "通过个人微信 iLink Bot 接收和发送消息，支持扫码登录",
        authType = AuthType.QR_CODE,
        configFields = listOf(
            ConfigField("botToken", "Bot Token", "扫码登录后自动获取（也可手动填写）"),
            ConfigField("baseUrl", "API 地址", "https://ilinkai.weixin.qq.com")
        )
    ),
    ChannelTypeInfo(
        id = "qq",
        name = "QQ 机器人",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_qq,
        description = "通过 QQ 机器人 Official API 接收和发送消息",
        configFields = listOf(
            ConfigField("appId", "App ID", "QQ 机器人的 App ID"),
            ConfigField("appSecret", "App Secret", "QQ 机器人的 Client Secret", isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "telegram",
        name = "Telegram",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_telegram,
        description = "通过 Telegram Bot API 接收和发送消息",
        configFields = listOf(
            ConfigField("botToken", "Bot Token", "123456:ABC-DEF...", isSecret = true)
        )
    ),
    ChannelTypeInfo(
        id = "discord",
        name = "Discord",
        iconRes = com.lin.hippyagent.R.drawable.ic_channel_discord,
        description = "通过 Discord Webhook 发送消息",
        configFields = listOf(
            ConfigField("webhookUrl", "Webhook URL", "https://discord.com/api/webhooks/...")
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
                title = if (selectedChannel != null) selectedChannel!!.name else "频道配置",
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
                        "配置智能体的消息推送渠道。支持 Webhook 推送和双向通信。",
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
                    if (showSaveSuccess) showSnackbar("频道配置已保存")
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
                            contentDescription = "已配置",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    channel.description,
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
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            channelType.description,
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
                Text("扫码登录绑定")
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "或手动填写配置：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        channelType.configFields.forEach { field ->
            OutlinedTextField(
                value = configValues[field.key] ?: "",
                onValueChange = { configValues[field.key] = it },
                label = { Text(field.label) },
                placeholder = { Text(field.placeholder) },
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
            Text("保存配置")
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
                Text("删除配置")
            }
        }
    }
}
