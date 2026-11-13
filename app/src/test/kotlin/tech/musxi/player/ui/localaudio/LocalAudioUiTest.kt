package tech.musxi.player.ui.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioBookmark
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioFolderCursor
import tech.musxi.core.localaudio.LocalAudioImportResult
import tech.musxi.core.localaudio.LocalAudioImportState
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLibraryState
import tech.musxi.core.localaudio.LocalAudioMember
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalImportMode

/** What the 本地音频 pages derive from the library state, and the words they use. */
class LocalAudioUiTest {

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun item(
        n: Int,
        title: String? = null,
        creator: String? = null,
        durationMs: Long? = 3_600_000,
        mode: LocalImportMode = LocalImportMode.COPY,
        availability: LocalAudioAvailability = LocalAudioAvailability.AVAILABLE,
        displayName: String? = null,
    ) = LocalAudioItem(
        id = id(n),
        metadata = LocalAudioMetadata(fileName = "track$n.mp3", title = title, creator = creator, durationMs = durationMs),
        mode = mode,
        displayName = displayName,
        availability = availability,
        addedAtMs = n.toLong(),
    )

    private val sleep = LocalAudioFolder(id(100), "睡前音声")
    private val empty = LocalAudioFolder(id(101), "空文件夹")

    private fun state(
        items: List<LocalAudioItem>,
        members: List<LocalAudioMember> = emptyList(),
        progress: List<LocalAudioProgress> = emptyList(),
        cursors: List<LocalAudioFolderCursor> = emptyList(),
        bookmarks: List<LocalAudioBookmark> = emptyList(),
    ) = LocalAudioLibraryState(
        ready = true, revision = 7, folders = listOf(sleep, empty), items = items, members = members,
        progress = progress, cursors = cursors, bookmarks = bookmarks,
    )

    @Test fun offHidesCoversReadFromFilesButKeepsPickedOnes() {
        val auto = "a".repeat(64); val picked = "b".repeat(64)
        val read = item(1).copy(metadata = item(1).metadata.copy(artworkKey = auto))
        val chosen = item(2).copy(metadata = item(2).metadata.copy(artworkKey = auto), customArtworkKey = picked)
        val st = state(items = listOf(read, chosen))
        val on = LocalLibraryIndex(st, autoArtwork = true)
        assertEquals(auto, on.row(id(1))!!.artworkKey)
        assertEquals(picked, on.row(id(2))!!.artworkKey)
        assertTrue(on.row(id(2))!!.artworkCustom)
        val off = LocalLibraryIndex(st, autoArtwork = false)
        assertNull(off.row(id(1))!!.artworkKey)
        assertFalse(off.row(id(1))!!.artworkCustom)
        assertEquals(picked, off.row(id(2))!!.artworkKey)
    }

    @Test fun membersFollowTheirPositionsNotTheirListOrder() {
        // Positions may have gaps after appends; only their order counts.
        val index = LocalLibraryIndex(state(
            items = listOf(item(1), item(2), item(3)),
            members = listOf(
                LocalAudioMember(sleep.id, id(3), 40),
                LocalAudioMember(sleep.id, id(1), 2),
                LocalAudioMember(sleep.id, id(2), 9),
            ),
        ))
        assertEquals(listOf(id(1), id(2), id(3)), index.memberIds(sleep.id))
    }

    @Test fun aMemberWhoseItemIsGoneIsSkipped() {
        val index = LocalLibraryIndex(state(
            items = listOf(item(1)),
            members = listOf(LocalAudioMember(sleep.id, id(1), 0), LocalAudioMember(sleep.id, id(9), 1)),
        ))
        assertEquals(listOf(id(1)), index.memberIds(sleep.id))
    }

    @Test fun unfiledAudioIsWhatNoFolderHolds() {
        val index = LocalLibraryIndex(state(
            items = listOf(item(1), item(2)),
            members = listOf(LocalAudioMember(sleep.id, id(1), 0)),
        ))
        assertEquals(listOf(id(2)), index.unfiled)
    }

    @Test fun progressReadsAsTheListenerThinksOfIt() {
        assertEquals(ListenState.Unplayed, listenStateOf(null, 1000))
        assertEquals(ListenState.Unplayed, listenStateOf(LocalAudioProgress(id(1), 0, false, 1), 1000))
        assertEquals(ListenState.Finished, listenStateOf(LocalAudioProgress(id(1), 0, true, 1), 1000))
        val mid = listenStateOf(LocalAudioProgress(id(1), 1_398_000, false, 1), 3_760_000)
        assertEquals("听到 23:18 / 1:02:40", listenText(mid))
        assertEquals(0.3718f, (mid as ListenState.InProgress).fraction!!, 0.001f)
        // An unknown length still says where the listener is, with no bar.
        val unknown = listenStateOf(LocalAudioProgress(id(1), 61_000, false, 1), null)
        assertEquals("听到 1:01", listenText(unknown))
        assertNull((unknown as ListenState.InProgress).fraction)
        assertEquals("已听完", listenText(ListenState.Finished))
        assertNull(listenText(ListenState.Unplayed))
    }

