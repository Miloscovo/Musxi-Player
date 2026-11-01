package tech.musxi.core.account

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Account pictures are private profile metadata, with a separate allowlist from album artwork. */
object AvatarPolicy {
    fun sanitize(platform: Platform, value: String?): String? {
        if (value == null || value.length !in 1..2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(if (platform == Platform.KUGOU) value.replace("{size}", "165") else value)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.port != -1 ||
                uri.rawFragment != null || uri.rawAuthority.lowercase(Locale.ROOT) != host) return null
            var path = uri.rawPath ?: return null
            if (!path.startsWith('/') || path == "/" || uri.normalize().rawPath != path ||
                '\\' in path || Regex("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|2f|5c|25)").containsMatchIn(path)) return null
            val query = parseQuery(uri.rawQuery) ?: return null
            val suffix = when (platform) {
                Platform.NETEASE -> {
                    if (!host.matches(Regex("p[1-9]\\.music\\.126\\.net")) || query.keys.any { it != "param" }) return null
                    "?param=200y200"
                }
                Platform.KUGOU -> {
                    if (host !in setOf("imge.kugou.com", "imgessl.kugou.com") &&
                        !host.matches(Regex("[a-z0-9-]+\\.kgimg\\.com"))) return null
                    if (query.isNotEmpty()) return null
                    path = path.replaceFirst(Regex("^/kugouicon/[0-9]{1,4}/"), "/kugouicon/165/")
                    ""
                }
                Platform.QQ -> {
                    when {
                        host in setOf("thirdqq.qlogo.cn", "thirdwx.qlogo.cn", "q1.qlogo.cn") && path == "/g" -> {
                            if (query.keys.any { it !in setOf("b", "k", "nk", "kti", "s", "t") } ||
                                query["b"] !in setOf("qq", "wx", "sdk") ||
                                (query["kti"]?.length ?: 0) > 128 ||
                                !validPictureId(query["k"] ?: query["nk"])) return null
                            encode(query.filterKeys { it != "s" } + ("s" to "140"))
                        }
                        host == "q.qlogo.cn" && path == "/headimg_dl" -> {
                            if (query.keys.any { it !in setOf("dst_uin", "spec", "img_type") } ||
                                query["dst_uin"]?.matches(Regex("[0-9]{1,24}")) != true) return null
                            encode(query.filterKeys { it != "spec" } + ("spec" to "140"))
                        }
                        host in setOf("y.gtimg.cn", "qpic.y.qq.com", "i.gtimg.cn") -> {
                            if (query.isNotEmpty()) return null
                            path = path.replaceFirst(Regex("R[0-9]{2,4}x[0-9]{2,4}"), "R150x150")
                            ""
                        }
                        else -> return null
                    }
                }
            }
            "https://$host$path$suffix"
        } catch (_: Exception) { null }
    }

    private fun validPictureId(value: String?) = value != null && value.matches(Regex("[A-Za-z0-9_+=.-]{1,512}"))

    private fun parseQuery(raw: String?): Map<String, String>? {
        if (raw == null) return emptyMap()
        if (raw.isEmpty() || raw.length > 1024) return null
        val fields = linkedMapOf<String, String>()
        for (part in raw.split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) return null
            val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name())
            if (!key.matches(Regex("[a-z_]{1,16}")) || (value.isEmpty() && key != "kti") || value.length > 512 ||
                !value.matches(Regex("[A-Za-z0-9_+=.-]*")) || fields.put(key, value) != null) return null
        }
        return fields
    }

    private fun encode(fields: Map<String, String>) = fields.entries.joinToString("&", prefix = "?") {
        "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())}"
    }
}
