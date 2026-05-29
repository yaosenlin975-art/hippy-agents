package com.lin.hippyagent.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.ui.components.HippyTopBar
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TYPE_LABEL_RES = mapOf(
    BrainMemoryType.IDENTITY to R.string.memory_type_identity,
    BrainMemoryType.PREFERENCE to R.string.memory_type_preference,
    BrainMemoryType.GOAL to R.string.memory_type_goal,
    BrainMemoryType.PROJECT to R.string.memory_type_project,
    BrainMemoryType.HABIT to R.string.memory_type_habit,
    BrainMemoryType.DECISION to R.string.memory_type_decision,
    BrainMemoryType.CONSTRAINT to R.string.memory_type_constraint,
    BrainMemoryType.RELATIONSHIP to R.string.memory_type_relationship,
    BrainMemoryType.EPISODE to R.string.memory_type_episode,
    BrainMemoryType.REFLECTION to R.string.memory_type_reflection
)

private val TYPE_COLORS = mapOf(
    BrainMemoryType.IDENTITY to Color(0xFF6366F1),
    BrainMemoryType.PREFERENCE to Color(0xFF10B981),
    BrainMemoryType.GOAL to Color(0xFFF59E0B),
    BrainMemoryType.PROJECT to Color(0xFFEC4899),
    BrainMemoryType.HABIT to Color(0xFF8B5CF6),
    BrainMemoryType.DECISION to Color(0xFF06B6D4),
    BrainMemoryType.CONSTRAINT to Color(0xFFF97316),
    BrainMemoryType.RELATIONSHIP to Color(0xFFEF4444),
    BrainMemoryType.EPISODE to Color(0xFF84CC16),
    BrainMemoryType.REFLECTION to Color(0xFF6B7280)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonMemoryScreen(
    viewModel: CommonMemoryViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadEntries()
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.memory_common),
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.search(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.memory_search_hint), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.outline) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                )
            )

            Spacer(Modifier.height(8.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.filterType == null,
                        onClick = { viewModel.filterByType(null) },
                        label = { Text(stringResource(R.string.memory_all), fontSize = 12.sp) }
                    )
                }
                TYPE_LABEL_RES.forEach { (type, resId) ->
                    item(key = type.value) {
                        FilterChip(
                            selected = uiState.filterType == type,
                            onClick = { viewModel.filterByType(type) },
                            label = { Text(stringResource(resId), fontSize = 12.sp) }
                        )
                    }
                }
            }

            if (uiState.stats != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_count, uiState.entries.size, uiState.stats!!.total),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.memory_no_data),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(uiState.entries, key = { it.id }) { entry ->
                        MemoryCard(
                            entry = entry,
                            isDeleteConfirm = uiState.pendingDeleteId == entry.id,
                            onDelete = { viewModel.deleteEntry(entry.id) },
                            onConfirmDelete = { viewModel.confirmDelete(entry.id) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    entry: CommonMemoryEntry,
    isDeleteConfirm: Boolean,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typeColor = TYPE_COLORS[entry.type] ?: Color.Gray
    val typeLabel = TYPE_LABEL_RES[entry.type]?.let { stringResource(it) } ?: entry.type.value

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(typeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        typeLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = typeColor
                    )
                }
                Spacer(Modifier.weight(1f))
                val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                Text(
                    dateFormat.format(Date(entry.updatedAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                var showDeleteDialog by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text(stringResource(R.string.memory_delete)) },
                        text = { Text(stringResource(R.string.memory_delete_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteDialog = false
                                onDelete()
                            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                entry.summary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!entry.detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.detail,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConfidenceBar(stringResource(R.string.memory_confidence), entry.confidence, Modifier.weight(1f))
                ConfidenceBar(stringResource(R.string.memory_importance), entry.importance, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConfidenceBar(
    label: String,
    value: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (value >= 0.7f) Color(0xFF10B981) else if (value >= 0.4f) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
