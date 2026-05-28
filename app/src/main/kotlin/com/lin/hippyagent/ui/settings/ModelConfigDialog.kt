package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.core.model.ModelConfig
import java.util.UUID

@Composable
fun ModelConfigDialog(
    providerId: String,
    model: ModelConfig?,
    onDismiss: () -> Unit,
    onConfirm: (ModelConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(model?.name ?: "") }
    var displayName by remember { mutableStateOf(model?.displayName ?: "") }
    var maxTokensStr by remember { mutableStateOf((model?.maxTokens ?: 4096).toString()) }
    var contextWindowStr by remember { mutableStateOf((model?.contextWindow ?: 16384).toString()) }
    var temperature by remember { mutableFloatStateOf(model?.temperature ?: 0.7f) }
    var topP by remember { mutableFloatStateOf(model?.topP ?: 1.0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model != null) "编辑模型" else "添加模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("如 gpt-4, claude-3-sonnet") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名称（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = maxTokensStr,
                    onValueChange = { if (it.all { c -> c.isDigit() }) maxTokensStr = it },
                    label = { Text("Max Tokens") },
                    placeholder = { Text("如 4096") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contextWindowStr,
                    onValueChange = { if (it.all { c -> c.isDigit() }) contextWindowStr = it },
                    label = { Text("Context Window") },
                    placeholder = { Text("如 8192") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Temperature: %.2f".format(temperature), modifier = Modifier.fillMaxWidth())
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 20
                )

                Text("Top P: %.2f".format(topP), modifier = Modifier.fillMaxWidth())
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f,
                    steps = 20
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ModelConfig(
                            id = model?.id ?: UUID.randomUUID().toString(),
                            providerId = providerId,
                            name = name,
                            displayName = displayName,
                            maxTokens = maxTokensStr.toIntOrNull() ?: 4096,
                            contextWindow = contextWindowStr.toIntOrNull() ?: 16384,
                            temperature = temperature,
                            topP = topP
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

