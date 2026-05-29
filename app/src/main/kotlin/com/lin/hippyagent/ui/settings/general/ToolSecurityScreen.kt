package com.lin.hippyagent.ui.settings.general

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSecurityScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var policy by remember { mutableIntStateOf(1) }
    var fileAccess by remember { mutableStateOf(true) }
    var networkAccess by remember { mutableStateOf(true) }
    var shellAccess by remember { mutableStateOf(false) }
    var smsAccess by remember { mutableStateOf(false) }
    Scaffold(topBar = { HippyTopBar(title = stringResource(R.string.tool_security_policy), showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.tool_security_execution_policy), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        listOf(0 to stringResource(R.string.tool_security_allow_all), 1 to stringResource(R.string.tool_security_require_approval), 2 to stringResource(R.string.tool_security_deny_all)).forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = policy == v, onClick = { policy = v }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                                Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        if (policy == 1) Text(stringResource(R.string.tool_security_manual_confirm), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
                    }
                }
            }
            item { Text(stringResource(R.string.tool_security_permission_control), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        SecRow(stringResource(R.string.tool_security_file_access), stringResource(R.string.tool_security_file_access_desc), fileAccess) { fileAccess = it }
                        SecRow(stringResource(R.string.tool_security_network_access), stringResource(R.string.tool_security_network_access_desc), networkAccess) { networkAccess = it }
                        SecRow(stringResource(R.string.tool_security_shell_access), stringResource(R.string.tool_security_shell_access_desc), shellAccess) { shellAccess = it }
                        SecRow(stringResource(R.string.tool_security_sms_access), stringResource(R.string.tool_security_sms_access_desc), smsAccess) { smsAccess = it }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SecRow(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}
