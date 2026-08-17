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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraAndAI()
        } else {
            Log.e("ShadowActivity", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shadow)

        shadowImageView = findViewById(R.id.shadowImageView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // This makes any non-transparent pixel in the ImageView black
        // Since the parent background is white, we get a black shadow on a white background!
        shadowImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndAI()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCameraAndAI() {
        Log.d("ShadowActivity", "Starting Camera and AI Setup")
        // Use enableForegroundBitmap() to avoid stride and float buffer mismatch issues
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        Log.d("ShadowActivity", "Subject Segmenter Client Created")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            Log.d("ShadowActivity", "CameraProvider instance retrieved")
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            Log.d("ShadowActivity", "ImageAnalysis built")

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val image = InputImage.fromMediaImage(mediaImage, rotation)
                    
                    val startTime = System.currentTimeMillis()

                    segmenter.process(image)
                        .addOnSuccessListener { result ->
                            val bitmap = result.foregroundBitmap
                            if (bitmap != null) {
                                val segmentTime = System.currentTimeMillis() - startTime
                                Log.d("ShadowActivity", "AI Success: Bitmap received size=${bitmap.width}x${bitmap.height}, time=${segmentTime}ms")
                                updateTexture(bitmap)
                            } else {
                                Log.w("ShadowActivity", "AI Success but Bitmap is NULL")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("ShadowActivity", "AI processing failed", e)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    Log.w("ShadowActivity", "ImageProxy returned null mediaImage")
                    imageProxy.close()
                }
            }

            // Ensure ONLY Front Camera is used
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            Log.d("ShadowActivity", "CameraSelector built: ONLY LENS_FACING_FRONT")

            try {
                cameraProvider.unbindAll()
                Log.d("ShadowActivity", "Unbound previous use cases, binding ImageAnalysis to Front Camera...")
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                Log.d("ShadowActivity", "Camera Use Cases Successfully Bound!")
            } catch(exc: Exception) {
                Log.e("ShadowActivity", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateTexture(bitmap: Bitmap) {
        val renderStartTime = System.currentTimeMillis()

        // Mirror the bitmap horizontally so it acts like a mirror
        val matrix = Matrix()
        matrix.preScale(-1f, 1f)
        
        // Create mirrored bitmap
        val mirroredBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)

        val renderTime = System.currentTimeMillis() - renderStartTime
        Log.d("ShadowActivity", "Texture rendering completed in ${renderTime}ms")

        // Update ImageView on Main Thread
        runOnUiThread {
            shadowImageView.setImageBitmap(mirroredBitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}