package com.lin.hippyagent.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onBackClick: () -> Unit,
    onTaskClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务中心") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // 状态过滤
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { viewModel.setStatusFilter(null) },
                    label = { Text("全部", fontSize = 12.sp) }
                )
                listOf(
                    TaskStatus.RUNNING to "进行中",
                    TaskStatus.AWAITING_APPROVAL to "待审批",
                    TaskStatus.COMPLETED to "已完成",
                    TaskStatus.FAILED to "失败",
                ).forEach { (status, label) ->
                    FilterChip(
                        selected = statusFilter == status,
                        onClick = { viewModel.setStatusFilter(status) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无任务",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onClick = { onTaskClick(task.id) },
                            onCancel = { viewModel.cancelTask(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    val canCancel = task.status == TaskStatus.RUNNING || task.status == TaskStatus.AWAITING_APPROVAL
    val statusColor = when (task.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TaskStatus.AWAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
        TaskStatus.COMPLETED -> Color(0xFF2E7D32)
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        TaskStatus.PENDING -> MaterialTheme.colorScheme.secondary
    }
    val completedSteps = task.steps.count { it.status == com.lin.hippyagent.core.agent.task.StepStatus.COMPLETED }
    val totalSteps = task.steps.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = task.title,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (canCancel) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = "取消",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.status.name,
                    fontSize = 11.sp,
                    color = statusColor
                )
                Text(
                    text = "进度 $completedSteps/$totalSteps",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDate(task.createdAt),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val LIST_DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatDate(timestamp: Long): String = LIST_DATE_FORMAT.format(Date(timestamp))
