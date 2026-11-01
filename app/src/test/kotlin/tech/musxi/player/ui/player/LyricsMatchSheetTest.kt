package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsBinding
import tech.musxi.core.lyrics.LyricsMatchState
import tech.musxi.core.lyrics.ParsedLyrics

/** The sheet's mapping from the backend's match state: what each row says and when "用这个歌词" is live. */
class LyricsMatchSheetTest {

    private val original = MediaKey(Platform.NETEASE, "1")
    private val qqKey = MediaKey(Platform.QQ, "q1")
    private val kugouKey = MediaKey(Platform.KUGOU, "k1")
    private val owner = "a".repeat(64)

    private fun song(key: MediaKey, title: String = "晴天", album: String = "叶惠美") =
        SongSummary(key, title, listOf("周杰伦"), album, 269_000)

    private fun lyrics(count: Int) = ParsedLyrics(
        lines = (1..count).map { LyricLine(it * 1000L, "第${it}句") },
        plainText = "",
    )

    private fun binding(of: MediaKey = original, candidate: MediaKey = qqKey) =
        LyricsBinding(of, candidate, 0, owner, 1)

    private fun ui(state: LyricsMatchState) = lyricsMatchUiOf(state, "晴天", "周杰伦")

    @Test fun candidateCarriesArtistPlatformAlbumAndDuration() {
        val mapped = ui(LyricsMatchState(originalKey = original, candidates = listOf(song(qqKey)))).candidates.single()
        assertEquals(qqKey, mapped.key)
        assertEquals("晴天", mapped.title)
        assertEquals("周杰伦 · QQ音乐 · 叶惠美", mapped.caption)
        assertEquals(269_000L, mapped.durationMs)
        assertFalse(mapped.bound)
    }

    @Test fun captionSkipsBlankAndUnknownAlbumAndArtists() {
        assertEquals("酷狗", lyricsCandidateCaption(listOf(" "), Platform.KUGOU, "<unknown>"))
        assertEquals("A / B · 网易云", lyricsCandidateCaption(listOf("A", "B"), Platform.NETEASE, ""))
    }

    @Test fun onlyTheStoredCandidateIsTicked() {
        val state = LyricsMatchState(
            originalKey = original,
            candidates = listOf(song(qqKey), song(kugouKey)),
            binding = binding(candidate = kugouKey),
        )
        val mapped = ui(state)
        assertEquals(listOf(false, true), mapped.candidates.map { it.bound })
        assertTrue(mapped.manual)
    }

    @Test fun aBindingOfAnotherSongTicksNothing() {
        val other = MediaKey(Platform.NETEASE, "2")
        val state = LyricsMatchState(
            originalKey = original,
            candidates = listOf(song(qqKey)),
            binding = binding(of = other, candidate = qqKey),
        )
        val mapped = ui(state)
        assertFalse(mapped.candidates.single().bound)
        assertFalse(mapped.manual)
    }

    @Test fun previewShowsOnlyTheFirstSixLines() {
        val state = LyricsMatchState(
            originalKey = original,
            candidates = listOf(song(qqKey)),
            previewKey = qqKey,
            preview = lyrics(10),
        )
        val preview = ui(state).preview!!
        assertEquals(listOf("第1句", "第2句", "第3句", "第4句", "第5句", "第6句"), preview.lines)
        assertEquals("晴天", preview.title)
        assertTrue(preview.canBind)
    }

    @Test fun previewSkipsBlankLinesAndFallsBackToPlainText() {
        val timed = ParsedLyrics(
            lines = listOf(LyricLine(0, ""), LyricLine(1000, " 甲 "), LyricLine(2000, "")),
            plainText = "",
        )
        assertEquals(listOf("甲"), previewLinesOf(timed))
        val plain = ParsedLyrics(lines = emptyList(), plainText = "乙\n\n 丙 \n")
        assertEquals(listOf("乙", "丙"), previewLinesOf(plain))
    }

    @Test fun bindIsOffForEmptyBoundAndSavingPreviews() {
        val base = LyricsMatchState(originalKey = original, candidates = listOf(song(qqKey)), previewKey = qqKey)
        assertFalse(ui(base.copy(previewing = true)).preview!!.canBind)
        assertFalse(ui(base.copy(preview = lyrics(0))).preview!!.canBind)
        assertFalse(ui(base.copy(preview = lyrics(3), saving = true)).preview!!.canBind)
        val bound = ui(base.copy(preview = lyrics(3), binding = binding())).preview!!
        assertTrue(bound.bound)
        assertFalse(bound.canBind)
        assertEquals("正在使用这份歌词", lyricsBindLabel(bound, saving = false))
    }

