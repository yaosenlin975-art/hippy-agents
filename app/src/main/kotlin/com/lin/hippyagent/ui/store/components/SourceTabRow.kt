package com.lin.hippyagent.ui.store.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.store.SkillSource

@Composable
internal fun SourceTabRow(
    activeSource: SkillSource?,
    onSourceChange: (SkillSource?) -> Unit
) {
    val sources = listOf(null to stringResource(R.string.store_source_all)) + SkillSource.entries.map { it to it.displayName }
    val selectedIndex = sources.indexOfFirst { it.first == activeSource }.coerceAtLeast(0)

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex])
                )
            }
        },
        divider = {}
    ) {
        sources.forEachIndexed { index, (source, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSourceChange(source) },
                text = { Text(label, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}
