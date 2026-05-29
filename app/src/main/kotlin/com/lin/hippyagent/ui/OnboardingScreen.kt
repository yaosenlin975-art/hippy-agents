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
import androidx.compose.ui.res.stringResource
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
import com.lin.hippyagent.R

@Immutable
private data class OnboardingStep(
    val icon: ImageVector,
    val titleResId: Int,
    val descriptionResId: Int
)

private val ONBOARDING_STEPS = listOf(
    OnboardingStep(
        icon = Icons.Default.Rocket,
        titleResId = R.string.onboarding_welcome,
        descriptionResId = R.string.onboarding_welcome_desc
    ),
    OnboardingStep(
        icon = Icons.Default.Settings,
        titleResId = R.string.onboarding_config_provider,
        descriptionResId = R.string.onboarding_config_provider_desc
    ),
    OnboardingStep(
        icon = Icons.Default.Lock,
        titleResId = R.string.onboarding_authorize_permissions,
        descriptionResId = R.string.onboarding_authorize_permissions_desc
    ),
    OnboardingStep(
        icon = Icons.Default.Build,
        titleResId = R.string.onboarding_get_started,
        descriptionResId = R.string.onboarding_get_started_desc
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
                Text(stringResource(R.string.common_skip), fontSize = 15.sp)
            }
        }

        Spacer(Modifier.weight(0.3f))

        val step = ONBOARDING_STEPS[currentStep]
        Icon(
            imageVector = step.icon,
            contentDescription = stringResource(step.titleResId),
            modifier = Modifier.size(64.dp).padding(bottom = 24.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            stringResource(step.titleResId),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(step.descriptionResId),
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
                if (currentStep < ONBOARDING_STEPS.size - 1) stringResource(R.string.onboarding_continue) else stringResource(R.string.onboarding_get_started),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        if (currentStep == 1) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onNavigateToModelProvider) {
                Text(stringResource(R.string.onboarding_have_api_key), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
