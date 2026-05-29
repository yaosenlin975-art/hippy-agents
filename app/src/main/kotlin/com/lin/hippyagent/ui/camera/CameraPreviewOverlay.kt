package com.lin.hippyagent.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import com.lin.hippyagent.R

@Composable
fun CameraPreviewOverlay(
    isActive: Boolean,
    onFrameCaptured: (android.graphics.Bitmap) -> Unit
) {
    if (!isActive) return

    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    DisposableEffect(Unit) {
        onDispose {
            // 清理相机资源
        }
    }

    Box(
        modifier = Modifier.size(120.dp, 160.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize()
        )
        Text(stringResource(R.string.camera_preview), modifier = Modifier.align(Alignment.BottomCenter))
    }
}
