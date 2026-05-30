package com.lin.hippyagent.ui.store.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.store.InstallQueue

@Composable
internal fun InstallQueuePanel(
    items: List<InstallQueue.QueueItem>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val hasCompleted = remember(items) {
        items.any { it.status == InstallQueue.QueueItem.Status.COMPLETED }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.install_queue_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            if (hasCompleted) {
                TextButton(onClick = onClearCompleted) {
                    Text(stringResource(R.string.install_queue_clear_completed))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            InstallQueueItem(
                item = item,
                onCancel = { onCancel(item.id) },
                onRetry = { onRetry(item.id) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InstallQueueItem(
    item: InstallQueue.QueueItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (item.status) {
                InstallQueue.QueueItem.Status.INSTALLING -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                InstallQueue.QueueItem.Status.COMPLETED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                InstallQueue.QueueItem.Status.FAILED -> {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
                InstallQueue.QueueItem.Status.CANCELLED -> {
                    Icon(
                        Icons.Default.Cancel,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                InstallQueue.QueueItem.Status.QUEUED -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.error != null) {
                    Text(
                        text = item.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.status == InstallQueue.QueueItem.Status.FAILED || item.status == InstallQueue.QueueItem.Status.CANCELLED) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.install_queue_retry))
                }
            }
            if (item.status == InstallQueue.QueueItem.Status.INSTALLING || item.status == InstallQueue.QueueItem.Status.QUEUED) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.install_queue_cancel))
                }
            }
        }
    }
}
