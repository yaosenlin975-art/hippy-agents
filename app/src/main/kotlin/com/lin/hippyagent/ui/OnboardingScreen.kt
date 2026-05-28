package com.lin.hippyagent.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val description: String
)

private val ONBOARDING_STEPS = listOf(
    OnboardingStep(
        icon = Icons.Default.Rocket,
        title = "欢迎来到 HippyAgent",
        description = "你的智能助手可在手机端运行 AI Agent，自动化完成任务，数据完全本地，隐私安全。"
    ),
    OnboardingStep(
        icon = Icons.Default.Settings,
        title = "配置模型供应商",
        description = "支持 GPT-4o、Claude、DeepSeek、Gemini 等多种模型，也可连接本地 LM Studio 或 llama.cpp。"
    ),
    OnboardingStep(
        icon = Icons.Default.Lock,
        title = "授权核心权限",
        description = "启用无障碍服务、通知监听等权限，让 Agent 能自动操作手机和响应消息。"
    ),
    OnboardingStep(
        icon = Icons.Default.Build,
        title = "开始使用",
        description = "创建你的第一个 Agent，或从预设模板开始。随时可通过设置重新体验引导。"
    )
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToModelProvider: () -> Unit = {},
    onNavigateToPermissionCenter: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onComplete) {
                Text("跳过", fontSize = 15.sp)
            }
        }

        Spacer(Modifier.weight(0.3f))

        val step = ONBOARDING_STEPS[currentStep]
        Icon(
            imageVector = step.icon,
            contentDescription = step.title,
            modifier = Modifier.size(64.dp).padding(bottom = 24.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            step.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            step.description,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.weight(0.5f))

        Row(
            modifier = Modifier.padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ONBOARDING_STEPS.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentStep) 24.dp else 8.dp, 8.dp)
                        .background(
                            if (index == currentStep) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }

        Button(
            onClick = {
                if (currentStep < ONBOARDING_STEPS.size - 1) {
                    currentStep++
                } else {
                    onComplete()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (currentStep < ONBOARDING_STEPS.size - 1) "继续" else "开始使用",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        if (currentStep == 1) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onNavigateToModelProvider) {
                Text("已有 API Key？直接配置 →", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
