package tech.musxi.core.playback

data class QualityOption(val id: String, val name: String)

data class QualityOptions(val options: List<QualityOption>, val current: String?) {
    init { require(options.map { it.id }.distinct().size == options.size) }
}

/** Platform-scoped request vocabulary; availability always comes from the official response. */
object PlaybackQualities {
    private val levels = listOf(
        listOf("kugou:128", "kugou:320", "kugou:flac"),
        listOf("qq:standard", "qq:high", "qq:lossless"),
        listOf("netease:standard", "netease:higher", "netease:exhigh", "netease:lossless"),
    )
    fun candidates(platform: String, preferred: String?): List<String> {
        val ids = levels.singleOrNull { it.first().substringBefore(':') == platform } ?: return emptyList()
        return ids.take((ids.indexOf(preferred).takeIf { it >= 0 } ?: 0) + 1).reversed()
    }
    fun name(id: String): String? = when (id) {
        "kugou:128", "qq:standard", "netease:standard" -> "标准音质"
        "kugou:320", "qq:high" -> "高品音质"
        "netease:higher" -> "较高音质"
        "netease:exhigh" -> "极高音质"
        "kugou:flac", "qq:lossless", "netease:lossless" -> "无损音质"
        else -> null
    }
}
