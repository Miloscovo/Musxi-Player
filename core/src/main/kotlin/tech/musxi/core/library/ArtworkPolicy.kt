package tech.musxi.core.library

import java.net.URI
import java.util.Locale
import tech.musxi.core.account.Platform

/** Public image metadata only. Audio capabilities and authenticated URLs never cross this boundary. */
object ArtworkPolicy {
    fun sanitize(platform: Platform, value: String?): String? {
        if (value == null || value.length !in 1..2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val allowed = when (platform) {
                Platform.NETEASE -> host.matches(Regex("p[1-9]\\.music\\.126\\.net"))
                Platform.QQ -> host in setOf("y.gtimg.cn", "qpic.y.qq.com", "i.gtimg.cn")
                Platform.KUGOU -> host in setOf("imge.kugou.com", "imgessl.kugou.com")
            }
            if (!allowed || uri.scheme !in setOf("https", "http") || uri.rawUserInfo != null || uri.port != -1 ||
                uri.rawQuery != null || uri.rawFragment != null || uri.rawAuthority.lowercase(Locale.ROOT) != host ||
                uri.rawPath.isNullOrEmpty() || uri.rawPath == "/" || uri.normalize().rawPath != uri.rawPath ||
                uri.rawPath.any { it == '\\' } || Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|2f|5c)").containsMatchIn(uri.rawPath)) null
            else "https://$host${uri.rawPath}"
        } catch (_: Exception) { null }
    }
}
