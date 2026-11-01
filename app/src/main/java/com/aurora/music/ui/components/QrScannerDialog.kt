package com.aurora.music.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配对二维码扫描器：CameraX 预览 + ZXing 逐帧解码。
 * 识别到内容后通过 [onResult] 回调，由调用方决定是否立即导入。
 */
@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ios5Colors.CellBg)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("扫描配对二维码", color = Ios5Colors.TextPrimary, fontSize = 16.sp)
            if (!granted) {
                Text(
                    "需要相机权限才能扫码。也可以关闭后用下方输入框粘贴配对字符串。",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 12.sp,
                )
                Ios5ActionRow("允许相机权限") {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } else {
                CameraPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    onResult = onResult,
                )
                Text(
                    "对准 NAS 面板或远程配对字符串的二维码",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Ios5ActionRow("关闭") { onDismiss() }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val consumed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val listener = Runnable {
            runCatching {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val reader = MultiFormatReader()
                analysis.setAnalyzer(executor) { image ->
                    if (consumed.get()) {
                        image.close()
                        return@setAnalyzer
                    }
                    val text = runCatching { decodeQr(reader, image) }.getOrNull()
                    image.close()
                    if (!text.isNullOrBlank() && consumed.compareAndSet(false, true)) {
                        previewView.post { onResult(text) }
                    }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val data = if (pixelStride == 1 && rowStride == width) {
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } else {
        val out = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val need = minOf(rowStride, buffer.remaining())
            if (need <= 0) break
            buffer.get(row, 0, need)
            for (x in 0 until width) {
                val index = x * pixelStride
                if (index < need) out[y * width + x] = row[index]
            }
        }
        out
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    reader.reset()
    return reader.decodeWithState(bitmap)?.text
}
