package com.example.fluttertoneapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Size
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var cameraExecutor: ExecutorService
    @Volatile
    private var latestFrame: Bitmap? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            finish()
        }
    }

    private var mjpegServer: MJPEGStreamServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.imageView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        checkCameraPermission()

        // Lancer le serveur MJPEG
        startMJPEGServer()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /** Démarre la caméra avec CameraX et prépare l’analyse d’image */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, CameraFrameAnalyzer())

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analyseur : met à jour la dernière frame */
    inner class CameraFrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val bitmap = imageProxy.toBitmap()
            latestFrame = bitmap
            runOnUiThread {
                imageView.setImageBitmap(bitmap)
            }
            imageProxy.close()
        }
    }

    /** Conversion ImageProxy -> Bitmap (simplifié) */
    private fun ImageProxy.toBitmap(): Bitmap {
        // Pour de bien meilleures couleurs, convertir YUV → RGB correctement !
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Lancer le serveur MJPEG sur le port 8080 */
    private fun startMJPEGServer() {
        mjpegServer = MJPEGStreamServer { latestFrame }
        mjpegServer?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mjpegServer?.stop()
    }
}
