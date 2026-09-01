package dev.pantherale0.mc40.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.net.HaUrls
import dev.pantherale0.mc40.net.HttpClients
import okhttp3.Request
import java.util.concurrent.Executors

object ImageLoader {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    fun load(url: String, callback: (Bitmap?) -> Unit) {
        val token = ++generation
        if (url.isBlank()) {
            main.post { callback(null) }
            return
        }
        io.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (token != generation) return@execute
            main.post {
                if (token == generation) callback(bitmap)
            }
        }
    }

    fun cancel() {
        generation++
    }

    private fun download(url: String): Bitmap? {
        if (!HaUrls.isFetchable(url)) {
            android.util.Log.w(Mc40App.TAG, "Rejected image URL scheme")
            return null
        }
        val request = Request.Builder().url(url).build()
        HttpClients.okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w(Mc40App.TAG, "Image HTTP ${response.code}")
                return null
            }
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_BYTES) {
                android.util.Log.w(Mc40App.TAG, "Image too large ($length)")
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            if (bytes.size > MAX_BYTES) {
                android.util.Log.w(Mc40App.TAG, "Image too large (${bytes.size})")
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, 400)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxEdge && h / 2 >= maxEdge) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private const val MAX_BYTES = 1_000_000
}
