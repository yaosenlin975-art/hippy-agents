package com.lin.hippyagent.ui.task

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.agent.task.ApprovalNode
import com.lin.hippyagent.core.agent.task.ApprovalStatus
import com.lin.hippyagent.core.agent.task.StepStatus
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskStatus
import com.lin.hippyagent.core.agent.task.TaskStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: String,
    viewModel: TaskDetailViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task by viewModel.task.collectAsStateWithLifecycle()

    LaunchedEffect(taskId) { viewModel.load(taskId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.title ?: "任务详情") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        val current = task
        if (current == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { TaskSummaryCard(current) }
            item {
                Text(
                    "执行步骤",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            items(current.steps, key = { it.id }) { step ->
                StepRow(step)
            }
            if (current.approvalNodes.isNotEmpty()) {
                item {
                    Text(
                        "审批节点",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                items(current.approvalNodes, key = { it.id }) { node ->
                    ApprovalRow(
                        node = node,
                        onApprove = { viewModel.approve(node.id) },
                        onReject = { viewModel.reject(node.id) }
                    )
                }
            }
            current.result?.let { result ->
                item {
                    Text(
                        "结果",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Text(
                            result,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSummaryCard(task: TaskEntity) {
    val statusColor = when (task.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        TaskStatus.AWAITING_APPROVAL -> MaterialTheme.colorScheme.tertiary
        TaskStatus.COMPLETED -> Color(0xFF2E7D32)
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        TaskStatus.PENDING -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "状态",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    task.status.name,
                    fontSize = 13.sp,
                    color = statusColor
                )
            }
            Spacer(Modifier.size(4.dp))
            Row {
                Text("智能体", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(task.agentId, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.size(4.dp))
            Row {
                Text("创建", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(formatDate(task.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            task.completedAt?.let { completed ->
                Spacer(Modifier.size(4.dp))
                Row {
                    Text("完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(formatDate(completed), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            task.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                Spacer(Modifier.size(6.dp))
                Text(
                    "错误: $err",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: TaskStep) {
    val (icon, color) = when (step.status) {
        StepStatus.PENDING -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.outline
        StepStatus.RUNNING -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.primary
        StepStatus.COMPLETED -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
        StepStatus.FAILED -> Icons.Default.Cancel to MaterialTheme.colorScheme.error
        StepStatus.SKIPPED -> Icons.Default.Cancel to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(step.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                if (step.status == StepStatus.RUNNING) {
                    Spacer(Modifier.size(2.dp))
                    Text("执行中...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                step.result?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                step.error?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "错误: $it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                step.status.name,
                fontSize = 10.sp,
                color = color
            )
        }
    }
}

@Composable
private fun ApprovalRow(
    node: ApprovalNode,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val pending = node.status == ApprovalStatus.PENDING
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(node.prompt, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(6.dp))
            Text(
                "状态: ${node.status.name}${node.decidedBy?.let { " · $it" } ?: ""}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pending) {
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("通过", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onReject) {
                        Text("拒绝", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private val DETAIL_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatDate(timestamp: Long): String = DETAIL_DATE_FORMAT.format(Date(timestamp))
