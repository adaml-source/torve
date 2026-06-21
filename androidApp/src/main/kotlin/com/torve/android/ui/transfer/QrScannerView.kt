package com.torve.android.ui.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Detect whether the device has any camera at all. TV form-factor builds
 * (Android TV / Fire TV) typically don't, so the credential-transfer
 * scan UI hides the scan surface and the screen reverts to paste-only.
 */
fun deviceHasAnyCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * Whether the CAMERA runtime permission is currently granted.
 */
fun cameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * CameraX preview with ML Kit barcode analysis. Calls [onQrDetected]
 * with the raw QR-code text the first time a barcode is recognized;
 * the caller is expected to flip a "scanned" flag and stop calling
 * this composable.
 *
 * Requires the CAMERA permission to be granted before invocation.
 * Holds zero retained state — disposing the composable releases the
 * camera and shuts down the analyzer thread.
 */
@Composable
fun QrScannerView(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onDetected by rememberUpdatedState(onQrDetected)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var alreadyDelivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            runCatching { scanner.close() }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || alreadyDelivered) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(
                        media,
                        proxy.imageInfo.rotationDegrees,
                    )
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val first = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (first != null && !alreadyDelivered) {
                                alreadyDelivered = true
                                onDetected(first)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                cameraProvider.unbindAll()
                runCatching {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * Wraps [QrScannerView] with Compose-side runtime permission handling.
 *
 *   - If [deviceHasAnyCamera] is false: invokes [onUnavailable] once and
 *     renders nothing.
 *   - If permission is denied: invokes [onUnavailable] with a "denied"
 *     reason; caller surfaces the manual paste field as primary action.
 *   - If permission is granted: renders the live preview.
 *
 * The caller is expected to keep the manual paste flow always visible
 * regardless of the scanner state — it's a primary equal-weight option,
 * not a fallback.
 */
@Composable
fun QrScannerWithPermission(
    onQrDetected: (String) -> Unit,
    onUnavailable: (reason: ScannerUnavailable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (!deviceHasAnyCamera(context)) {
        LaunchedEffect(Unit) { onUnavailable(ScannerUnavailable.NoCamera) }
        return
    }

    var permissionGranted by remember { mutableStateOf(cameraPermissionGranted(context)) }
    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) onUnavailable(ScannerUnavailable.PermissionDenied)
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted && !permissionRequested) {
            permissionRequested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (permissionGranted) {
        QrScannerView(onQrDetected = onQrDetected, modifier = modifier)
    }
}

sealed interface ScannerUnavailable {
    data object NoCamera : ScannerUnavailable
    data object PermissionDenied : ScannerUnavailable
}
