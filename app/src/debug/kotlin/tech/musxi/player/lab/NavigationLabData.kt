package tech.musxi.player.lab

import androidx.compose.ui.unit.Dp

/** What a tab host hands to every page it shows. */
internal class LabPageContext(
    val env: LabEnv,
    val nav: LabTabNav,
    val scroll: LabScrollStore,
    /** Space the floating dock covers at the bottom; lists pad their end by this much. */
    val bottomPad: Dp,
    val onPush: (LabRoute) -> Unit,
    val onBack: () -> Unit,
)

/** A fake playlist: a window of the sample songs, so every playlist has its own cover and length. */
internal class LabPlaylist(val index: Int, val title: String, val songs: List<LabSong>) {
    /** Placeholder colours come from the title: seeds like "sample-3" and "sample-4" hash to nearly the same hue. */
    val seed: String get() = title
    val coverUrl: String? get() = songs.firstOrNull()?.coverUrl
    val subtitle: String get() = "${songs.size} 首"
    val minutes: Int get() = (songs.sumOf { it.durationMs } / 60_000L).toInt()
}

private val PlaylistTitles = listOf(
    "通勤路上", "雨天的窗边", "深夜写代码", "周末公路", "咖啡馆午后", "跑步 5 公里",
    "老歌新听", "睡前轻音乐", "考试周", "春日野餐", "一个人的海边", "冬夜围炉",
)

internal fun labPlaylists(songs: List<LabSong>): List<LabPlaylist> {
    if (songs.isEmpty()) return emptyList()
    return PlaylistTitles.mapIndexed { i, title ->
        val count = 8 + (i * 5) % 14
        val offset = (i * 3) % songs.size
        LabPlaylist(i, title, List(count) { songs[(offset + it) % songs.size] })
    }
}

internal fun labDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun LabSong.firstArtist(): String = artist.substringBefore(" / ")

/** Seed for the gradient placeholder; the title spreads the hues that sequential ids would bunch together. */
internal fun LabSong.placeholderSeed(): String = "$title/$seed"

/** Songs by [name], then the rest of the library, so a one-song artist still gets a page that scrolls. */
internal fun artistSongs(songs: List<LabSong>, name: String): Pair<List<LabSong>, List<LabSong>> {
    val own = songs.filter { song -> song.artist.split(" / ").any { it == name } }
    return own to songs.filter { it !in own }.take(8)
}

/** Fake search results: a keyword-dependent window of the sample songs. */
internal fun searchSongs(songs: List<LabSong>, keyword: String): List<LabSong> {
    if (songs.isEmpty()) return emptyList()
    val offset = (keyword.hashCode() and 0x7fffffff) % songs.size
    return List(minOf(12, songs.size)) { songs[(offset + it) % songs.size] }
}

internal val HotKeywords = listOf(
    "夜航", "回声", "薄雾", "海盐", "晴空万里", "慢速列车", "静默时区", "月光白",
    "旧唱片", "远方来信", "林间", "Aster", "沈宁", "Noa", "周屿",
)

internal val ProfileRows = listOf(
    "账号与安全", "播放与音质", "歌词显示", "外观", "下载与缓存", "消息通知", "隐私", "帮助与反馈", "关于 Musxi",
)

/** The same fake settings behind every row of the profile tab; the pushed page only needs to exist. */
internal val SettingItems = listOf(
    "跟随系统" to "开", "仅在 Wi-Fi 下载" to "开", "音质" to "较高", "淡入淡出" to "2 秒",
    "歌词字号" to "标准", "锁屏显示歌词" to "关", "睡眠定时" to "未设置", "缓存上限" to "2 GB",
)
