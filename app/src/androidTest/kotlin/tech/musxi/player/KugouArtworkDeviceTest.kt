package tech.musxi.player

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

/** Read-only CDN inspection: keep the exact artwork host and vary only its size path segment. */
class KugouArtworkDeviceTest {
    @Test fun inspectSupportedCoverSizes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusxiApplication
        val saved = CompletableFuture<tech.musxi.core.playback.PlaybackJournal>()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 35000
        fun read() {
            val identity = app.accounts.playbackIdentity(Platform.KUGOU)
            if (identity != null) app.accounts.playbackJournal(Platform.KUGOU, identity) {
                it.fold(saved::complete, saved::completeExceptionally)
            } else if (System.currentTimeMillis() < deadline) main.postDelayed({ read() }, 250)
            else saved.completeExceptionally(IllegalStateException("account_unavailable"))
        }
        main.post { read() }
        val cover = saved.get(40, TimeUnit.SECONDS).queue?.songs?.firstNotNullOfOrNull { it.coverUrl }
            ?: error("No saved Kugou artwork")
        val pathSize = Regex("/(?:100|150|240|400|480|800)/")
        assertTrue("Artwork has no recognized size segment", pathSize.containsMatchIn(cover))
        val report = JSONArray()
        for (size in listOf(400, 480, 800)) {
            val row = JSONObject().put("requestedSize", size)
            try {
                val url = URL(cover.replaceFirst(pathSize, "/$size/"))
                assertEquals(URL(cover).host, url.host)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
                try {
                    row.put("status", connection.responseCode)
                    if (connection.responseCode == 200) {
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val block = ByteArray(8192)
                            while (true) {
                                val count = input.read(block)
                                if (count < 0) break
                                check(out.size() + count <= 8 * 1024 * 1024)
                                out.write(block, 0, count)
                            }
                            out.toByteArray()
                        }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        row.put("width", options.outWidth).put("height", options.outHeight).put("bytes", bytes.size)
                    }
                } finally { connection.disconnect() }
            } catch (error: Exception) { row.put("error", error.javaClass.simpleName) }
            report.put(row)
        }
        File(app.filesDir, "batch4-kugou-artwork.json").writeText(report.toString(2))
        assertTrue((0 until report.length()).any { report.getJSONObject(it).optInt("width") >= 400 })
    }
}
