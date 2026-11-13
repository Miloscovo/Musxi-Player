package tech.musxi.player.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeResult
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistMembershipEntry
import tech.musxi.core.library.PlaylistMembershipResult
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongPlaylistMembership
import tech.musxi.core.library.SongSummary

/** The playlist sheet's state rules, against a source whose replies the test releases by hand. */
class MembershipEditorTest {

    private val song = SongSummary(MediaKey(Platform.QQ, "song"), "晴天", listOf("周杰伦"), "叶惠美", 269_000)
    private fun key(id: String) = MediaKey(Platform.QQ, id)
    private fun target(id: String, favorite: Boolean = false, generation: Long = 1) =
        EditablePlaylist(PlaylistSummary(key(id), "歌单 $id", null, 10), generation, favorite)
    private fun snapshot(vararg entries: Pair<String, Boolean>, liked: Boolean = true, generation: Long = 1) =
        SongPlaylistMembership(
            song.key, generation,
            listOf(PlaylistMembershipEntry(target("liked", favorite = true, generation = generation), liked)) +
                entries.map { (id, present) -> PlaylistMembershipEntry(target(id, generation = generation), present) },
        )

    private class FakeSource : MembershipSource {
        val reads = ArrayDeque<(Result<SongPlaylistMembership>) -> Unit>()
        val updates = ArrayDeque<Triple<SongPlaylistMembership, Map<MediaKey, Boolean>, (Result<PlaylistMembershipResult>) -> Unit>>()
        override fun read(song: MediaKey, result: (Result<SongPlaylistMembership>) -> Unit) { reads += result }
        override fun update(
            song: SongSummary, snapshot: SongPlaylistMembership, desired: Map<MediaKey, Boolean>,
            result: (Result<PlaylistMembershipResult>) -> Unit,
        ) { updates += Triple(snapshot, desired, result) }
    }

    private val source = FakeSource()
    private val settled = mutableListOf<MembershipSettled>()
    private var clock = 1_000L
    private val editor = MembershipEditor(source, { settled += it }, now = { clock })

    private fun ui() = editor.state!!
    private fun ids() = ui().rows.map { it.target.playlist.key.id }
    private fun change(id: String, wanted: Boolean, status: PlaylistChangeStatus, error: String? = null) =
        PlaylistChangeResult(key(id), wanted, status, confirmedPresent = null, error = error)
    private fun batch(vararg results: PlaylistChangeResult) = Result.success(PlaylistMembershipResult(song.key, results.toList()))

    private fun openWith(snapshot: SongPlaylistMembership, pinned: MediaKey? = null) {
        editor.open(song, pinned)
        source.reads.removeFirst()(Result.success(snapshot))
    }

    @Test fun likedIsNeverListedAndServerOrderIsKept() {
        openWith(snapshot("c" to false, "a" to true, "b" to false))
        assertEquals(listOf("c", "a", "b"), ids())
        assertEquals(listOf(false, true, false), ui().rows.map { it.checked })
    }

    @Test fun theListItWasOpenedFromComesFirst() {
        openWith(snapshot("a" to false, "b" to true), pinned = key("b"))
        assertEquals(listOf("b", "a"), ids())
        assertTrue(ui().rows.first().pinned)
    }

    @Test fun aFailedReadIsNotAnEmptyList() {
        editor.open(song)
        source.reads.removeFirst()(Result.failure(LibraryPageFailure("network")))
        assertEquals(MembershipPhase.FAILED, ui().phase)
        assertTrue(ui().rows.isEmpty())
        assertFalse(ui().canSave)
        editor.retry()
        assertEquals(MembershipPhase.LOADING, ui().phase)
        source.reads.removeFirst()(Result.success(snapshot("a" to true)))
        assertEquals(MembershipPhase.READY, ui().phase)
    }

    @Test fun aReadArrivingAfterCloseIsIgnored() {
        editor.open(song)
        editor.close()
        source.reads.removeFirst()(Result.success(snapshot("a" to true)))
        assertNull(editor.state)
    }

    @Test fun aReadForAnEarlierOpeningIsIgnored() {
        editor.open(song)
        val stale = source.reads.removeFirst()
        editor.open(song)
        stale(Result.success(snapshot("old" to true)))
        assertEquals(MembershipPhase.LOADING, ui().phase)
        source.reads.removeFirst()(Result.success(snapshot("new" to true)))
        assertEquals(listOf("new"), ids())
    }

    @Test fun onlyChangedListsAreSentAgainstTheSnapshotThatWasRead() {
        val read = snapshot("a" to true, "b" to false, "c" to true)
        openWith(read)
        assertFalse("nothing changed yet", ui().canSave)
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        assertEquals(2, ui().changeCount)
        editor.save()
        val (sentSnapshot, desired, _) = source.updates.single()
        assertTrue(sentSnapshot === read)
        assertEquals(mapOf(key("a") to false, key("b") to true), desired)
    }

