package tech.musxi.accounts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.musxi.core.update.UpdateException
import tech.musxi.core.update.UpdateFailure

class UpdateHttpClientTest {
    @Test
    fun `oversized update is rejected before the extra byte reaches output`() {
        val output = ByteArrayOutputStream()

        val error = assertThrows(UpdateException::class.java) {
            copyUpdateBytes(
                OneByteInputStream("12345".toByteArray()),
                output,
                limit = 4,
                expectedHash = null,
                cancelled = AtomicBoolean(),
                progress = {},
            )
        }

        assertEquals(UpdateFailure.SIZE_MISMATCH, error.reason)
        assertEquals("1234", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `short content fails size check before hash acceptance`() {
        val bytes = "short".toByteArray()
        val error = assertThrows(UpdateException::class.java) {
            copyUpdateBytes(
                ByteArrayInputStream(bytes),
                ByteArrayOutputStream(),
                limit = 10,
                expectedHash = sha256(bytes),
                cancelled = AtomicBoolean(),
                progress = {},
            )
        }

        assertEquals(UpdateFailure.SIZE_MISMATCH, error.reason)
    }

    @Test
    fun `bad hash is reported separately from size`() {
        val error = assertThrows(UpdateException::class.java) {
            copyUpdateBytes(
                ByteArrayInputStream("apk".toByteArray()),
                ByteArrayOutputStream(),
                limit = 3,
                expectedHash = "0".repeat(64),
                cancelled = AtomicBoolean(),
                progress = {},
            )
        }

        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, error.reason)
    }

    @Test
    fun `cancelled transfer never reads or writes`() {
        val input = TrackingInputStream("data".toByteArray())
        val output = ByteArrayOutputStream()

        val error = assertThrows(UpdateException::class.java) {
            copyUpdateBytes(input, output, 10, null, AtomicBoolean(true), {})
        }

        assertEquals(UpdateFailure.CANCELLED, error.reason)
        assertEquals(0, input.readCount)
        assertEquals(0, output.size())
    }

    @Test
    fun `output failure maps to storage`() {
        val error = assertThrows(UpdateException::class.java) {
            copyUpdateBytes(
                ByteArrayInputStream("data".toByteArray()),
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk full")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
                },
                4,
                null,
                AtomicBoolean(),
                {},
            )
        }

        assertEquals(UpdateFailure.STORAGE, error.reason)
    }

    @Test
    fun `official HTTPS redirect is followed without enabling automatic redirects`() {
        val connections = ArrayDeque(
            listOf(
                FakeConnection(
                    302,
                    mapOf("Location" to "https://release-assets.githubusercontent.com/asset/body?sig=test"),
                    ByteArray(0),
                ),
                FakeConnection(200, emptyMap(), "ok".toByteArray()),
            ),
        )
        val opened = mutableListOf<String>()
        val client = UpdateHttpClient { url ->
            opened += url.toString()
            connections.removeFirst()
        }

        assertEquals("ok", client.read(assetUrl, 2).toString(Charsets.UTF_8))
        assertEquals(2, opened.size)
        assertEquals("https", URL(opened.last()).protocol)
        assertEquals("release-assets.githubusercontent.com", URL(opened.last()).host)
    }

    @Test
    fun `unsafe redirect target is rejected before opening the next connection`() {
        val opened = mutableListOf<String>()
        val client = UpdateHttpClient { url ->
            opened += url.toString()
            FakeConnection(302, mapOf("Location" to "https://evil.example/app.apk"), ByteArray(0))
        }

        val error = assertThrows(UpdateException::class.java) { client.read(assetUrl, 10) }

        assertEquals(UpdateFailure.UNSAFE_REDIRECT, error.reason)
        assertEquals(listOf(assetUrl), opened)
        assertFalse(opened.any { it.contains("evil.example") })
    }

    @Test
    fun `redirect chain stops at three before opening a fourth target`() {
        val opened = mutableListOf<String>()
        val client = UpdateHttpClient { url ->
            opened += url.toString()
            FakeConnection(302, mapOf("Location" to assetUrl), ByteArray(0))
        }

        val error = assertThrows(UpdateException::class.java) { client.read(assetUrl, 10) }

        assertEquals(UpdateFailure.UNSAFE_REDIRECT, error.reason)
        assertEquals(4, opened.size)
        assertTrue(opened.all { URL(it).host == "github.com" && URL(it).protocol == "https" })
    }

    @Test
    fun `429 exposes Retry-After through rate limit signal`() {
        val client = UpdateHttpClient {
            FakeConnection(429, mapOf("Retry-After" to "37"), ByteArray(0))
        }

        val error = assertThrows(UpdateRateLimit::class.java) { client.read(UpdateHttpClient.RELEASES_URL, 10) }

        assertEquals(37L, error.seconds)
    }

    private class FakeConnection(
        private val status: Int,
        private val headers: Map<String, String>,
        private val body: ByteArray,
    ) : HttpURLConnection(URL("https://github.com")) {
        private val requestHeaders = mutableMapOf<String, String>()

        fun setHeader(name: String, value: String) {
            requestHeaders[name] = value
        }

        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun getContentLengthLong(): Long = if (body.isEmpty()) -1L else body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun setRequestProperty(key: String?, value: String?) {
            requestHeaders[key ?: return] = value.orEmpty()
        }
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readCount: Int = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readCount++
            return super.read(b, off, len)
        }
    }

    private class OneByteInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, 1))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val assetUrl =
            "https://github.com/qwt1011/Musxi-Player-Android/releases/download/v0.2.0/Musxi-Player-0.2.0.apk"
    }
}
