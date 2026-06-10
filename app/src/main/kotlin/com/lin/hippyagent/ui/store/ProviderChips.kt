package com.lin.hippyagent.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StatusGreen = Color(0xFF2E7D32)
private val StatusYellow = Color(0xFFF9A825)
private val StatusGray = Color(0xFF9E9E9E)

private val ProviderOrder = listOf("lobehub", "skills_sh", "clawhub")
private val ProviderFallbackNames = mapOf(
    "lobehub" to "LobeHub",
    "skills_sh" to "Skills.sh",
    "clawhub" to "ClawHub"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderChips(
    statuses: Map<String, ProviderStatus>,
    errorProviderKeys: Set<String>,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderOrder.forEach { key ->
            val status = statuses[key] ?: ProviderStatus(
                providerId = key,
                available = false,
                displayName = ProviderFallbackNames[key] ?: key,
                lastError = "unknown"
            )
            val color = when {
                !status.available -> StatusGray
                errorProviderKeys.contains(key) -> StatusYellow
                else -> StatusGreen
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(status.displayName) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, CircleShape)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = Color.Transparent,
                    disabledLabelColor = AssistChipDefaults.assistChipColors().labelColor,
                    disabledLeadingIconContentColor = AssistChipDefaults.assistChipColors().leadingIconContentColor
                )
            )
        }
    }
}
