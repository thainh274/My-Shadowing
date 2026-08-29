package com.enn.chi.shadow.myshadowing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "ShadowDebug"

class ShadowActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    
    // TỐI ƯU 1: Zero-copy Memory (Giải quyết GC Pressure, giữ FPS ổn định)
    private var reusableBitmap: Bitmap? = null
    private var reusablePixels: IntArray? = null
    
    // TỐI ƯU 2: Trạng thái ép Jetpack Compose vẽ lại (Recompose)
    // Bằng cách bọc lại ImageBitmap mới mỗi frame, Compose sẽ tự động nhận diện thay đổi
    private var currentImageBitmap by mutableStateOf<ImageBitmap?>(null)

    private var segFrameCount = 0
    private var segLastTime = System.currentTimeMillis()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startCameraAndAI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndAI()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    ShadowScene2D(currentImageBitmap)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCameraAndAI() {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Giữ phân giải 480x360 để tối đa FPS cho Camera/AI. 
            // Việc làm nét hình sẽ được xử lý bằng GPU ở bước Render.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val image = InputImage.fromMediaImage(mediaImage, rotation)

                    segmenter.process(image)
                        // TỐI ƯU 3: Ép chạy trên cameraExecutor để tránh giật lag UI Main Thread
                        .addOnSuccessListener(cameraExecutor) { mask ->
                            val buffer = mask.buffer
                            val width = mask.width
                            val height = mask.height
                            val totalPixels = width * height
                            
                            // Chỉ cấp phát bộ nhớ đúng 1 lần duy nhất (Khử hoàn toàn tạo rác GC)
                            if (reusablePixels == null || reusablePixels!!.size != totalPixels) {
                                reusablePixels = IntArray(totalPixels)
                                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            }
                            
                            val pixels = reusablePixels!!
                            buffer.rewind()
                            
                            // TỐI ƯU 4: Soft Edges - Khử dứt gãy viền và giữ lại các cử động nhanh
                            for (i in 0 until totalPixels) {
                                val confidence = buffer.float
                                // Ép confidence (0..1) sang Alpha (0..255)
                                val alpha = (confidence * 255f).toInt().coerceIn(0, 255)
                                // Màu Đen tuyệt đối (RGB=0). Đẩy Alpha vào 8 bit cao nhất.
                                pixels[i] = alpha shl 24 
                            }
                            
                            // Ghi đè pixel vào Bitmap cũ cực nhanh
                            reusableBitmap!!.setPixels(pixels, 0, width, 0, 0, width, height)
                            
                            segFrameCount++
                            val now = System.currentTimeMillis()
                            if (now - segLastTime >= 1000) {
                                Log.d(TAG, "[SEG] fps=$segFrameCount rotation=$rotation size=${width}x${height}")
                                segFrameCount = 0
                                segLastTime = now
                            }
                            
                            // Ép Compose UI vẽ lại khung hình mới bằng cách cấp phát wrapper ImageBitmap mới. 
                            // Wrapper này cực kì nhẹ (chỉ tốn vài byte reference) nên không lo rác GC.
                            currentImageBitmap = reusableBitmap!!.asImageBitmap()
                        }
                        .addOnFailureListener { e -> Log.e(TAG, "[SEG] FAILED: ${e.message}") }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        reusableBitmap?.recycle()
    }
}

@Composable
fun ShadowScene2D(imageBitmap: ImageBitmap?) {
    var renderFrameCount by remember { mutableIntStateOf(0) }
    var renderLastTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                renderFrameCount++
                val now = System.currentTimeMillis()
                if (now - renderLastTime >= 1000) {
                    Log.d(TAG, "[RENDER] fps=$renderFrameCount")
                    renderFrameCount = 0
                    renderLastTime = now
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (imageBitmap != null) {
            // TỐI ƯU 5: Dùng Canvas nội suy phần cứng GPU (High Quality)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = -1f) // Lật gương
            ) {
                drawImage(
                    image = imageBitmap,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.High // Khử răng cưa và mảng ô vuông cực hiệu quả
                )
            }
        }
    }
}