    @Test fun aRowSaysWhoWhereAndWhetherItCanPlay() {
        val index = LocalLibraryIndex(state(
            items = listOf(
                item(1, title = "第一夜", creator = "某某社团"),
                item(2, displayName = "我的命名", durationMs = 90_000),
                item(3, mode = LocalImportMode.REFERENCE, availability = LocalAudioAvailability.MISSING, creator = "作者"),
            ),
            progress = listOf(LocalAudioProgress(id(1), 1_398_000, false, 1)),
            bookmarks = listOf(LocalAudioBookmark(id(50), id(1), 10, "喜欢"), LocalAudioBookmark(id(51), id(1), 20, "再听")),
        ))
        val first = index.row(id(1))!!
        assertEquals("第一夜", first.title)
        assertEquals("某某社团 · 听到 23:18 / 1:00:00", first.subtitle)
        assertEquals(2, first.bookmarks)
        assertFalse(first.canRelink)
        // No creator and unplayed: the length alone.
        val second = index.row(id(2))!!
        assertEquals("我的命名", second.title)
        assertEquals("1:30", second.subtitle)
        // An unplayable reference says so instead of its progress, and can be pointed elsewhere.
        val third = index.row(id(3))!!
        assertEquals("track3", third.title)
        assertEquals("作者 · 原文件不在了", third.subtitle)
        assertTrue(third.unavailable)
        assertTrue(third.canRelink)
    }

    @Test fun aFolderCardSaysWhereTheListenerLeftOff() {
        val index = LocalLibraryIndex(state(
            items = listOf(item(1), item(2), item(3)),
            members = (1..3).map { LocalAudioMember(sleep.id, id(it), it) },
            progress = listOf(LocalAudioProgress(id(2), 61_000, false, 1)),
            cursors = listOf(LocalAudioFolderCursor(sleep.id, id(2))),
        ))
        val card = index.folderUi(sleep)
        assertEquals(3, card.count)
        assertEquals("上次：第 2 条 · 1:01 · 共 3 条", card.subtitle)
        assertEquals("还没有音频", index.folderUi(empty).subtitle)
        assertEquals("第 2 条 · 1:01", resumeLine(index.resumePoint(sleep.id), 3))
    }

    @Test fun aFinishedCursorStartsThatItemAgain() {
        val index = LocalLibraryIndex(state(
            items = listOf(item(1), item(2)),
            members = (1..2).map { LocalAudioMember(sleep.id, id(it), it) },
            progress = listOf(LocalAudioProgress(id(2), 0, true, 1)),
            cursors = listOf(LocalAudioFolderCursor(sleep.id, id(2))),
        ))
        assertEquals("上次：第 2 条 · 共 2 条", index.folderUi(sleep).subtitle)
        assertEquals("从第 2 条开始", resumeLine(index.resumePoint(sleep.id), 2))
    }

    @Test fun noCursorStartsAtTheTop() {
        val index = LocalLibraryIndex(state(
            items = listOf(item(1)),
            members = listOf(LocalAudioMember(sleep.id, id(1), 0)),
        ))
        assertEquals("共 1 条", index.folderUi(sleep).subtitle)
        assertEquals("从第 1 条开始", resumeLine(index.resumePoint(sleep.id), 1))
        assertEquals("导入音频后就可以开始听", resumeLine(null, 0))
    }

    @Test fun aClashIsNotCalledALimit() {
        assertEquals("已有任务在进行，请稍后再试", localErrorText(LocalAudioError.LIMIT_REACHED, busy = true))
        assertEquals("超出数量上限，每次最多 500 个", localErrorText(LocalAudioError.LIMIT_REACHED))
        // A caller that knows which ceiling it hit names it; a clash still wins.
        assertEquals("本地库数量已达上限", localErrorText(LocalAudioError.LIMIT_REACHED, limitText = "本地库数量已达上限"))
        assertEquals("已有任务在进行，请稍后再试",
            localErrorText(LocalAudioError.LIMIT_REACHED, busy = true, limitText = "本地库数量已达上限"))
    }

    @Test fun aChangedSelectionIsNotCalledStale() {
        assertEquals("部分音频已不在此处，请重新选择", organizeFailureText(Result.failure<Unit>(SelectionChanged()), busy = false))
        assertEquals("列表刚刚更新，请再试一次",
            organizeFailureText(Result.failure<Unit>(tech.musxi.core.localaudio.LocalAudioFailure(LocalAudioError.STALE_STATE)), busy = false))
    }

    @Test fun everyErrorHasWordsAndNoneShowsTheEnumName() {
        LocalAudioError.entries.forEach { error ->
            val text = localErrorText(error)
            assertTrue(error.name, text.isNotBlank())
            assertFalse(error.name, text.contains('_'))
        }
    }

    @Test fun importProgressAndSummary() {
        assertEquals("正在读取文件列表…", importProgressText(LocalAudioImportState(running = true)))
        assertEquals(
            "正在导入 3 / 8 · 第三夜.mp3",
            importProgressText(LocalAudioImportState(running = true, total = 8, processed = 2, currentName = "第三夜.mp3")),
        )
        assertEquals("", importProgressText(LocalAudioImportState()))
        val ok = LocalAudioImportResult("a.mp3", audioId = id(1))
        val bad = LocalAudioImportResult("b.txt", error = LocalAudioError.UNSUPPORTED_AUDIO)
        assertEquals("已导入 1 个", importSummary(listOf(ok)))
        assertEquals("已导入 1 个，1 个没有导入", importSummary(listOf(ok, bad)))
        assertEquals("1 个都没有导入", importSummary(listOf(bad)))
        assertEquals("没有找到音频或视频文件", importSummary(emptyList()))
    }

    @Test fun sizesReadNaturally() {
        assertNull(sizeText(null))
        assertEquals("512 B", sizeText(512))
        assertEquals("8.2 MB", sizeText(8_600_000))
        assertEquals("1.5 GB", sizeText(1_610_612_736))
    }
}
