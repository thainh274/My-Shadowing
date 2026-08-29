package com.enn.chi.shadow.myshadowing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "ShadowDebug"

class ShadowActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap by mutableStateOf<Bitmap?>(null)
    private var segFrameCount = 0
    private var segLastTime = System.currentTimeMillis()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraAndAI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "=== ShadowActivity onCreate (2D Phase 1) ===")

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
                    ShadowScene2D(currentBitmap)
                }
            }
        }
    }



    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCameraAndAI() {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            // Không dùng enableRawSizeMask() để ML Kit tự động xoay mask thành chiều dọc theo rotationDegrees
            .build()
        val segmenter = Segmentation.getClient(options)
        Log.d(TAG, "ML Kit segmenter initialized")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

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
                        .addOnSuccessListener { mask ->
                            val buffer = mask.buffer
                            val width = mask.width
                            val height = mask.height
                            
                            // Convert float confidence mask to ARGB_8888 bitmap
                            val pixels = IntArray(width * height)
                            buffer.rewind()
                            for (i in pixels.indices) {
                                val confidence = buffer.float
                                // 0xFF000000 is solid black, 0x00000000 is transparent
                                pixels[i] = if (confidence > 0.5f) 0xFF000000.toInt() else 0x00000000
                            }
                            
                            // Bitmap sẽ tự động có hướng dọc (portrait) vì ML Kit đã xoay theo rotation
                            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                            
                            segFrameCount++
                            val now = System.currentTimeMillis()
                            if (now - segLastTime >= 1000) {
                                Log.d(TAG, "[SEG] fps=$segFrameCount rotation=$rotation size=${width}x${height}")
                                segFrameCount = 0
                                segLastTime = now
                            }
                            currentBitmap = bitmap
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "[SEG] FAILED: ${e.message}")
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
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
                Log.d(TAG, "Camera bound successfully")
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun ShadowScene2D(bitmap: Bitmap?) {
    var renderFrameCount by remember { mutableStateOf(0) }
    var renderLastTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                renderFrameCount++
                val now = System.currentTimeMillis()
                if (now - renderLastTime >= 1000) {
                    Log.d(TAG, "[RENDER] fps=$renderFrameCount bitmap=${bitmap != null} size=${bitmap?.let { "${it.width}x${it.height}" } ?: "null"}")
                    renderFrameCount = 0
                    renderLastTime = now
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Shadow silhouette",
                contentScale = ContentScale.Crop,
                // The bitmap is already solid black from the segmentation mask processing
                colorFilter = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = -1f)
            )
        }
    }
}