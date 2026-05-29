package com.lin.hippyagent.ui.channel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.channel.qr.AppLauncher
import com.lin.hippyagent.core.channel.qr.QrAuthState
import com.lin.hippyagent.core.channel.qr.QrImageSaver
import com.lin.hippyagent.core.channel.qr.base64ToBitmap
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QrAuthScreen(
    channelId: String,
    channelName: String,
    viewModel: QrAuthViewModel,
    onBackClick: () -> Unit,
    onAuthSuccess: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.channel_qr_login, channelName),
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is QrAuthState.Idle -> {
                    Text(stringResource(R.string.channel_qr_start_hint, channelName))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.startQrLogin() }) {
                        Text(stringResource(R.string.channel_start_bind))
                    }
                }
                is QrAuthState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.channel_fetching_qr))
                }
                is QrAuthState.QrReady -> {
                    val bitmap = remember(s.qrcodeBase64) {
                        base64ToBitmap(s.qrcodeBase64)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.channel_qr_code_desc, channelName),
                            modifier = Modifier.size(250.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.channel_qr_decode_failed), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.channel_scan_qr_hint, channelName))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.channel_qr_expiry), style = MaterialTheme.typography.bodySmall)

                    if (AppLauncher.isAppInstalled(context, channelId)) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val savedUri = QrImageSaver.saveToGallery(context, s.qrcodeBase64)
                                    val launched = AppLauncher.launchScan(context, channelId)
                                    if (launched && savedUri != null) {
                                        snackbarHostState.showSnackbar(context.getString(R.string.channel_qr_saved_hint, channelName))
                                    } else if (!launched) {
                                        snackbarHostState.showSnackbar(context.getString(R.string.channel_app_not_installed, channelName))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.channel_open_app_scan, channelName))
                        }
                    }
                }
                is QrAuthState.Scanned -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.channel_scanned_confirm))
                }
                is QrAuthState.Success -> {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.channel_bind_success))
                    LaunchedEffect(s) {
                        delay(1500)
                        onAuthSuccess()
                    }
                }
                is QrAuthState.Error -> {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.retryable) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.startQrLogin() }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        }
    }
}
