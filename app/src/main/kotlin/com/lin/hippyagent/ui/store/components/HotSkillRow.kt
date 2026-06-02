package com.lin.hippyagent.ui.store.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.store.StoreSkillItem

@Composable
internal fun HotSkillsRow(
    skills: List<StoreSkillItem>,
    installedIds: Set<String>,
    installingIds: Set<String> = emptySet(),
    queuedIds: Set<String> = emptySet(),
    onSkillClick: (StoreSkillItem) -> Unit,
    onInstall: (StoreSkillItem) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(skills, key = { it.identifier }) { skill ->
            HotSkillCard(
                skill = skill,
                isInstalled = installedIds.contains(skill.identifier),
                isInstalling = installingIds.contains(skill.identifier),
                isQueued = queuedIds.contains(skill.identifier),
                onInstall = { onInstall(skill) },
                onClick = { onSkillClick(skill) }
            )
        }
    }
}

@Composable
internal fun HotSkillCard(
    skill: StoreSkillItem,
    isInstalled: Boolean,
    isInstalling: Boolean = false,
    isQueued: Boolean = false,
    onInstall: () -> Unit,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            SourceBadge(skill.source)
            Spacer(Modifier.height(8.dp))
            Text(
                skill.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (skill.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.store_by_author, skill.author),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (skill.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(String.format("%.1f", skill.rating), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (skill.installCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(formatCount(skill.installCount), style = MaterialTheme.typography.labelSmall)
                    }
                } else if (skill.confidence > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(String.format("%.1f", skill.confidence), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (isInstalling) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (isQueued) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.store_queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
