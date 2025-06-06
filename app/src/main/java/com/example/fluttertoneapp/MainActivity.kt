package com.example.fluttertoneapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.niqdev.mjpeg.Mjpeg
import com.github.niqdev.mjpeg.MjpegSurfaceView
import com.github.niqdev.mjpeg.DisplayMode
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

class MainActivity : AppCompatActivity() {

    private lateinit var mjpegView: MjpegSurfaceView
    private val url = "http://webcam01.ecn.purdue.edu/mjpg/video.mjpg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mjpegView = findViewById(R.id.mjpegView)
        mjpegView.holder.addCallback(mjpegView)
        mjpegView.setDisplayMode(DisplayMode.BEST_FIT)
        mjpegView.showFps(false)

        // on demande à Mjpeg d'ouvrir le flux en IO, puis de livrer le stream sur le main‐thread
        Mjpeg.newInstance()
            .open(url)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ stream ->
                // ici stream est un MjpegInputStream
                mjpegView.setSource(stream)
            },
                { error ->
                    error.printStackTrace()
                })
    }

    override fun onPause() {
        super.onPause()
        if (::mjpegView.isInitialized) mjpegView.clearStream()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mjpegView.isInitialized) mjpegView.clearStream()
    }
}