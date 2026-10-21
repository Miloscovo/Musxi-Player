package tech.musxi.player

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy

/** Debug-only public cover download check; never accepts audio capabilities or account state. */
internal object ValidationArtworkProbe {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 5_000
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "validation-artwork").apply { isDaemon = true }
    }

    /** Caller supplies only production cover metadata and controls the number of checks. */
    fun check(platform: Platform, value: String?, result: (String) -> Unit) {
        io.execute {
            val status = try {
                if (value.isNullOrBlank()) {
                    "absent"
                } else {
                    val url = ArtworkPolicy.sanitize(platform, value)
                    if (url == null) "rejected" else download(url)
                }
            } catch (_: Exception) {
                "network"
            }
            main.post { result(status) }
        }
    }

    private fun download(url: String): String {
        // URLConnection may consult process-wide credential handlers. Fail closed rather
        // than borrowing or changing those handlers, even if another subsystem installs one.
        if (CookieHandler.getDefault() != null) {
            return "network"
        }
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "image/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            val code = connection.responseCode
            if (code in 300..399) return "redirect"
            if (code !in 200..299) return "http"
            if (connection.contentLengthLong > MAX_BYTES) return "too_large"

            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - total))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    total += count
                }
                // One sentinel byte distinguishes an exactly 2 MiB body from an oversized one.
                if (total == MAX_BYTES && input.read() != -1) return "too_large"
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            } catch (_: Exception) {
                return "not_image"
            }
            return if (bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                bounds.outMimeType?.startsWith("image/") == true
            ) "ok" else "not_image"
        } finally {
            connection.disconnect()
        }
    }
}
