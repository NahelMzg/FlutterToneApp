
package com.example.fluttertoneapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.github.niqdev.mjpeg.Mjpeg
import com.github.niqdev.mjpeg.MjpegSurfaceView
import com.github.niqdev.mjpeg.DisplayMode
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import fi.iki.elonen.NanoHTTPD
import android.media.MediaPlayer
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy


class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mjpegView: MjpegSurfaceView
    private val url = "http://192.168.200.102:8090/video_feed"  // IP de l'ordinateur sur le réseau local
    private var soundHttpServer: SoundHttpServer? = null

    @Volatile
    private var latestFrame: Bitmap? = null
    private var mjpegServer: MJPEGStreamServer? = null

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Démarrer la caméra locale pour l'envoi vers le PC
        checkCameraPermission()

        // 2. Démarrer le serveur MJPEG (port 8080) pour que le PC puisse récupérer notre flux
        startMJPEGServer()

        // 2bis. Démarrer le serveur HTTP pour jouer les sons
        soundHttpServer = SoundHttpServer(this, 8081)
        soundHttpServer?.start()

        // 3. Configurer l'affichage du flux traité provenant du PC (port 8090)
        setupProcessedVideoDisplay()






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

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),                      // résolution préférée
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER // règle de repli : plus bas si indispo
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()


            imageAnalysis.setAnalyzer(cameraExecutor, CameraFrameAnalyzer())

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    inner class CameraFrameAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val bitmap = imageProxy.toBitmap()
            latestFrame = bitmap  // Utilisé par le serveur MJPEG
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

    private fun setupProcessedVideoDisplay() {
        mjpegView = findViewById(R.id.mjpegView)

        mjpegView.holder.addCallback(mjpegView)
        mjpegView.setDisplayMode(DisplayMode.BEST_FIT)
        mjpegView.showFps(false)


        // Attendre que le serveur démarre et que le PC commence le traitement
        Thread {
            Thread.sleep(3000)  // Attendre 3 secondes
            runOnUiThread {
                connectToProcessedStream()
            }
        }.start()
    }

    private fun connectToProcessedStream() {
        Mjpeg.newInstance()
            .open(url)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ stream ->
                mjpegView.setSource(stream)
            },
                { error ->
                    error.printStackTrace()
                    // Réessayer après 5 secondes en cas d'échec
                    Thread {
                        Thread.sleep(5000)
                        runOnUiThread {
                            connectToProcessedStream()
                        }
                    }.start()
                })
    }

    override fun onPause() {
        super.onPause()
        if (::mjpegView.isInitialized) {
            mjpegView.clearStream()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mjpegServer?.stop()
        if (::mjpegView.isInitialized) {
            mjpegView.clearStream()
        }

        soundHttpServer?.stop()

    }

    class SoundHttpServer(val context: Context, port: Int = 8081) : NanoHTTPD(port) {
        private val soundMap = mapOf(
            "open_hand" to R.raw.open_hand,
            "fist" to R.raw.fist,
            "victory" to R.raw.victory,
            "like" to R.raw.like,
            "point" to R.raw.point
        )

        // Stocke les lecteurs audio actifs pour éviter le garbage collection prématuré
        private val activePlayers = mutableListOf<MediaPlayer>()

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.POST && session.uri == "/play_sound") {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val postData = files["postData"] ?: ""
                val soundName = postData.trim()
                soundMap[soundName]?.let { soundRes ->
                    // Crée un MediaPlayer et conserve une référence
                    val mediaPlayer = MediaPlayer.create(context, soundRes)
                    activePlayers.add(mediaPlayer)
                    mediaPlayer.setOnCompletionListener {
                        it.release()
                        activePlayers.remove(it)
                    }
                    mediaPlayer.start()
                    return newFixedLengthResponse("Sound $soundName played")
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown sound")
            }
            return newFixedLengthResponse("OK")
        }
    }


}