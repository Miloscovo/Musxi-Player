package tech.musxi.player.ui.player

import java.net.URI
import java.util.Locale

/**
 * The address of a larger rendition of the same cover, for the full player's big artwork.
 *
 * Lists and the mini player keep the address they were given. Only two sources are known to serve
 * other sizes from the same file name, so everything else (Kugou included, its large size is not
 * verified) comes back untouched. Scheme, host, port and user info are never rewritten, so the
 * result is always served by the host the original address named.
 */
fun hdCoverUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val host = try {
        val uri = URI(url)
        if (uri.scheme?.lowercase(Locale.ROOT) !in WEB_SCHEMES) return url
        uri.host?.lowercase(Locale.ROOT) ?: return url
    } catch (_: Exception) {
        return url
    }
    val parts = splitUrl(url) ?: return url
    return when {
        host == QQ_COVER_HOST ->
            parts.copy(path = QQ_COVER_SIZE.replace(parts.path) { "${it.groupValues[1]}R${HD_SIDE}x$HD_SIDE${it.groupValues[2]}" }).join()
        host.endsWith(NETEASE_SUFFIX) ->
            parts.copy(query = withParam(parts.query, "param", "${HD_SIDE}y$HD_SIDE")).join()
        else -> url
    }
}

private const val HD_SIDE = 800
private const val QQ_COVER_HOST = "y.gtimg.cn"
private const val NETEASE_SUFFIX = ".music.126.net"
private val WEB_SCHEMES = setOf("http", "https")

/** `T002R300x300M000…`: the size sits between the `T` code and the `M` code of the file name. */
private val QQ_COVER_SIZE = Regex("(T\\d{3})R\\d+x\\d+(M)")

/** An address cut at its authority, then at `?` and `#`, so each part can change on its own. */
private data class UrlParts(val origin: String, val path: String, val query: String?, val fragment: String?) {
    fun join(): String = origin + path + (query?.let { "?$it" } ?: "") + (fragment?.let { "#$it" } ?: "")
}

private fun splitUrl(url: String): UrlParts? {
    val authorityStart = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
    val pathStart = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: return null
    val rest = url.substring(pathStart)
    val hash = rest.indexOf('#')
    val beforeHash = if (hash >= 0) rest.substring(0, hash) else rest
    val question = beforeHash.indexOf('?')
    return UrlParts(
        origin = url.substring(0, pathStart),
        path = if (question >= 0) beforeHash.substring(0, question) else beforeHash,
        query = if (question >= 0) beforeHash.substring(question + 1) else null,
        fragment = if (hash >= 0) rest.substring(hash + 1) else null,
    )
}

/** [query] with [key] set to [value]: an existing entry is dropped and the new one goes last. */
private fun withParam(query: String?, key: String, value: String): String {
    val kept = query.orEmpty().split('&').filter { it.isNotEmpty() && it.substringBefore('=') != key }
    return (kept + "$key=$value").joinToString("&")
}
