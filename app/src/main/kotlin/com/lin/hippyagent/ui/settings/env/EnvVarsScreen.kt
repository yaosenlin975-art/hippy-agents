package com.lin.hippyagent.ui.settings.env

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EnvVarItem(val key: String, val value: String)

class EnvVarsViewModel(
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage
) : ViewModel() {
    private companion object {
        const val PREFIX = "env_var_"
    }

    private val _vars = MutableStateFlow<List<EnvVarItem>>(emptyList())
    val vars: StateFlow<List<EnvVarItem>> = _vars.asStateFlow()

    init { reload() }

    fun reload() {
        val items = configStorage.getAllKeys()
            .filter { it.startsWith(PREFIX) }
            .map { key ->
                val name = key.removePrefix(PREFIX)
                EnvVarItem(name, configStorage.getString(key))
            }
            .sortedBy { it.key }
        _vars.value = items
    }

    fun add(key: String, value: String) {
        if (key.isBlank()) return
        configStorage.putString(PREFIX + key, value)
        reload()
    }

    fun update(key: String, value: String) {
        configStorage.putString(PREFIX + key, value)
        reload()
    }

    fun delete(key: String) {
        configStorage.remove(PREFIX + key)
        reload()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvVarsScreen(
    viewModel: EnvVarsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vars by viewModel.vars.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.env_vars_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.add))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (showAdd) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.env_vars_add), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text(stringResource(R.string.env_var_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newValue,
                            onValueChange = { newValue = it },
                            label = { Text(stringResource(R.string.env_var_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.cancel),
                                modifier = Modifier
                                    .clickable {
                                        showAdd = false
                                        newKey = ""
                                        newValue = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.add),
                                modifier = Modifier
                                    .clickable {
                                        viewModel.add(newKey.trim(), newValue)
                                        showAdd = false
                                        newKey = ""
                                        newValue = ""
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (vars.isEmpty() && !showAdd) {
                Spacer(Modifier.height(48.dp))
                Text(
                    stringResource(R.string.env_vars_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.env_vars_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            LazyColumn {
                items(vars, key = { it.key }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (editingKey == item.key) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(item.key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editingValue,
                                    onValueChange = { editingValue = it },
                                    label = { Text(stringResource(R.string.env_var_value)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.cancel),
                                        modifier = Modifier
                                            .clickable { editingKey = null }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.save),
                                        modifier = Modifier
                                            .clickable {
                                                viewModel.update(item.key, editingValue)
                                                editingKey = null
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editingKey = item.key
                                        editingValue = item.value
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(
                                        item.value.ifBlank { "(空)" },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = if (item.value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.delete(item.key) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