    @Test fun bindLabelFollowsSaving() {
        val state = LyricsMatchState(originalKey = original, candidates = listOf(song(qqKey)), previewKey = qqKey, preview = lyrics(2))
        val preview = ui(state).preview!!
        assertEquals("用这个歌词", lyricsBindLabel(preview, saving = false))
        assertEquals("正在保存…", lyricsBindLabel(preview, saving = true))
    }

    @Test fun previewNoteSaysLoadingFailedOrEmpty() {
        val empty = LyricsPreviewUi(qqKey, "", emptyList(), loading = false, bound = false, canBind = false)
        assertEquals("正在取歌词…", lyricsPreviewNote(empty.copy(loading = true), failed = false))
        assertEquals("没能取到这份歌词", lyricsPreviewNote(empty, failed = true))
        assertEquals("这个版本没有歌词", lyricsPreviewNote(empty, failed = false))
        assertNull(lyricsPreviewNote(empty.copy(lines = listOf("甲")), failed = false))
    }

    @Test fun errorCodesBecomeShortSentences() {
        assertNull(lyricsMatchErrorText(null, hasOriginal = true))
        assertEquals("该平台账号未连接或已过期，请到「我的」重新连接", lyricsMatchErrorText("account_unavailable", true))
        assertEquals("该平台账号未连接或已过期，请到「我的」重新连接", lyricsMatchErrorText("expired", true))
        assertEquals("网络异常，请稍后重试", lyricsMatchErrorText("timeout", true))
        assertEquals("请求太频繁，稍后再试", lyricsMatchErrorText("rate", true))
        assertEquals("没能读取或保存匹配结果，请重试", lyricsMatchErrorText("storage", true))
        assertEquals("没能完成，请重试", lyricsMatchErrorText("something_new", true))
    }

    @Test fun noOriginalMeansDifferentThingsWithAndWithoutASong() {
        assertEquals("没有正在播放的歌曲", lyricsMatchErrorText("no_original", hasOriginal = false))
        assertEquals(
            "这首歌所在平台的账号未连接，请到「我的」重新连接",
            lyricsMatchErrorText("no_original", hasOriginal = true),
        )
    }

    @Test fun stateFlagsAndErrorReachTheUi() {
        val mapped = ui(LyricsMatchState(originalKey = original, searching = true, saving = true, error = "limit"))
        assertTrue(mapped.searching)
        assertTrue(mapped.saving)
        assertEquals("请求太频繁，稍后再试", mapped.error)
        assertEquals(original, mapped.original)
    }

    @Test fun noticeFollowsTheMostUrgentState() {
        val base = LyricsMatchUi(original = original)
        assertEquals("没有正在播放的歌曲", lyricsMatchNotice(LyricsMatchUi(), searched = false))
        assertEquals("网络异常，请稍后重试", lyricsMatchNotice(base.copy(error = "网络异常，请稍后重试", searching = true), true))
        assertEquals("正在保存…", lyricsMatchNotice(base.copy(saving = true), true))
        assertEquals("正在搜索…", lyricsMatchNotice(base.copy(searching = true), true))
        assertEquals("没有找到相关歌曲，换个关键词试试", lyricsMatchNotice(base, searched = true))
        assertEquals("输入歌名或歌手，点键盘上的搜索", lyricsMatchNotice(base, searched = false))
        val withRows = base.copy(candidates = listOf(LyricsCandidateUi(qqKey, "晴天", "", 0, false)))
        assertNull(lyricsMatchNotice(withRows, searched = true))
    }

    @Test fun defaultQueryIsTitleThenArtistWithinTheBackendLimit() {
        assertEquals("晴天 周杰伦", lyricsDefaultQuery("晴天", "周杰伦"))
        assertEquals("晴天 A B", lyricsDefaultQuery("晴天", "A / B"))
        assertEquals("晴天", lyricsDefaultQuery("晴天", ""))
        assertEquals("", lyricsDefaultQuery("", ""))
        assertEquals(200, lyricsDefaultQuery("a".repeat(300), "b").length)
    }

    @Test fun searchKeywordsAreNeverBlankOrOverLong() {
        assertNull(lyricsSearchKeywords("   "))
        assertEquals("晴天 周杰伦", lyricsSearchKeywords("  晴天 周杰伦  "))
        assertEquals(200, lyricsSearchKeywords("x".repeat(500))!!.length)
    }

    @Test fun subtitleNamesTheSong() {
        assertEquals("《晴天》· 周杰伦", lyricsSubtitleOf("晴天", "周杰伦"))
        assertEquals("《晴天》", lyricsSubtitleOf("晴天", ""))
        assertEquals("", lyricsSubtitleOf("", "周杰伦"))
    }
}
