package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.model.ContextWindowGuard
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
    var contextWindowError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model != null) stringResource(R.string.model_edit_title) else stringResource(R.string.model_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.model_name_label)) },
                    placeholder = { Text(stringResource(R.string.model_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.model_display_name_optional)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = maxTokensStr,
                    onValueChange = { if (it.all { c -> c.isDigit() }) maxTokensStr = it },
                    label = { Text(stringResource(R.string.model_max_tokens)) },
                    placeholder = { Text(stringResource(R.string.placeholder_max_tokens)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contextWindowStr,
                    onValueChange = {
                        if (it.all { c -> c.isDigit() }) {
                            contextWindowStr = it
                            contextWindowError = null
                        }
                    },
                    label = { Text(stringResource(R.string.model_context_window)) },
                    placeholder = { Text(stringResource(R.string.placeholder_context_window)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                contextWindowError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(stringResource(R.string.model_temperature_format, temperature), modifier = Modifier.fillMaxWidth())
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 20
                )

                Text(stringResource(R.string.model_top_p_format, topP), modifier = Modifier.fillMaxWidth())
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
                    val contextWindow = contextWindowStr.toIntOrNull() ?: 16384
                    val guardResult = ContextWindowGuard.check(contextWindow)
                    if (guardResult.decision != ContextWindowGuard.GuardDecision.BLOCK) {
                        onConfirm(
                            ModelConfig(
                                id = model?.id ?: UUID.randomUUID().toString(),
                                providerId = providerId,
                                name = name,
                                displayName = displayName,
                                maxTokens = maxTokensStr.toIntOrNull() ?: 4096,
                                contextWindow = contextWindow,
                                temperature = temperature,
                                topP = topP
                            )
                        )
                    } else {
                        contextWindowError = guardResult.message
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