    @Test fun toggleTwiceIsNoChange() {
        openWith(snapshot("a" to true))
        editor.toggle(key("a"))
        editor.toggle(key("a"))
        assertEquals(0, ui().changeCount)
        editor.save()
        assertTrue(source.updates.isEmpty())
    }

    @Test fun aSecondSaveWhileTheFirstRunsIsNotSent() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        editor.save()
        editor.toggle(key("a"))
        assertEquals(1, source.updates.size)
        assertEquals(MembershipPhase.SAVING, ui().phase)
        assertTrue("rows are frozen while saving", ui().rows.single().checked)
    }

    @Test fun aConfirmedSaveClosesAndSaysWhatChanged() {
        openWith(snapshot("a" to true, "b" to false))
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        editor.save()
        source.updates.removeFirst().third(batch(
            change("a", false, PlaylistChangeStatus.APPLIED),
            change("b", true, PlaylistChangeStatus.UNCHANGED),
        ))
        assertNull(editor.state)
        assertEquals("已加入 1 个歌单，移出 1 个", settled.single().notice)
        assertEquals(2, settled.single().results.size)
    }

    @Test fun aPartialSaveKeepsTheSheetMarksRowsAndRereads() {
        openWith(snapshot("a" to true, "b" to false, "c" to false))
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        editor.toggle(key("c"))
        editor.save()
        source.updates.removeFirst().third(batch(
            change("a", false, PlaylistChangeStatus.APPLIED),
            change("b", true, PlaylistChangeStatus.FAILED, "permission"),
            change("c", true, PlaylistChangeStatus.UNKNOWN, "network"),
        ))
        // Still open, checking against the server before allowing another edit.
        assertEquals(MembershipPhase.CHECKING, ui().phase)
        assertFalse(ui().editable)
        assertTrue(ui().message!!.startsWith("1 项已保存，2 项未完成"))
        assertNull("the shell is told nothing: the sheet says it", settled.single().notice)
        // The reread is the server's truth: c turned out to be added after all.
        source.reads.removeFirst()(Result.success(snapshot("a" to false, "b" to false, "c" to true)))
        assertEquals(MembershipPhase.READY, ui().phase)
        val rows = ui().rows.associateBy { it.target.playlist.key.id }
        assertNull("confirmed rows stay quiet", rows.getValue("a").outcome)
        assertFalse(rows.getValue("a").checked)
        assertEquals("没能加入：没有修改这个歌单的权限", rows.getValue("b").outcome!!.text)
        assertTrue(rows.getValue("c").checked)
        assertEquals(0, ui().changeCount)
    }

    @Test fun aRetryAfterAPartialSaveUsesTheNewSnapshot() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        source.updates.removeFirst().third(batch(change("a", true, PlaylistChangeStatus.FAILED, "network")))
        val fresh = snapshot("a" to false)
        source.reads.removeFirst()(Result.success(fresh))
        editor.toggle(key("a"))
        editor.save()
        assertTrue(source.updates.single().first === fresh)
    }

    @Test fun aRereadFailureAfterAPartialSaveKeepsWhatWasSaid() {
        openWith(snapshot("a" to false, "b" to false))
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        editor.save()
        source.updates.removeFirst().third(batch(
            change("a", true, PlaylistChangeStatus.APPLIED),
            change("b", true, PlaylistChangeStatus.UNKNOWN),
        ))
        source.reads.removeFirst()(Result.failure(LibraryPageFailure("network")))
        assertEquals(MembershipPhase.FAILED, ui().phase)
        assertTrue(ui().message!!.contains("1 项已保存"))
        assertTrue(ui().message!!.contains("请重试核对"))
    }

    @Test fun closingDuringASaveStillReportsToTheShell() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        editor.close()
        source.updates.removeFirst().third(batch(change("a", true, PlaylistChangeStatus.FAILED, "network")))
        assertNull(editor.state)
        assertTrue(source.reads.isEmpty())
        assertEquals("1 项都没有完成", settled.single().notice)
    }

    @Test fun aSaveFinishingAfterTheSheetMovedOnDoesNotTouchTheNewOne() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        val late = source.updates.removeFirst().third
        openWith(snapshot("x" to true))
        late(batch(change("a", true, PlaylistChangeStatus.APPLIED)))
        assertEquals(listOf("x"), ids())
        assertEquals(MembershipPhase.READY, ui().phase)
        assertEquals("已加入 1 个歌单", settled.single().notice)
    }

    @Test fun aRejectedSaveChangesNothingAndRereads() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        source.updates.removeFirst().third(Result.failure(LibraryPageFailure("busy")))
        assertEquals(MembershipPhase.CHECKING, ui().phase)
        assertEquals("上一次保存还在进行，已重新读取当前状态", ui().message)
        assertTrue(settled.single().results.isEmpty())
        assertEquals(1, source.reads.size)
    }

    @Test fun anAccountChangeDropsTheSnapshotAndReads() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.accountChanged()
        assertEquals(MembershipPhase.LOADING, ui().phase)
        assertTrue(ui().rows.isEmpty())
        editor.save()
        assertTrue("the old account's snapshot can no longer be written", source.updates.isEmpty())
        source.reads.removeFirst()(Result.success(snapshot("b" to true, generation = 2)))
        assertEquals(listOf("b"), ids())
    }

    @Test fun anAccountChangeDuringASaveIgnoresItsLateResultInTheSheet() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        val late = source.updates.removeFirst().third
        editor.accountChanged()
        late(batch(change("a", true, PlaylistChangeStatus.UNKNOWN, "account_changed")))
        assertEquals(MembershipPhase.LOADING, ui().phase)
        assertEquals(1, source.reads.size)
        assertEquals("1 项都没有完成；有结果未确认，请重新打开歌单核对", settled.single().notice)
    }

    @Test fun conflictAndUnknownSayToCheckAgain() {
        // Only a failure is red; a conflict or an unknown result asks the user to look again.
        assertEquals(false, outcomeOf(change("a", true, PlaylistChangeStatus.CONFLICT))?.warning)
        assertEquals(false, outcomeOf(change("a", true, PlaylistChangeStatus.UNKNOWN))?.warning)
        assertEquals(true, outcomeOf(change("a", true, PlaylistChangeStatus.FAILED, "permission"))?.warning)
        assertTrue(outcomeOf(change("a", true, PlaylistChangeStatus.UNKNOWN))!!.text.contains("未确认"))
        assertNull(outcomeOf(change("a", true, PlaylistChangeStatus.APPLIED)))
        assertNull(outcomeOf(change("a", false, PlaylistChangeStatus.UNCHANGED)))
    }

    @Test fun aSettledResultCarriesItsAccountAndStartTime() {
        // The shell drops results for an account that has since changed, and does not count a change
        // twice on a list that was read after the save began.
        openWith(snapshot("a" to false, generation = 4))
        editor.toggle(key("a"))
        clock = 5_000
        editor.save()
        clock = 9_000
        source.updates.removeFirst().third(batch(change("a", true, PlaylistChangeStatus.APPLIED)))
        assertEquals(4L, settled.single().accountGeneration)
        assertEquals(5_000L, settled.single().startedAtMs)
    }

    @Test fun anAccountChangeAfterAPartialSaveStillSaysWhatWasSaved() {
        openWith(snapshot("a" to false, "b" to false))
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        editor.save()
        source.updates.removeFirst().third(batch(
            change("a", true, PlaylistChangeStatus.APPLIED),
            change("b", true, PlaylistChangeStatus.FAILED, "network"),
        ))
        editor.accountChanged()
        assertEquals("1 项已保存，1 项未完成；账号已变化，已重新读取", ui().message)
    }

    @Test fun aRetryAfterAFailedCheckStillSaysWhatWasSaved() {
        openWith(snapshot("a" to false, "b" to false))
        editor.toggle(key("a"))
        editor.toggle(key("b"))
        editor.save()
        source.updates.removeFirst().third(batch(
            change("a", true, PlaylistChangeStatus.APPLIED),
            change("b", true, PlaylistChangeStatus.UNKNOWN),
        ))
        source.reads.removeFirst()(Result.failure(LibraryPageFailure("network")))
        editor.retry()
        assertEquals("1 项已保存，1 项未完成", ui().message)
        assertFalse(ui().messageWarning)
    }

    @Test fun anUnknownResultIsReadAgainNeverWrittenAgain() {
        openWith(snapshot("a" to false))
        editor.toggle(key("a"))
        editor.save()
        source.updates.removeFirst().third(batch(change("a", true, PlaylistChangeStatus.UNKNOWN, "network")))
        source.reads.removeFirst()(Result.success(snapshot("a" to false)))
        assertTrue("no automatic replay", source.updates.isEmpty())
        assertEquals(MembershipPhase.READY, ui().phase)
        assertEquals(0, ui().changeCount)
    }

    @Test fun noInternalReasonReachesTheUser() {
        listOf("permission", "network", "rate", "not_confirmed", "verification_required", "weird_new_code", null).forEach {
            assertFalse(writeFailureText(it).contains("_"))
        }
        listOf("account_changed", "limit", "network", "risk", "protocol").forEach {
            assertFalse(readFailureText(it).contains("_"))
        }
    }
}
