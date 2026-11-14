package com.munchkin.app.ui.components

import android.Manifest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.munchkin.app.R
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonBackground
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSurface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/**
 * QR Code scanner composable using CameraX and ML Kit with single-scan gating,
 * main-thread callback dispatch, lifecycle resource cleanup, and visible error recovery.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrScanner(
    onQrCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeonBackground)
    ) {
        if (cameraPermission.status.isGranted) {
            val hasScanned = remember { AtomicBoolean(false) }
            val scanner = remember {
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                BarcodeScanning.getClient(options)
            }
            val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
            var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            var previewView by remember { mutableStateOf<PreviewView?>(null) }
            var cameraError by remember { mutableStateOf<String?>(null) }
            var retryTrigger by remember { mutableIntStateOf(0) }
            val defaultErrorText = stringResource(R.string.qr_camera_error_message)

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        cameraProvider?.unbindAll()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unbind camera provider", e)
                    }
                    try {
                        scanner.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to close barcode scanner", e)
                    }
                    analyzerExecutor.shutdown()
                }
            }

            LaunchedEffect(previewView, retryTrigger) {
                val activePreview = previewView ?: return@LaunchedEffect
                cameraError = null
                hasScanned.set(false)

                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(activePreview.surfaceProvider)
                        }

                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    if (hasScanned.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    processImage(
                                        imageProxy = imageProxy,
                                        scanner = scanner,
                                        onQrFound = { qrContent ->
                                            if (hasScanned.compareAndSet(false, true)) {
                                                mainExecutor.execute {
                                                    onQrCodeScanned(qrContent)
                                                }
                                            }
                                        },
                                        onFailure = { error ->
                                            Log.w(TAG, "Frame scan failed", error)
                                        }
                                    )
                                }
                            }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera initialization failed", e)
                        val msg = e.localizedMessage?.takeIf { it.isNotBlank() } ?: defaultErrorText
                        cameraError = msg
                        mainExecutor.execute { onError(msg) }
                    }
                }, mainExecutor)
            }

            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { previewView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(NeonBackground.copy(alpha = 0.72f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = NeonGray100
                    )
                }

                val currentError = cameraError
                if (currentError != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = NeonSurface,
                        border = BorderStroke(1.dp, DungeonBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = NeonError,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = stringResource(R.string.qr_camera_error_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NeonGray100,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = currentError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGray200,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onClose,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    border = BorderStroke(1.dp, DungeonBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                                ) {
                                    Text(text = stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = { retryTrigger++ },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonPrimary,
                                        contentColor = NeonGray100
                                    )
                                ) {
                                    Text(text = stringResource(R.string.reconnect_retry))
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .align(Alignment.Center)
                            .border(
                                width = 2.dp,
                                color = NeonSecondary,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .background(
                                color = Color.Transparent,
                                shape = RoundedCornerShape(24.dp)
                            )
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 84.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = NeonBackground.copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, DungeonBorder)
                    ) {
                        Text(
                            text = stringResource(R.string.qr_point_camera),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = NeonGray100,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.qr_camera_permission_rationale),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NeonGray100,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { cameraPermission.launchPermissionRequest() },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonPrimary,
                        contentColor = NeonGray100
                    )
                ) {
                    Text(text = stringResource(R.string.grant_permission))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = NeonGray300
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImage(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onQrFound: (String) -> Unit,
    onFailure: (Exception) -> Unit
) {
    val mediaImage = imageProxy.image ?: run {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                val raw = barcode.rawValue
                if (!raw.isNullOrBlank()) {
                    onQrFound(raw)
                    return@addOnSuccessListener
                }
            }
        }
        .addOnFailureListener { e ->
            onFailure(e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
