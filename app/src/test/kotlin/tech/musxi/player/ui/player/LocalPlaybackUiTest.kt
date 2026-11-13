package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.playback.FailureReason
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.core.playback.PlaybackFailureReason
import tech.musxi.core.playback.SourceSelectionMode
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.playback.LocalAudioQueueState
import tech.musxi.playback.PlaybackState
import tech.musxi.player.ui.shell.dockNoticeOf

/**
 * A local recording has no platform, source, account or quality. These pin down that every line the
 * player shows for one speaks about the file, and that platform songs keep their own wording.
 */
class LocalPlaybackUiTest {

    private val folderId = "11111111-1111-1111-1111-111111111111"

    private fun item(
        n: Int,
        creator: String? = "讲述人",
        durationMs: Long? = 3_600_000L,
        sizeBytes: Long? = 8_600_000L,
        mode: LocalImportMode = LocalImportMode.COPY,
        availability: LocalAudioAvailability = LocalAudioAvailability.AVAILABLE,
        displayName: String? = "第${n}章",
    ) = LocalAudioItem(
        id = "00000000-0000-0000-0000-00000000000$n",
        metadata = LocalAudioMetadata(
            fileName = "chapter-$n.m4a",
            creator = creator,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
        ),
        mode = mode,
        displayName = displayName,
        availability = availability,
        addedAtMs = 0,
    )

    private fun localState(
        item: LocalAudioItem = item(1),
        error: String? = null,
        failure: PlaybackFailure? = null,
        localError: LocalAudioError? = null,
        artist: String = "讲述人",
    ) = PlaybackState(
        mediaId = "local:${item.id}",
        title = item.title,
        artist = artist,
        error = error,
        failure = failure,
        // The service folds a local failure into a platform reason; none of it may reach the screen.
        failureReason = failure?.let { PlaybackFailureReason(FailureReason.PERMISSION_RESTRICTED) },
        localAudio = item,
        localError = localError,
    )

    // --- error wording ---

    @Test fun everyLocalErrorHasTheAgreedSentence() {
        assertEquals("需要重新授权访问这个文件", localAudioErrorText(LocalAudioError.PERMISSION_REQUIRED))
        assertEquals("文件已不存在或已移动", localAudioErrorText(LocalAudioError.NOT_FOUND))
        assertEquals("文件已被替换，请重新选择", localAudioErrorText(LocalAudioError.SOURCE_CHANGED))
        assertEquals("不支持这个音频格式", localAudioErrorText(LocalAudioError.UNSUPPORTED_AUDIO))
        assertEquals("读取失败，请检查文件", localAudioErrorText(LocalAudioError.READ_FAILED))
        assertEquals("文件太大，无法播放", localAudioErrorText(LocalAudioError.TOO_LARGE))
        assertEquals("音频暂时无法播放", localAudioErrorText(LocalAudioError.STORAGE_FAILED))
        assertEquals("音频暂时无法播放", localAudioErrorText(null))
    }

    @Test fun noLocalErrorTalksAboutSourcesAccountsOrPlatforms() {
        val texts = LocalAudioError.entries.map(::localAudioErrorText) + localAudioErrorText(null)
        for (text in texts) {
            for (word in listOf("音源", "账号", "平台", "content://", "/")) {
                assertFalse("$text contains $word", text.contains(word))
            }
        }
    }

    @Test fun theMiniPlayerNamesTheFileProblemInsteadOfAPlatformReason() {
        val subtitle = subtitleOf(
            localState(failure = PlaybackFailure.PERMISSION, error = "x", localError = LocalAudioError.PERMISSION_REQUIRED),
        )
        assertEquals("需要重新授权访问这个文件", subtitle)
        assertEquals("音频暂时无法播放", subtitleOf(localState(error = "x")))
    }

    @Test fun aRecordingWithoutAnAuthorIsNotAnUnknownSinger() {
        assertEquals("本地音频", subtitleOf(localState(artist = "")))
        assertEquals("讲述人", subtitleOf(localState()))
    }

