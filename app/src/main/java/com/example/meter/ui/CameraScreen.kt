package com.example.meter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.meter.camera.OverlayMapping
import com.example.meter.data.RoiStore
import com.example.meter.model.Roi
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Кадр анализа — 4:3. Превью настраиваем на тот же аспект, чтобы нормировка совпадала.
private const val IMAGE_WIDTH = 640
private const val IMAGE_HEIGHT = 480

private val ACCENT = Color(0xFFFF5A5A)
private val HUD_YELLOW = Color(0xFFFFD24A)
private val HUD_GREEN = Color(0xFF9BE59B)
private val TEXT_LIGHT = Color(0xFFEDEDED)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraContent()
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Нужен доступ к камере.\nНажмите, чтобы запросить снова.",
                color = TEXT_LIGHT,
                modifier = Modifier
                    .padding(24.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { launcher.launch(Manifest.permission.CAMERA) }
                    },
            )
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { RoiStore(context) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var roi by remember { mutableStateOf(store.load()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var rotationDegrees by remember { mutableStateOf(90) }
    var fps by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Запуск камеры…") }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }
    val frameCounter = remember { AtomicInteger(0) }

    // Фактический FPS — раз в секунду.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            fps = frameCounter.getAndSet(0)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val providerRef = arrayOfNulls<ProcessCameraProvider>(1)

        providerFuture.addListener({
            val provider = providerFuture.get()
            providerRef[0] = provider

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Жёсткая частота: без этого в темноте автоматика уйдёт на 15 fps.
            val fpsRange = chooseFpsRange(provider)
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Extender вешаем на БИЛДЕР ImageAnalysis, а не на собранный use case.
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                fpsRange,
            )
            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(analysisExecutor) { image ->
                val rot = image.imageInfo.rotationDegrees
                if (rot != rotationDegrees) rotationDegrees = rot
                frameCounter.incrementAndGet()
                image.close()
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera.cameraControl.enableTorch(false)
                cameraControl.value = camera.cameraControl
                status = "Камера готова · FPS ${fpsRange.lower}–${fpsRange.upper}"
            } catch (t: Throwable) {
                status = "Ошибка камеры: ${t.message}"
            }
        }, mainExecutor)

        onDispose {
            providerRef[0]?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = it },
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Рамка ROI
        if (viewSize != IntSize.Zero) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val mapping = OverlayMapping(
                    size.width, size.height, IMAGE_WIDTH, IMAGE_HEIGHT, rotationDegrees,
                )
                roi?.let { r ->
                    val rect = mapping.bufferRectToView(r.u0, r.v0, r.u1, r.v1)
                    drawRect(
                        color = ACCENT,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    drawLine(ACCENT, Offset(cx - 12f, cy), Offset(cx + 12f, cy), 1.dp.toPx())
                    drawLine(ACCENT, Offset(cx, cy - 12f), Offset(cx, cy + 12f), 1.dp.toPx())
                }
            }
        }

        // Слой приёма тапов: ставит ROI и фиксирует AF/AE
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rotationDegrees, viewSize) {
                    detectTapGestures { offset ->
                        if (viewSize == IntSize.Zero) return@detectTapGestures
                        val mapping = OverlayMapping(
                            viewSize.width.toFloat(),
                            viewSize.height.toFloat(),
                            IMAGE_WIDTH,
                            IMAGE_HEIGHT,
                            rotationDegrees,
                        )
                        val uv = mapping.viewToBuffer(offset.x, offset.y)
                        val newRoi = Roi(uv[0], uv[1])
                        roi = newRoi
                        store.save(newRoi)
                        focusAndLock(
                            previewView, cameraControl.value, offset.x, offset.y, mainExecutor,
                        ) { ok ->
                            status = if (ok) "AF/AE зафиксированы" else "Фокус не удался"
                        }
                    }
                },
        )

        // Отладочный HUD (на шаге 2 уберём)
        Column(modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Text(
                roi?.let { "ROI: u=%.3f v=%.3f".format(it.centerU, it.centerV) } ?: "ROI: не задан",
                color = HUD_YELLOW, fontSize = 12.sp,
            )
            Text(
                roi?.let {
                    "буфер: %.0f, %.0f px".format(it.centerU * IMAGE_WIDTH, it.centerV * IMAGE_HEIGHT)
                } ?: "буфер: —",
                color = HUD_YELLOW, fontSize = 12.sp,
            )
            Text("поворот: $rotationDegrees° · fps: $fps", color = HUD_GREEN, fontSize = 12.sp)
            Text(status, color = TEXT_LIGHT, fontSize = 12.sp)
        }

        Text(
            "Тапните по ВЕРХНЕМУ светодиоду счётчика",
            color = TEXT_LIGHT,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/** Тап по экрану -> точка замера + блокировка автофокуса и автоэкспозиции. */
private fun focusAndLock(
    previewView: PreviewView,
    control: CameraControl?,
    viewX: Float,
    viewY: Float,
    executor: Executor,
    onResult: (Boolean) -> Unit,
) {
    val cameraControl = control ?: run { onResult(false); return }
    try {
        val point = previewView.meteringPointFactory.createPoint(viewX, viewY)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()

        val future = cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = try {
                future.get().isFocusSuccessful
            } catch (t: Throwable) {
                false
            }
            onResult(ok)
        }, executor)
    } catch (t: Throwable) {
        onResult(false)
    }
}

/**
 * Выбираем диапазон FPS с верхней границей 30 (нам нужны жёсткие 30 кадров/с).
 * Если ровно [30,30] не поддерживается — берём ближайший доступный.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun chooseFpsRange(
    provider: ProcessCameraProvider,
): Range<Int> {
    return try {
        val infos = provider.getAvailableCameraInfos()
        // Выбираем тыловую камеру по LENS_FACING_BACK (надёжнее, чем getCameraInfo).
        val backInfo = infos.firstOrNull { info ->
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: infos.firstOrNull()

        val available = backInfo?.let { info ->
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
        }.orEmpty()

        available.filter { it.upper == 30 }.maxByOrNull { it.upper - it.lower }
            ?: available.minByOrNull { kotlin.math.abs(it.upper - 30) }
            ?: Range(30, 30)
    } catch (t: Throwable) {
        Range(30, 30)
    }
}
