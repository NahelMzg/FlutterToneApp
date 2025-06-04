package com.example.fluttertoneapp
import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MJPEGStreamServer(private val bitmapProvider: () -> Bitmap?) : NanoHTTPD(8080) {
    override fun serve(session: IHTTPSession?): Response {
        val boundary = "frame"
        val responseStream = MJPEGStream(boundary, bitmapProvider)
        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            responseStream
        )
    }

    class MJPEGStream(
        private val boundary: String,
        private val bitmapProvider: () -> Bitmap?
    ) : InputStream() {
        private var buf: ByteArray = ByteArray(0)
        private var idx = 0

        override fun read(): Int {
            if (idx >= buf.size) {
                val bmp = bitmapProvider() ?: return -1
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val jpeg = stream.toByteArray()
                val header =
                    "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                buf = header.toByteArray() + jpeg + "\r\n".toByteArray()
                idx = 0
                Thread.sleep(50) // Régule le flux (~20 images/sec)
            }
            return buf[idx++].toInt() and 0xFF
        }
    }
}