    @Test fun theFullPlayerFailureLineIsAboutTheFile() {
        val ui = playerUiOf(
            localState(failure = PlaybackFailure.UNAVAILABLE, error = "x", localError = LocalAudioError.NOT_FOUND),
            // A selection left over from a platform song does not turn this into a swap.
            swapSearching = true,
        )
        assertEquals(PlayerStatus.FAILED, ui.status)
        assertFalse(ui.swapSearching)
        assertEquals("文件已不存在或已移动", thirdLineOf(ui))
    }

    @Test fun platformFailuresKeepTheirOwnWording() {
        val state = PlaybackState(
            mediaId = "netease:1",
            title = "晴天",
            queuePlatform = Platform.NETEASE,
            failure = PlaybackFailure.PERMISSION,
            failureReason = PlaybackFailureReason(FailureReason.MEMBERSHIP_REQUIRED),
            // A stale local error must not leak into a platform song.
            localError = LocalAudioError.NOT_FOUND,
        )
        assertEquals("暂无可用音源 · 需要会员", thirdLineOf(playerUiOf(state)))
        assertEquals("播放失败 · 需要会员", subtitleOf(state))
        assertFalse(playerUiOf(state).local)
    }

    // --- normal third line ---

    @Test fun theNormalThirdLineIsTheFolderOrLocalAudio() {
        assertEquals("有声书", thirdLineOf(playerUiOf(localState(), localFolderName = "有声书")))
        assertEquals("本地音频", thirdLineOf(playerUiOf(localState())))
        assertEquals("本地音频", localSourceLineOf(" "))
    }

    @Test fun theFolderNameComesFromTheQueueFolder() {
        val folders = listOf(LocalAudioFolder(folderId, "睡前故事"))
        assertEquals("睡前故事", localFolderNameOf(folderId, folders))
        assertNull(localFolderNameOf(null, folders))
        // A deleted folder: the queue became a loose list, so the line falls back.
        assertNull(localFolderNameOf("22222222-2222-2222-2222-222222222222", folders))
    }

    // --- detection ---

    @Test fun aLocalSlotIsLocalEvenBeforeItsSnapshotArrives() {
        assertTrue(isLocalPlayback(localState()))
        assertTrue(isLocalPlayback(PlaybackState(mediaId = "local:00000000-0000-0000-0000-000000000001")))
        assertFalse(isLocalPlayback(PlaybackState(mediaId = "netease:1", queuePlatform = Platform.NETEASE)))
        assertFalse(isLocalPlayback(PlaybackState()))
    }

    // --- dock notice ---

    @Test fun theDockNeverOffersAnotherSourceForALocalFile() {
        for (phase in SourceSelectionPhase.entries) {
            assertNull(
                "$phase",
                dockNoticeOf(phase, SourceSelectionMode.AUTO, playerOpen = false, playbackFailed = true, local = true),
            )
        }
        // A platform song still gets the way out.
        assertTrue(
            dockNoticeOf(SourceSelectionPhase.NONE, SourceSelectionMode.AUTO, playerOpen = false, playbackFailed = true)
                ?.findsSources == true,
        )
    }

    // --- queue ---

    @Test fun theLocalQueueBecomesQueueRows() {
        val state = LocalAudioQueueState(
            revision = 9,
            folderId = folderId,
            items = listOf(
                item(1),
                item(2, creator = null, durationMs = null),
                item(3, availability = LocalAudioAvailability.MISSING),
            ),
            currentIndex = 1,
            upcoming = listOf(2),
        )
        val ui = localQueueUiOf(state, editing = true)
        assertEquals(listOf("第1章", "第2章", "第3章"), ui.items.map { it.title })
        // No "未知歌手" for a recording without an author.
        assertEquals(listOf("讲述人", "", "讲述人"), ui.items.map { it.artist })
        assertEquals(listOf(3_600_000L, 0L, 3_600_000L), ui.items.map { it.durationMs })
        assertEquals(listOf(null, null, "文件已不存在"), ui.items.map { it.unavailable })
        assertTrue(ui.items.all { it.coverUrl == null })
        assertEquals(3, ui.items.map { it.key }.toSet().size)
        assertEquals(1, ui.currentIndex)
        assertEquals(9L, ui.revision)
        assertEquals(listOf(2), ui.upcoming)
        assertTrue(ui.editing)
        assertEquals("条", ui.noun)
    }

