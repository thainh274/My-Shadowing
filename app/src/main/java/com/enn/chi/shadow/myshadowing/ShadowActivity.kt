package com.enn.chi.shadow.myshadowing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ShadowActivity : AppCompatActivity() {

    private lateinit var shadowImageView: ImageView
    private lateinit var cameraExecutor: ExecutorService

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraAndAI()
        } else {
            // Camera permission denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shadow)

        shadowImageView = findViewById(R.id.shadowImageView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        shadowImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndAI()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCameraAndAI() {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)
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
                        .addOnSuccessListener { result ->
                            val bitmap = result.foregroundBitmap
                            if (bitmap != null) {
                                frameCount++
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastFpsTimestamp >= 1000) {
                                    val fps = frameCount * 1000f / (currentTime - lastFpsTimestamp)
                                    Log.d("ShadowActivity", "Current FPS: ${String.format("%.2f", fps)}")
                                    frameCount = 0
                                    lastFpsTimestamp = currentTime
                                }
                                updateTexture(bitmap, rotation)
                            }
                        }
                        .addOnFailureListener { e ->
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            // Ensure ONLY Front Camera is used
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch(exc: Exception) {
                // Use case binding failed
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateTexture(bitmap: Bitmap, rotationDegrees: Int) {
        val viewWidth = shadowImageView.width.toFloat()
        val viewHeight = shadowImageView.height.toFloat()
        
        if (viewWidth == 0f || viewHeight == 0f) return

        // Image bounds after rotation
        val imageWidth = if (rotationDegrees % 180 == 0) bitmap.width.toFloat() else bitmap.height.toFloat()
        val imageHeight = if (rotationDegrees % 180 == 0) bitmap.height.toFloat() else bitmap.width.toFloat()

        // Calculate centerCrop scale
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val scale = Math.max(scaleX, scaleY)

        val matrix = Matrix()
        
        // 1. Move origin to center of bitmap
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        
        // 2. Rotate
        matrix.postRotate(rotationDegrees.toFloat())
        
        // 3. Mirror horizontally
        matrix.postScale(-1f, 1f)
        
        // 4. Scale to fit screen (centerCrop)
        matrix.postScale(scale, scale)
        
        // 5. Move back to center of view
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)

        // Update ImageView on Main Thread
        runOnUiThread {
            shadowImageView.scaleType = ImageView.ScaleType.MATRIX
            shadowImageView.imageMatrix = matrix
            shadowImageView.setImageBitmap(bitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}