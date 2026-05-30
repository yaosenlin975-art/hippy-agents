package com.lin.hippyagent.ui.store.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.lin.hippyagent.core.skill.store.provider.MarketProviderInfo

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderChips(
    providers: List<MarketProviderInfo>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        providers.forEach { provider ->
            val selected = provider.key in selectedKeys
            val chip = @Composable {
                FilterChip(
                    selected = selected,
                    onClick = { if (provider.available) onToggle(provider.key) },
                    label = { Text(provider.label, style = MaterialTheme.typography.labelSmall) },
                    enabled = provider.available,
                    colors = FilterChipDefaults.filterChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            }

            if (!provider.available && provider.reason != null) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(provider.reason) } },
                    state = rememberTooltipState()
                ) {
                    chip()
                }
            } else {
                chip()
            }
        }
    }
}
