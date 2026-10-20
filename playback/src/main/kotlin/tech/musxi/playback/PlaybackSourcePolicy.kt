package tech.musxi.playback

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Pure source validation shared by playback entry points; never includes input in errors. */
object PlaybackSourcePolicy {
    private val mediaIdPattern = Regex("netease:[1-9][0-9]{0,18}")
    private val kugouIdPattern = Regex("kugou:[0-9a-f]{32}_(?:0|[1-9][0-9]{0,18})_(?:0|[1-9][0-9]{0,18})")
    private val qqIdPattern = Regex("qq:[A-Za-z0-9]{14}")
    private val hostLabelPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun acceptsMediaId(mediaId: String): Boolean = when {
        qqIdPattern.matches(mediaId) -> true
        mediaIdPattern.matches(mediaId) -> mediaId.substringAfter(':').toLongOrNull() != null
        kugouIdPattern.matches(mediaId) -> mediaId.substringAfter(':').split('_').drop(1).all { it.toLongOrNull() != null }
        else -> false
    }

    fun accepts(mediaId: String, url: String): Boolean {
        if (!acceptsMediaId(mediaId)) return false
        val domains = when {
            mediaId.startsWith("qq:") -> listOf("stream.qqmusic.qq.com")
            mediaId.startsWith("kugou:") -> listOf("kugou.com")
            else -> listOf("music.126.net", "music.163.com")
        }
        if (url.length > 8192 || url.any { it.isISOControl() }) return false
        val uri = try { URI(url) } catch (_: URISyntaxException) { return false }
        if (mediaId.startsWith("kugou:") && uri.scheme == "http") {
            val host = uri.host ?: return false
            return host in setOf("fs.youthandroid2.kugou.com", "fs.youthandroid.kugou.com") &&
                uri.rawAuthority == host && uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null
        }
        if (uri.isOpaque || uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.length > 253 || host.split('.').any { !hostLabelPattern.matches(it) }) return false
        if (domains.none { host == it || host.endsWith(".$it") } && !(mediaId.startsWith("qq:") && host == "aqqmusic.tc.qq.com")) return false
        if (uri.port != -1 && uri.port != 443) return false
        // Reject encoded authority, empty ports, alternate port spellings, and parser ambiguity.
        val authority = uri.rawAuthority?.lowercase(Locale.ROOT) ?: return false
        return authority == host || authority == "$host:443"
    }
}
