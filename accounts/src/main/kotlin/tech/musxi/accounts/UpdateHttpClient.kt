package tech.musxi.accounts

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import tech.musxi.core.update.*

/** No cookies, credentials, arbitrary hosts or automatic redirects. */
internal open class UpdateHttpClient(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { active?.disconnect() }
    open fun read(url: String, maxBytes: Long, cancelled: AtomicBoolean = AtomicBoolean()): ByteArray {
        val output = ByteArrayOutputStream()
        transfer(url, maxBytes, null, output, cancelled) {}
        return output.toByteArray()
    }
    open fun transfer(url: String, limit: Long, expectedHash: String?, output: OutputStream,
        cancelled: AtomicBoolean, progress: (Long) -> Unit) {
        var current = url
        var redirects = 0
        try {
            while (true) {
                if (cancelled.get()) throw UpdateException(UpdateFailure.CANCELLED)
                val api = current == RELEASES_URL
                if (!api && !OfficialUpdateSource.isRedirect(current)) throw UpdateException(UpdateFailure.UNSAFE_REDIRECT)
                val connection = connect(URL(current)); active = connection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                    connection.setRequestProperty("User-Agent", "Musxi-Android-Update")
                    connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status == 429 || (status == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                            connection.getHeaderField("Retry-After") != null))) {
                        val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400)
                            ?: connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let {
                                (it - System.currentTimeMillis() / 1000).coerceIn(1, 86_400)
                            } ?: 60L
                        throw UpdateRateLimit(seconds)
                    }
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (api || ++redirects > 3) throw UpdateException(UpdateFailure.UNSAFE_REDIRECT)
                        val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateFailure.UNSAFE_REDIRECT)
                        val target = runCatching { URI(current).resolve(location).toASCIIString() }.getOrNull()
                            ?: throw UpdateException(UpdateFailure.UNSAFE_REDIRECT)
                        if (!OfficialUpdateSource.isRedirect(target)) throw UpdateException(UpdateFailure.UNSAFE_REDIRECT)
                        current = target; continue
                    }
                    if (status != 200) throw UpdateException(UpdateFailure.NETWORK)
                    if (connection.contentLengthLong > limit || (expectedHash != null && connection.contentLengthLong >= 0 && connection.contentLengthLong != limit))
                        throw UpdateException(UpdateFailure.SIZE_MISMATCH)
                    connection.inputStream.use { copyUpdateBytes(it, output, limit, expectedHash, cancelled, progress) }
                    return
                } finally { connection.disconnect(); active = null }
            }
        } catch (_: SocketTimeoutException) {
            throw UpdateException(if (cancelled.get()) UpdateFailure.CANCELLED else UpdateFailure.TIMEOUT)
        } catch (error: java.io.IOException) {
            if (cancelled.get()) throw UpdateException(UpdateFailure.CANCELLED)
            if (error is UpdateException || error is UpdateRateLimit) throw error
            throw UpdateException(UpdateFailure.NETWORK)
        }
    }
    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/qwt1011/Musxi-Player-Android/releases?per_page=30"
    }
}
internal class UpdateRateLimit(val seconds: Long) : java.io.IOException()

/** Reads at most one byte beyond the bound; never writes that byte to private cache. */
internal fun copyUpdateBytes(input: InputStream, output: OutputStream, limit: Long, expectedHash: String?,
    cancelled: AtomicBoolean, progress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var received = 0L
    while (true) {
        if (cancelled.get()) throw UpdateException(UpdateFailure.CANCELLED)
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - received + 1).toInt())
        if (count < 0) break
        if (received + count > limit) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
        try { output.write(buffer, 0, count) } catch (_: java.io.IOException) { throw UpdateException(UpdateFailure.STORAGE) }
        digest.update(buffer, 0, count); received += count; progress(received)
    }
    if (cancelled.get()) throw UpdateException(UpdateFailure.CANCELLED)
    if (expectedHash != null) {
        if (received != limit) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
        if (digest.digest().joinToString("") { "%02x".format(it) } != expectedHash)
            throw UpdateException(UpdateFailure.CHECKSUM_MISMATCH)
    }
}