    @Test fun everyUnavailableStateIsMarked() {
        for (availability in LocalAudioAvailability.entries) {
            val row = localQueueUiOf(
                LocalAudioQueueState(items = listOf(item(1, availability = availability)), currentIndex = 0),
                editing = false,
            ).items.single()
            if (availability == LocalAudioAvailability.AVAILABLE) assertNull(row.unavailable)
            else assertTrue("$availability", !row.unavailable.isNullOrBlank())
        }
    }

    @Test fun anEmptyLocalQueueIsAnEmptyPanel() {
        val ui = localQueueUiOf(LocalAudioQueueState(), editing = true)
        assertTrue(ui.items.isEmpty())
        assertEquals(-1, ui.currentIndex)
    }

    @Test fun queueRowsAndNeighboursShowTheCoverThePlayerShows() {
        // A picked cover must reach the queue too (11-12: it kept the placeholder after a change).
        val state = LocalAudioQueueState(items = listOf(item(1), item(2), item(3)), currentIndex = 1)
        val cover: (tech.musxi.core.localaudio.LocalAudioItem) -> String? =
            { if (it.title == "第2章") null else "file:///covers/${it.title}.jpg" }
        val rows = localQueueUiOf(state, editing = true, cover = cover).items
        assertEquals(listOf("file:///covers/第1章.jpg", null, "file:///covers/第3章.jpg"), rows.map { it.coverUrl })
        assertEquals(CoverRef("第1章", "file:///covers/第1章.jpg"), localNeighbourCover(state, 1, -1, false, cover))
        assertEquals(CoverRef("第3章", "file:///covers/第3章.jpg"), localNeighbourCover(state, 1, 1, false, cover))
    }

    // --- neighbour covers ---

    @Test fun neighbourCoversArePlaceholdersFromTheLocalRows() {
        val state = LocalAudioQueueState(items = listOf(item(1), item(2), item(3)), currentIndex = 1)
        assertEquals(CoverRef("第1章", null), localNeighbourCover(state, 1, -1, shuffle = false))
        assertEquals(CoverRef("第3章", null), localNeighbourCover(state, 1, 1, shuffle = false))
        assertNull(localNeighbourCover(state, 2, 1, shuffle = false))
        assertNull(localNeighbourCover(state, 0, -1, shuffle = false))
        // Shuffle: the next recording is not the next row.
        assertNull(localNeighbourCover(state, 1, 1, shuffle = true))
        assertNull(localNeighbourCover(state, -1, 1, shuffle = false))
    }

    // --- info rows ---

    @Test fun theInfoDialogListsPublicFileFacts() {
        val rows = localAudioInfoRows(item(1), playerDurationMs = 0, folderName = "有声书")
        assertEquals(
            listOf(
                "作者" to "讲述人",
                "时长" to "1:00:00",
                "文件名" to "chapter-1.m4a",
                "大小" to "8.2 MB",
                "保存方式" to "已复制到 Musxi",
                "状态" to "可用",
                "文件夹" to "有声书",
            ),
            rows,
        )
    }

    @Test fun unknownInfoValuesAreLeftOut() {
        val rows = localAudioInfoRows(
            item(1, creator = null, durationMs = null, sizeBytes = null, mode = LocalImportMode.REFERENCE,
                availability = LocalAudioAvailability.PERMISSION_REQUIRED),
            playerDurationMs = 0,
            folderName = null,
        )
        assertEquals(
            listOf(
                "文件名" to "chapter-1.m4a",
                "保存方式" to "使用原文件",
                "状态" to "需要重新授权",
            ),
            rows,
        )
    }

    @Test fun thePlayersDurationWinsOnceKnown() {
        val rows = localAudioInfoRows(item(1, durationMs = 1_000), playerDurationMs = 90_061_000, folderName = null)
        assertEquals("25:01:01", rows.toMap()["时长"])
    }

    @Test fun sizesReadInBinaryUnits() {
        assertEquals("512 B", localSizeText(512))
        assertEquals("1.0 KB", localSizeText(1024))
        assertEquals("1.5 MB", localSizeText(1_572_864))
        assertEquals("2.0 GB", localSizeText(2L * 1024 * 1024 * 1024))
    }
}
