package tech.musxi.player.ui.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.*

class SourcePanelUiTest {
    private val original = SongSummary(MediaKey(Platform.QQ, "original"), "Song", listOf("Artist"), "Album", 180_000)
    private val source = SourceCandidate(original.copy(key = MediaKey(Platform.KUGOU, "source")), 1)

    @Test fun automaticallySelectedSourceClosesPanel() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, {}, {})
        selection.offer(token, AlternativeSources(original, listOf(source)))
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
        assertEquals(SourcePhase.HIDDEN, sourcePanelUiOf(selection.state.value, 0).phase)
    }

    private fun failed(
        reasons: Map<Platform, FailureReason> = emptyMap(),
        progress: List<SourceSearchProgress> = emptyList(),
        partial: Boolean = false,
    ) = SourceSelectionState(
        phase = SourceSelectionPhase.FAILED,
        original = original,
        partial = partial,
        platformProgress = progress,
        failureReasons = reasons,
    )

    private fun searching(
        reasons: Map<Platform, FailureReason> = emptyMap(),
        progress: List<SourceSearchProgress> = emptyList(),
        mode: SourceSelectionMode = SourceSelectionMode.AUTO,
    ) = failed(reasons = reasons, progress = progress).copy(phase = SourceSelectionPhase.SEARCHING, mode = mode)

    private fun choosing(list: List<SourceCandidate> = emptyList(), partial: Boolean = false) =
        failed(partial = partial).copy(phase = SourceSelectionPhase.CHOOSING, candidates = list)

    private fun done(platform: Platform, count: Int = 0) =
        SourceSearchProgress(platform, SourceSearchStatus.COMPLETE, 3, 3, candidateCount = count)

    private fun working(platform: Platform) =
        SourceSearchProgress(platform, SourceSearchStatus.SEARCHING, 0, 3)

    private fun silent(platform: Platform) =
        SourceSearchProgress(platform, SourceSearchStatus.FAILURE, 3, 3, error = "timeout")

    private fun candidate(platform: Platform, durationMs: Long, artists: List<String> = listOf("Artist"), album: String = "Album") =
        SourceCandidate(SongSummary(MediaKey(platform, "c-$durationMs"), "Song", artists, album, durationMs), 1)

    // --- headline ---------------------------------------------------------------------------

    @Test fun searchingTitleNamesTheOriginalPlatformsFailure() {
        val ui = sourcePanelUiOf(searching(reasons = mapOf(Platform.QQ to FailureReason.MEMBERSHIP_REQUIRED)), 0)
        assertEquals("《Song》在QQ音乐放不了（需要会员）", ui.title)
        assertEquals("正在其他平台找能播的版本", ui.subtitle)
    }

    @Test fun searchingTitleStaysBareWithoutARecordedReason() {
        assertEquals("《Song》在QQ音乐放不了", sourcePanelUiOf(searching(), 0).title)
        // Another platform's reason is not the original's.
        val other = searching(reasons = mapOf(Platform.KUGOU to FailureReason.COPYRIGHT_RESTRICTED))
        assertEquals("《Song》在QQ音乐放不了", sourcePanelUiOf(other, 0).title)
    }

    @Test fun userStartedSearchIsJustChangingTheSource() {
        // The song plays fine, so nothing is claimed about it, even if an old reason is on record.
        val manual = searching(
            reasons = mapOf(Platform.QQ to FailureReason.NETWORK),
            mode = SourceSelectionMode.MANUAL,
        )
        val ui = sourcePanelUiOf(manual, 0)
        assertTrue(ui.manual)
        assertEquals("换个音源", ui.title)
        assertEquals("正在其他平台找《Song》的其他版本", ui.subtitle)
    }

    @Test fun choosingIsAlwaysTheVersionPicker() {
        val ui = sourcePanelUiOf(choosing(), 0)
        assertEquals("换个音源", ui.title)
        assertEquals("《Song》· Artist · 3:00", ui.subtitle)
        assertEquals("", ui.note)
    }

    @Test fun choosingSaysWhenPlatformsDidNotAnswer() {
        assertEquals("有平台没回应，候选可能不全", sourcePanelUiOf(choosing(partial = true), 0).note)
        // The note belongs to the version list; a failed search says it through its lines.
        assertEquals("", sourcePanelUiOf(failed(partial = true), 0).note)
    }

    @Test fun failedHeadlineSaysNothingPlays() {
        assertEquals("没找到能播的《Song》", sourcePanelUiOf(failed(), 0).title)
    }

    @Test fun failedUserStartedSearchOnlyMissesOtherVersions() {
        val manual = failed().copy(mode = SourceSelectionMode.MANUAL)
        assertEquals("没找到《Song》的其他版本", sourcePanelUiOf(manual, 0).title)
    }

    @Test fun missingOriginalStillHasAHeadline() {
        val noOriginal = failed().copy(original = null)
        assertEquals("没找到能播的替代音源", sourcePanelUiOf(noOriginal, 0).title)
        assertEquals("正在找替代音源", sourcePanelUiOf(searching().copy(original = null), 0).title)
    }

    @Test fun trackLineLeavesOutWhatIsNotKnown() {
        assertEquals("《Song》· Artist · 3:00", trackLineOf(original))
        assertEquals("《Song》· 3:00", trackLineOf(original.copy(artists = emptyList())))
        assertEquals("《Song》· Artist", trackLineOf(original.copy(durationMs = 0)))
        assertEquals("《Song》", trackLineOf(original.copy(artists = listOf(" "), durationMs = 0)))
        assertEquals("", trackLineOf(null))
    }

    // --- searching: one line per platform ---------------------------------------------------

    @Test fun beforeAnyReportEveryOtherPlatformIsWorking() {
        val lines = sourcePanelUiOf(searching(), 0).platformLines
        assertEquals(listOf("网易云 · 正在找", "酷狗 · 正在找", "QQ音乐 · 原平台"), lines.map { it.text })
        assertEquals(listOf(true, true, false), lines.map { it.busy })
        assertEquals(listOf(false, false, true), lines.map { it.muted })
        assertEquals(listOf(Platform.NETEASE, Platform.KUGOU, Platform.QQ), lines.map { it.platform })
    }

    @Test fun searchingLinesFollowEachPlatformsProgress() {
        val ui = sourcePanelUiOf(searching(progress = listOf(done(Platform.NETEASE, count = 2), working(Platform.KUGOU))), 0)
        assertEquals(listOf("网易云 · 找到 2 个版本", "酷狗 · 正在找", "QQ音乐 · 原平台"), ui.platformLines.map { it.text })
        assertEquals(listOf(false, true, false), ui.platformLines.map { it.busy })
    }

    @Test fun finishedWithoutHitsIsNotFoundAndSilenceIsNoAnswer() {
        val ui = sourcePanelUiOf(searching(progress = listOf(done(Platform.NETEASE, count = 0), silent(Platform.KUGOU))), 0)
        assertEquals(listOf("网易云 · 没找到", "酷狗 · 没有回应", "QQ音乐 · 原平台"), ui.platformLines.map { it.text })
        assertTrue(ui.platformLines.none { it.busy })
    }

    @Test fun aPlatformThatWasNeverSearchedGetsNoLine() {
        // Once reports arrive, a platform without one had no connected account: it must not spin forever.
        val ui = sourcePanelUiOf(searching(progress = listOf(working(Platform.NETEASE))), 0)
        assertEquals(listOf("网易云 · 正在找", "QQ音乐 · 原平台"), ui.platformLines.map { it.text })
    }

    @Test fun lineListsAreOnlyForSearchingAndFailed() {
        assertTrue(sourcePanelUiOf(choosing(), 0).platformLines.isEmpty())
        assertTrue(sourcePanelUiOf(SourceSelectionState(), 0).platformLines.isEmpty())
    }

    // --- failed: the reasons ----------------------------------------------------------------

    @Test fun failedLinesCarryTheOriginalPlatformsOwnReason() {
        val ui = sourcePanelUiOf(failed(reasons = mapOf(Platform.QQ to FailureReason.MEMBERSHIP_REQUIRED)), 0)
        assertEquals(listOf("QQ音乐：需要会员"), ui.platformLines.map { it.text })
    }

    @Test fun detailNamesEachPlatformsOwnOutcome() {
        val state = failed(
            reasons = mapOf(
                Platform.NETEASE to FailureReason.COPYRIGHT_RESTRICTED,
                Platform.QQ to FailureReason.MEMBERSHIP_REQUIRED,
            ),
            progress = listOf(done(Platform.KUGOU)),
        )
        val ui = sourcePanelUiOf(state, 0)
        assertEquals("网易云：暂无版权 · QQ音乐：需要会员 · 酷狗：没找到这首", ui.failureDetail)
        assertEquals(listOf("网易云：暂无版权", "QQ音乐：需要会员", "酷狗：没找到这首"), ui.platformLines.map { it.text })
        assertTrue(ui.platformLines.none { it.busy || it.muted })
    }

    @Test fun detailSaysWhichPlatformDidNotAnswer() {
        val state = failed(
            progress = listOf(done(Platform.NETEASE), silent(Platform.KUGOU)),
            partial = true,
        )
        // QQ is the original and has no reason: it was not searched, so it is not listed.
        // Kugou answered with an error, which reads as no answer.
        assertEquals("网易云：没找到这首 · 酷狗：没有回应", sourcePanelUiOf(state, 0).failureDetail)
    }

    @Test fun detailTreatsAPlatformMissingFromProgressAsSilent() {
        val state = failed(progress = listOf(done(Platform.NETEASE)), partial = true)
        assertEquals("网易云：没找到这首 · 酷狗：没有回应", sourcePanelUiOf(state, 0).failureDetail)
    }

    @Test fun aRecordedReasonBeatsTheSearchProgress() {
        val state = failed(
            reasons = mapOf(Platform.KUGOU to FailureReason.PREVIEW_ONLY),
            progress = listOf(done(Platform.NETEASE), done(Platform.KUGOU)),
        )
        assertEquals("网易云：没找到这首 · 酷狗：只能试听", sourcePanelUiOf(state, 0).failureDetail)
    }

    @Test fun detailFallsBackToTheSummaryWithoutPerPlatformFacts() {
        val plain = sourcePanelUiOf(failed(), 0)
        assertEquals("网易云、QQ音乐、酷狗都没有可播版本", plain.failureDetail)
        assertTrue("the sheet shows the sentence instead of invented lines", plain.platformLines.isEmpty())
        assertEquals("还有平台没回应，稍后可以再试一次", sourcePanelUiOf(failed(partial = true), 0).failureDetail)
    }

    @Test fun reasonRecordedDuringSelectionReachesThePanel() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, {}, {})
        selection.playbackFailure(token, Platform.QQ, PlaybackFailure.PERMISSION, FailureReason.MEMBERSHIP_REQUIRED)
        selection.fail(token, "no_matching_candidates")
        val ui = sourcePanelUiOf(selection.state.value, 0)
        assertEquals(SourcePhase.FAILED, ui.phase)
        assertTrue(ui.failureDetail, ui.failureDetail.contains("QQ音乐：需要会员"))
        assertTrue(ui.platformLines.any { it.text == "QQ音乐：需要会员" })
    }

    // --- candidates -------------------------------------------------------------------------

    @Test fun durationNoteStartsAtFiveSeconds() {
        assertEquals("长 12 秒", durationDiffLabel(200_000L, 212_000L))
        assertEquals("短 8 秒", durationDiffLabel(200_000L, 192_000L))
        assertEquals("长 5 秒", durationDiffLabel(200_000L, 205_000L))
        assertEquals("短 5 秒", durationDiffLabel(200_000L, 195_000L))
        assertNull(durationDiffLabel(200_000L, 204_999L))
        assertNull(durationDiffLabel(200_000L, 203_000L))
        assertNull(durationDiffLabel(200_000L, 197_500L))
        assertNull(durationDiffLabel(200_000L, 200_000L))
    }

    @Test fun anUnknownLengthIsNotADifference() {
        assertNull(durationDiffLabel(0, 212_000L))
        assertNull(durationDiffLabel(200_000L, 0))
    }

    @Test fun candidateRowsCarryWhatTheListPrints() {
        val state = choosing(
            listOf(
                candidate(Platform.KUGOU, 212_000),
                candidate(Platform.NETEASE, 172_000),
                candidate(Platform.QQ, 183_000),
            ),
        )
        val rows = sourcePanelUiOf(state, 0).candidates
        assertEquals(listOf("长 32 秒", "短 8 秒", null), rows.map { it.durationNote })
        assertEquals(listOf(Platform.KUGOU, Platform.NETEASE, Platform.QQ), rows.map { it.platform })
        assertEquals(listOf(212_000L, 172_000L, 183_000L), rows.map { it.durationMs })
        assertEquals("Artist · Album", rows.first().subtitle)
        assertEquals("Song", rows.first().title)
    }

    @Test fun candidateSubtitleLeavesOutWhatIsNotKnown() {
        fun subtitle(artists: List<String>, album: String) =
            candidateSubtitleOf(SongSummary(MediaKey(Platform.QQ, "x"), "Song", artists, album, 1))
        assertEquals("A / B · Album", subtitle(listOf("A", "B"), "Album"))
        assertEquals("A", subtitle(listOf("A"), ""))
        assertEquals("A", subtitle(listOf("A"), "<unknown>"))
        assertEquals("Album", subtitle(emptyList(), "Album"))
        assertEquals("", subtitle(listOf(" "), " "))
    }

    // --- identity ---------------------------------------------------------------------------

    @Test fun theRevisionTravelsWithEveryFace() {
        assertEquals(4L, sourcePanelUiOf(searching().copy(revision = 4), 0).revision)
        assertEquals(5L, sourcePanelUiOf(failed().copy(revision = 5), 0).revision)
        // Hidden keeps it too: the sheet compares it with the revision the user dismissed.
        val hidden = sourcePanelUiOf(SourceSelectionState(revision = 7), 0)
        assertEquals(SourcePhase.HIDDEN, hidden.phase)
        assertEquals(7L, hidden.revision)
        assertEquals("", hidden.title)
    }

    @Test fun uncertainVersionStaysManualWithoutCountdown() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, { error("manual choice required") }, {})
        selection.offer(token, AlternativeSources(original, listOf(source.copy(song = source.song.copy(album = "Live")))))
        val ui = sourcePanelUiOf(selection.state.value, 50_000)
        assertEquals(SourcePhase.CHOOSING, ui.phase)
        assertNull(ui.candidates.single().secondsLeft)
        assertFalse(ui.candidates.single().autoTarget)
    }
}
