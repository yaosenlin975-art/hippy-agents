package com.lin.hippyagent.ui.settings.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.storage.ConfigStorage
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Immutable
data class GlobalRulesUiState(
    val rules: String = "",
    val isLoading: Boolean = true,
    val saveSuccess: Boolean = false
)

class GlobalRulesViewModel(
    private val configStorage: ConfigStorage,
    private val application: android.app.Application
) : ViewModel() {
    private val _uiState = MutableStateFlow(GlobalRulesUiState())
    val uiState: StateFlow<GlobalRulesUiState> = _uiState.asStateFlow()
    private var saveJob: Job? = null

    init {
        loadRules()
    }

    private fun loadRules() {
        var rules = configStorage.getString("global_rules", "")
        if (rules.isBlank()) {
            // 首次安装：从 assets 读取默认全局规则
            rules = try {
                application.assets.open("default_global_rules.md")
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            if (rules.isNotBlank()) {
                configStorage.putString("global_rules", rules)
            }
        }
        _uiState.update { it.copy(rules = rules, isLoading = false) }
    }

    fun updateRules(rules: String) {
        _uiState.update { it.copy(rules = rules) }
        debouncedSave()
    }

    private fun debouncedSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500L)
            configStorage.putString("global_rules", _uiState.value.rules)
            _uiState.update { it.copy(saveSuccess = true) }
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalRulesScreen(
    viewModel: GlobalRulesViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(context.getString(R.string.global_rules_saved), duration = SnackbarDuration.Short)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.settings_global_rules),
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.global_rules_hint),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )

            OutlinedTextField(
                value = uiState.rules,
                onValueChange = viewModel::updateRules,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text(stringResource(R.string.global_rules_placeholder)) },
                minLines = 6
            )
        }
    }
}
