package com.lin.hippyagent.ui.settings.general

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.i18n.LanguageManager
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val langManager = remember { LanguageManager(ctx) }
    var selected by remember { mutableStateOf(langManager.getCurrentLanguage()) }
    val langs = remember { langManager.getSupportedLanguages() }
    Scaffold(topBar = { HippyTopBar(title = "语言", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)); Text("选择语言", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            items(langs, key = { it.code }) { lang ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp).clickable {
                    selected = lang.code
                    langManager.setLanguage(lang.code)
                    (ctx as? Activity)?.let { activity ->
                        activity.recreate()
                    }
                },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${lang.name} (${lang.nativeName})", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                        if (selected == lang.code) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

