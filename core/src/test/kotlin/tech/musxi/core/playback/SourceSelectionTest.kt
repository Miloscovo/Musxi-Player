package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class SourceSelectionTest {
    private fun song(platform: Platform, id: String, title: String = "AIZO") = SongSummary(
        MediaKey(platform, id), title, listOf("Artist"), "Album", 180000)
    private val original = song(Platform.QQ, "A0000000000001")
    private val first = SourceCandidate(song(Platform.NETEASE, "1"), 7)
    private val second = SourceCandidate(song(Platform.KUGOU, "a".repeat(32) + "_0_1"), 8)

    @Test fun candidateQualityAndPlatformFailureBelongToTheCurrentSelectionRevision() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, {}, {}, SourceSelectionMode.MANUAL)
        selection.offer(token, AlternativeSources(original, listOf(first)))
        val audio = SourceAudioMetadata(quality = SourceAudioQuality.HIGH, qualities = listOf(QualityOption("netease:exhigh", "极高音质")))
        assertTrue(selection.candidateAudio(token, first.song.key, audio))
        selection.playbackFailure(token, Platform.QQ, PlaybackFailure.PERMISSION)
        assertEquals(audio, selection.state.value.candidates.single().audio)
        assertEquals(PlaybackFailure.PERMISSION, selection.state.value.playbackFailures[Platform.QQ])
        selection.clear()
        assertFalse(selection.candidateAudio(token, first.song.key, audio))
        selection.playbackFailure(token, Platform.QQ, PlaybackFailure.NETWORK)
        assertTrue(selection.state.value.playbackFailures.isEmpty())
    }

    @Test fun offerPicksFirstEligibleImmediatelyAndOnlyOnce() {
        val selection = SourceSelection { error("time must not be read") }; val chosen = mutableListOf<SourceCandidate>()
        val token = selection.begin(original, chosen::add, {})
        val related = first.copy(song = first.song.copy(key = MediaKey(Platform.NETEASE, "99"), title = "AIZO live"))
        selection.offer(token, AlternativeSources(original, listOf(related, first, second)))
        assertEquals(listOf(first), chosen)
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
        assertEquals(first, selection.state.value.selected)
        assertEquals(0, selection.state.value.automaticIndex)
        assertNull(selection.state.value.deadlineElapsedMs)
        selection.tick(token); selection.tick(token)
        assertEquals(listOf(first), chosen)
        assertFalse(selection.select(token, 0))
        assertFalse(selection.select(token, 2))
    }
    @Test fun oldResultsCannotSelectAfterNavigation() {
        val selection = SourceSelection { 0 }; val chosen = mutableListOf<SourceCandidate>()
        val old = selection.begin(original, chosen::add, {})
        val next = selection.begin(original, chosen::add, {})
        selection.offer(old, AlternativeSources(original, listOf(first, second)))
        assertEquals(SourceSelectionPhase.SEARCHING, selection.state.value.phase)
        assertTrue(chosen.isEmpty())
        selection.tick(old); assertTrue(chosen.isEmpty())
        selection.clear()
        selection.offer(next, AlternativeSources(original, listOf(first)))
        selection.tick(next); assertFalse(selection.select(next, 0))
        assertEquals(SourceSelectionPhase.NONE, selection.state.value.phase)
        assertTrue(chosen.isEmpty())
    }
    @Test fun cancelDuringSearchAndChoiceNeverInvokesSelectionOrLateResponse() {
        val selection = SourceSelection { 10000 }; var dismissed = 0; var selected = 0
        val token = selection.begin(original, { selected++ }, { dismissed++ })
        assertTrue(selection.cancel(token)); assertFalse(selection.cancel(token))
        selection.offer(token, AlternativeSources(original, listOf(first)))
        selection.tick(token)
        assertEquals(1, dismissed); assertEquals(0, selected)
        val next = selection.begin(original, { selected++ }, { dismissed++ })
        selection.offer(next, AlternativeSources(original, listOf(first)))
        assertEquals(1, selected)
        selection.cancel(next); selection.tick(next)
        assertEquals(1, dismissed); assertEquals(1, selected)
        assertFalse(selection.cancel(next))
    }
    @Test fun automaticDiscoveryLeavesUncertainVersionsForExplicitConfirmation() {
        val selection = SourceSelection { 0 }; var count = 0
        val token = selection.begin(original, { count++ }, {})
        selection.offer(token, AlternativeSources(original, listOf(first.copy(song = first.song.copy(title = "AIZO (Live)")))))
        assertNull(selection.state.value.deadlineElapsedMs)
        selection.tick(token); assertEquals(0, count)
        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertTrue(selection.select(token, 0))
        val next = selection.begin(original, { count++ }, {})
        selection.offer(next, AlternativeSources(original, listOf(SourceCandidate(original, 1))))
        assertEquals(SourceSelectionPhase.FAILED, selection.state.value.phase)
        assertNull(selection.state.value.deadlineElapsedMs)
        assertFalse(selection.select(next, 0))
        assertEquals(1, count)
    }
    @Test fun unicodeCaseAndWhitespaceMatchButVersionMarkersRemainDistinct() {
        assertTrue(SourceSelection.sameTitle(" Ａｉｚｏ ", "aizo"))
        assertFalse(SourceSelection.sameTitle("AIZO", "AIZO (Live)"))
        assertFalse(SourceSelection.sameTitle(" ", ""))
    }
    @Test fun uncertainMetadataNeverAutoPlaysButExistingConfirmationRemainsAvailable() {
        val selection = SourceSelection { error("time must not be read") }; val chosen = mutableListOf<SourceCandidate>()
        val token = selection.begin(original, chosen::add, {})
        val wrong = first.copy(song = first.song.copy(artists = listOf("Other")))
        val unknown = second.copy(song = second.song.copy(durationMs = 0))
        val version = first.copy(song = first.song.copy(key = MediaKey(Platform.NETEASE, "2"), album = "Live"))
        selection.offer(token, AlternativeSources(original, listOf(wrong, unknown, version)))
        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertNull(selection.state.value.deadlineElapsedMs); assertNull(selection.state.value.automaticIndex)
        selection.tick(token); assertTrue(chosen.isEmpty())
        assertEquals(3, selection.state.value.candidates.size)
        val versionIndex = selection.state.value.candidates.indexOfFirst { it.song.key == version.song.key }
        assertTrue(selection.select(token, versionIndex)); assertEquals(listOf(version), chosen)
    }
    @Test fun qingTianJayCoverAndSpedCandidatesNeverAutoSelect() {
        val selection = SourceSelection { 0 }; val chosen = mutableListOf<SourceCandidate>()
        val qingTian = SongSummary(MediaKey(Platform.QQ, "QINGTIAN"), "晴天",
            listOf("周杰伦"), "叶惠美", 269_000)
        fun candidate(id: String) = SourceCandidate(qingTian.copy(key = MediaKey(Platform.NETEASE, id)), 7)
        val jay = candidate("jay").copy(song = candidate("jay").song.copy(artists = listOf("Jay")))
        val cover = candidate("cover").copy(song = candidate("cover").song.copy(album = "晴天（翻唱）"))
        val sped = candidate("sped").copy(song = candidate("sped").song.copy(album = "Sped Up"))

        val token = selection.begin(qingTian, chosen::add, {}, SourceSelectionMode.AUTO)
        selection.offer(token, AlternativeSources(qingTian, listOf(jay, cover, sped)))

        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertNull(selection.state.value.automaticIndex)
        assertTrue(chosen.isEmpty())
    }
    @Test fun manualModeNeverAutoSelectsAndExposesStrictCandidates() {
        val selection = SourceSelection { error("time must not be read") }; var chosen = 0
        val token = selection.begin(original, { chosen++ }, {}, SourceSelectionMode.MANUAL)
        selection.offer(token, AlternativeSources(original, listOf(first, second)))
        assertEquals(SourceSelectionMode.MANUAL, selection.state.value.mode)
        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertNull(selection.state.value.automaticIndex)
        assertEquals(0, chosen)
        assertTrue(selection.select(token, 1))
        assertEquals(1, chosen)
        assertEquals(second, selection.state.value.selected)
    }
    @Test fun manualModeWithoutEligibleCandidateFailsWithoutSelectionOrDismissal() {
        val selection = SourceSelection { 0 }; var dismissed = 0; var chosen = 0
        val token = selection.begin(original, { chosen++ }, { dismissed++ }, SourceSelectionMode.MANUAL)
        val unknown = first.copy(song = first.song.copy(durationMs = 0))
        selection.offer(token, AlternativeSources(original, listOf(unknown)))
        assertEquals(SourceSelectionPhase.FAILED, selection.state.value.phase)
        assertNull(selection.state.value.automaticIndex)
        assertFalse(selection.cancel(token))
        assertEquals(0, dismissed)
        assertEquals(0, chosen)
    }
    @Test fun strictEligibleCandidateWinsWhileLooseOptionIsExcluded() {
        val selection = SourceSelection { 0 }; val chosen = mutableListOf<SourceCandidate>()
        val token = selection.begin(original, chosen::add, {})
        val wrong = first.copy(song = first.song.copy(artists = listOf("Other")))
        selection.offer(token, AlternativeSources(original, listOf(wrong, second)))
        assertEquals(listOf(second, wrong), selection.state.value.candidates)
        assertEquals(0, selection.state.value.automaticIndex)
        assertEquals(listOf(second), chosen)
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
    }
    @Test fun mismatchedOriginalResultCannotReplaceActiveSearchOrArmTimer() {
        val selection = SourceSelection { 0 }; val token = selection.begin(original, {}, {})
        selection.offer(token, AlternativeSources(original.copy(key = MediaKey(Platform.QQ, "A0000000000002")), listOf(first)))
        assertEquals(SourceSelectionPhase.SEARCHING, selection.state.value.phase)
        assertEquals(original, selection.state.value.original)
        assertNull(selection.state.value.deadlineElapsedMs)
        selection.offer(token, AlternativeSources(original, listOf(first)))
        assertEquals(0, selection.state.value.automaticIndex)
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
    }
    @Test fun repositoryMayEnrichSameOriginalKeyBeforeAutomaticAssessment() {
        val selection = SourceSelection { error("time must not be read") }; val chosen = mutableListOf<SourceCandidate>()
        val incomplete = original.copy(artists = emptyList(), durationMs = 0)
        val token = selection.begin(incomplete, chosen::add, {})
        selection.offer(token, AlternativeSources(original, listOf(first)))
        assertEquals(original, selection.state.value.original)
        assertEquals(0, selection.state.value.automaticIndex)
        assertEquals(listOf(first), chosen)
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
    }
    @Test fun callbackReentryCannotSelectTwiceOrOverwriteItsSelection() {
        lateinit var selection: SourceSelection
        var token = 0L
        val chosen = mutableListOf<SourceCandidate>()
        selection = SourceSelection { 0 }
        token = selection.begin(original, { candidate ->
            chosen += candidate
            assertFalse(selection.select(token, 1))
            selection.tick(token)
        }, {})
        selection.offer(token, AlternativeSources(original, listOf(first, second)))
        assertEquals(listOf(first), chosen)
        assertEquals(first, selection.state.value.selected)
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
    }
    @Test fun progressIsTokenScopedAndCandidatesKeepUnknownCapabilities() {
        val selection = SourceSelection { 0 }; val token = selection.begin(original, {}, {})
        val progress = SourceSearchProgress(Platform.NETEASE, SourceSearchStatus.SEARCHING, 0, 2)
        selection.progress(token, progress)
        selection.progress(token + 1, SourceSearchProgress(Platform.QQ, SourceSearchStatus.FAILURE, 1, 2, error = "timeout"))
        assertEquals(listOf(progress), selection.state.value.platformProgress)
        selection.offer(token, AlternativeSources(original, listOf(first)))
        assertEquals(SourceAudioQuality.UNKNOWN, selection.state.value.candidates.single().audio.quality)
        assertEquals(SourcePreviewAvailability.UNKNOWN, selection.state.value.candidates.single().audio.preview)
        assertEquals(SourcePlayability.UNKNOWN, selection.state.value.candidates.single().audio.playability)
    }
}
