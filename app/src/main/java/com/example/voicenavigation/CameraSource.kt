package com.example.voicenavigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.corsight.vision.ImageSource
import java.util.concurrent.ExecutorService

class CameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val executor: ExecutorService
) : ImageSource {

    override val displayName: String = "手机相机"

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var onFrame: ((Bitmap, Int) -> Unit)? = null
    @Volatile private var running = false

    fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun start(onFrame: (Bitmap, Int) -> Unit): Boolean {
        if (running) return false
        if (!allPermissionsGranted()) return false
        this.onFrame = onFrame
        startCamera()
        return true
    }

    override fun stop() {
        running = false
        onFrame = null
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    override val isRunning: Boolean get() = running

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                running = true
            } catch (exc: Exception) {
                Log.e("CameraSource", "Use case binding failed", exc)
                return@addListener
            }

            imageAnalysis?.setAnalyzer(executor) { imageProxy ->
                val rotation = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()
                if (running) {
                    onFrame?.invoke(bitmap, rotation)
                }
                imageProxy.close()
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
