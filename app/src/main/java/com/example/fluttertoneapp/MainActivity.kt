package com.example.fluttertoneapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Size
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.fluttertoneapp.mjpeg.MjpegInputStream
import com.example.fluttertoneapp.mjpeg.MjpegView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var cameraExecutor: ExecutorService
    @Volatile
    private var latestFrame: Bitmap? = null

    private lateinit var mjpegView: MjpegView

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

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ----- Caméra locale -----
        imageView = findViewById(R.id.imageView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        checkCameraPermission()
        startMJPEGServer()

        // ----- Affichage du flux MJPEG distant traité (PC) -----
        mjpegView = findViewById(R.id.mjpegView)
        val mjpegUrl = "http://webcam01.ecn.purdue.edu/mjpg/video.mjpg"

        Thread {
            val inputStream = MjpegInputStream.read(mjpegUrl)
            runOnUiThread {
                if (inputStream != null) {
                    mjpegView.setSource(inputStream)
                    mjpegView.setDisplayMode(MjpegView.SIZE_BEST_FIT)
                    mjpegView.showFps(false) // Si tu veux l'overlay FPS
                }
            }
        }.start()
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

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun startMJPEGServer() {
        mjpegServer = MJPEGStreamServer { latestFrame }
        mjpegServer?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mjpegServer?.stop()
    }

    override fun onPause() {
        super.onPause()
        // IMPORTANT: Stoppe l'affichage du flux distant pour libérer les ressources réseau/CPU
        if (::mjpegView.isInitialized) {
            mjpegView.stopPlayback()
        }
    }
}