package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistMembershipTest {
    private val song = SongSummary(MediaKey(Platform.QQ, "song"), "Song", listOf("Artist"), "Album", 1)
    private fun target(id: String) = EditablePlaylist(PlaylistSummary(MediaKey(Platform.QQ, id), id, null, 0), 7, false)
    private class Port(val targets: List<EditablePlaylist>) : PlaylistMembershipPort {
        var activeGeneration: Long? = 7
        val values = targets.associate { it.playlist.key to false }.toMutableMap()
        val writes = mutableListOf<Pair<MediaKey, Boolean>>()
        var write: ((EditablePlaylist, Boolean, (Result<Unit>) -> Unit) -> Unit)? = null
        var read: ((EditablePlaylist, (Result<Boolean>) -> Unit) -> Unit)? = null
        override fun generation(platform: Platform) = activeGeneration
        override fun listWritable(platform: Platform, result: (Result<List<EditablePlaylist>>) -> Unit) = result(Result.success(targets))
        override fun contains(song: MediaKey, target: EditablePlaylist, result: (Result<Boolean>) -> Unit) {
            read?.invoke(target, result) ?: result(Result.success(values.getValue(target.playlist.key)))
        }
        override fun setPresent(song: SongSummary, target: EditablePlaylist, present: Boolean, result: (Result<Unit>) -> Unit) {
            writes += target.playlist.key to present
            val action = write
            if (action != null) action(target, present, result)
            else { values[target.playlist.key] = present; result(Result.success(Unit)) }
        }
    }
    private fun snapshot(port: Port) = SongPlaylistMembership(song.key, 7,
        port.targets.map { PlaylistMembershipEntry(it, port.values.getValue(it.playlist.key)) })
    private fun update(port: Port, snap: SongPlaylistMembership, desired: Map<MediaKey, Boolean>): Result<PlaylistMembershipResult> {
        var result: Result<PlaylistMembershipResult>? = null
        PlaylistMembershipCoordinator(port).update(song, snap, desired) { result = it }
        return requireNotNull(result)
    }
    @Test fun onlyExplicitListsChangeAndHeartIsIndependent() {
        val a=target("a"); val b=target("b"); val heart=target("heart").copy(favorite=true)
        val port=Port(listOf(a,b,heart)); port.values[a.playlist.key]=true; port.values[heart.playlist.key]=true
        val result=update(port,snapshot(port), linkedMapOf(a.playlist.key to false,b.playlist.key to true)).getOrThrow()
        assertTrue(result.successful); assertEquals(2,port.writes.size)
        assertTrue(port.values.getValue(heart.playlist.key)); assertFalse(port.values.getValue(a.playlist.key)); assertTrue(port.values.getValue(b.playlist.key))
    }
    @Test fun repeatedDesiredStateDoesNotWriteAgain() {
        val a=target("a"); val port=Port(listOf(a)); val snap=snapshot(port)
        update(port,snap,mapOf(a.playlist.key to true)).getOrThrow()
        val repeated=update(port,snap,mapOf(a.playlist.key to true)).getOrThrow()
        assertEquals(1,port.writes.size); assertEquals(PlaylistChangeStatus.UNCHANGED,repeated.results.single().status)
    }
    @Test fun unchangedCheckboxDoesNotUndoRemoteChange() {
        val a=target("a"); val port=Port(listOf(a)); val snap=snapshot(port); port.values[a.playlist.key]=true
        val result=update(port,snap,mapOf(a.playlist.key to false)).getOrThrow()
        assertEquals(PlaylistChangeStatus.CONFLICT,result.results.single().status); assertTrue(port.writes.isEmpty())
    }
    @Test fun failedTargetDoesNotDiscardSuccessAndRetryOnlyFailedTarget() {
        val a=target("a"); val b=target("b"); val port=Port(listOf(a,b)); val snap=snapshot(port)
        port.write={ t,v,done -> if(t==b) done(Result.failure(LibraryPageFailure("permission")))
            else {port.values[t.playlist.key]=v;done(Result.success(Unit))} }
        val result=update(port,snap,linkedMapOf(a.playlist.key to true,b.playlist.key to true)).getOrThrow()
        assertEquals(listOf(PlaylistChangeStatus.APPLIED,PlaylistChangeStatus.FAILED),result.results.map { it.status })
        assertTrue(port.values.getValue(a.playlist.key)); port.write=null
        update(port,snap,mapOf(b.playlist.key to true)).getOrThrow()
        assertEquals(1,port.writes.count {it.first==a.playlist.key})
    }
    @Test fun lostWriteResponseIsRecoveredByRead() {
        val a=target("a");val port=Port(listOf(a))
        port.write={t,v,done -> port.values[t.playlist.key]=v; done(Result.failure(LibraryPageFailure("timeout")))}
        assertTrue(update(port,snapshot(port),mapOf(a.playlist.key to true)).getOrThrow().successful)
    }
    @Test fun unreadableWriteOutcomeIsUnknownAndNeverReportedSuccessful() {
        val a=target("a");val port=Port(listOf(a))
        port.write={_,_,done -> port.read={_,r -> r(Result.failure(LibraryPageFailure("network")))};done(Result.success(Unit))}
        val result=update(port,snapshot(port),mapOf(a.playlist.key to true)).getOrThrow()
        assertFalse(result.successful); assertEquals(PlaylistChangeStatus.UNKNOWN,result.results.single().status)
    }
    @Test fun accountChangeAfterWriteStopsRemainingTargetsAndLeavesOutcomeUnknown() {
        val a=target("a");val b=target("b");val port=Port(listOf(a,b))
        port.write={_,_,done -> port.activeGeneration=8;done(Result.success(Unit))}
        val result=update(port,snapshot(port),linkedMapOf(a.playlist.key to true,b.playlist.key to true)).getOrThrow()
        assertEquals(listOf(PlaylistChangeStatus.UNKNOWN,PlaylistChangeStatus.FAILED),result.results.map {it.status})
        assertEquals(1,port.writes.size)
    }
    @Test fun staleGenerationCrossPlatformAndUnlistedTargetsNeverWrite() {
        val a=target("a");val port=Port(listOf(a));val snap=snapshot(port)
        assertTrue(update(port,snap,mapOf(MediaKey(Platform.NETEASE,"1") to true)).isFailure)
        assertTrue(update(port,snap,mapOf(target("unknown").playlist.key to true)).isFailure)
        port.activeGeneration=8;assertTrue(update(port,snap,mapOf(a.playlist.key to true)).isFailure)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun incompleteMembershipReadNeverReturnsPartialSnapshot() {
        val a=target("a");val b=target("b");val port=Port(listOf(a,b))
        port.read={t,done -> if(t==a) done(Result.success(true)) else done(Result.failure(LibraryPageFailure("incomplete")))}
        var result:Result<SongPlaylistMembership>?=null
        PlaylistMembershipCoordinator(port).read(song.key){result=it}
        assertEquals("incomplete",(result!!.exceptionOrNull() as LibraryPageFailure).category)
    }
    @Test fun overlappingWritesAreRejectedUntilFirstOperationFinishes() {
        val a=target("a");val port=Port(listOf(a));val snap=snapshot(port);val coordinator=PlaylistMembershipCoordinator(port)
        var resume:((Result<Unit>)->Unit)?=null
        port.write={_,_,done -> resume=done}
        coordinator.update(song,snap,mapOf(a.playlist.key to true)){}
        var second:Result<PlaylistMembershipResult>?=null
        coordinator.update(song,snap,mapOf(a.playlist.key to true)){second=it}
        assertEquals("busy",(second!!.exceptionOrNull() as LibraryPageFailure).category)
        port.values[a.playlist.key]=true;resume!!(Result.success(Unit))
        coordinator.update(song,snap,mapOf(a.playlist.key to true)){second=it}
        assertTrue(requireNotNull(second).getOrThrow().successful)
    }
    @Test fun serverAcknowledgementWithoutMembershipChangeIsFailure() {
        val a=target("a");val port=Port(listOf(a))
        port.write={_,_,done -> done(Result.success(Unit))}
        val result=update(port,snapshot(port),mapOf(a.playlist.key to true)).getOrThrow()
        assertEquals(PlaylistChangeStatus.FAILED,result.results.single().status)
        assertEquals("not_confirmed",result.results.single().error)
    }
    @Test fun snapshotReadFailsWhenAccountChangesMidRead() {
        val a=target("a");val port=Port(listOf(a))
        port.read={_,done -> port.activeGeneration=8;done(Result.success(false))}
        var result:Result<SongPlaylistMembership>?=null
        PlaylistMembershipCoordinator(port).read(song.key){result=it}
        assertEquals("account_changed",(result!!.exceptionOrNull() as LibraryPageFailure).category)
    }
    @Test fun failedPreReadDoesNotWriteAndOtherTargetsStillProceed() {
        val a=target("a");val b=target("b");val port=Port(listOf(a,b))
        port.read={t,done -> if(t==a) done(Result.failure(LibraryPageFailure("permission")))
            else done(Result.success(port.values.getValue(t.playlist.key))) }
        val result=update(port,snapshot(port),linkedMapOf(a.playlist.key to true,b.playlist.key to true)).getOrThrow()
        assertEquals(listOf(PlaylistChangeStatus.FAILED,PlaylistChangeStatus.APPLIED),result.results.map{it.status})
        assertEquals(listOf(b.playlist.key to true),port.writes)
    }

}
