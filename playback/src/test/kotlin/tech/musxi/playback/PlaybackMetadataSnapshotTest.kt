package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.emptyFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class PlaybackMetadataSnapshotTest {
    private val original = SongSummary(MediaKey(Platform.NETEASE, "1"), "Track", listOf("Artist"),
        "Album", 123_000, "https://p1.music.126.net/cover.jpg")

    @Test fun changingOnlyAlbumOrCoverRequiresMetadataReplacement() {
        val initial = PlaybackMetadataSnapshot.fromSong(original, Platform.NETEASE)
        assertEquals(original.coverUrl, initial.coverUrl)
        assertNotEquals(initial, PlaybackMetadataSnapshot.fromSong(original.copy(album = "New album"), Platform.NETEASE))
        assertNotEquals(initial, PlaybackMetadataSnapshot.fromSong(original.copy(coverUrl = null), Platform.NETEASE))
        assertNotEquals(initial, PlaybackMetadataSnapshot.fromSong(
            original.copy(coverUrl = "https://p1.music.126.net/new-cover.jpg"), Platform.NETEASE))
    }

    @Test fun substitutionAndRestorationKeepQueuePlatformWhileReplacingAllSourceDisplayData() {
        val candidate = SongSummary(MediaKey(Platform.QQ, "A0000000000001"), "Other title",
            listOf("Other artist", "Guest"), "Other album", 123_000)
        val initial = PlaybackMetadataSnapshot.fromSong(original, Platform.NETEASE)
        val selected = PlaybackMetadataSnapshot.fromSong(candidate, Platform.NETEASE)
        assertEquals(Platform.NETEASE, selected.queuePlatform)
        assertEquals(Platform.QQ, selected.sourcePlatform)
        assertEquals("Other title", selected.title)
        assertEquals("Other artist / Guest", selected.artist)
        assertEquals("Other album", selected.album)
        assertNull(selected.coverUrl) // Never carry the old source's artwork forward.
        val restored = PlaybackMetadataSnapshot.fromSong(original, Platform.NETEASE)
        assertEquals(initial, restored)
        assertEquals(Platform.NETEASE, restored.sourcePlatform)
        assertEquals("netease:1", original.key.stableId)
    }

    @Test fun sourcePlatformChangesAreObservableEvenWithIdenticalDisplayText() {
        val candidate = original.copy(key = MediaKey(Platform.QQ, "A0000000000001"), coverUrl = null)
        val initial = PlaybackMetadataSnapshot.fromSong(original.copy(coverUrl = null), Platform.NETEASE)
        val selected = PlaybackMetadataSnapshot.fromSong(candidate, Platform.NETEASE)
        assertNotEquals(initial, selected)
    }

    @Test fun resolvedOriginalReplacesPlaceholderButCandidateCannotOverwriteTheRestorationSlot() {
        val placeholder = original.copy(title = "待加载歌曲", artists = emptyList(), album = "", coverUrl = null)
        val saved = PlaybackMetadataSnapshot.refreshOriginal(placeholder, original)
        assertEquals(original, saved)
        val candidate = original.copy(key = MediaKey(Platform.QQ, "A0000000000001"),
            title = "Candidate", album = "Candidate album", coverUrl = "https://y.gtimg.cn/cover.jpg")
        val restored = PlaybackMetadataSnapshot.refreshOriginal(saved, candidate)
        assertSame(saved, restored)
        assertEquals("Album", PlaybackMetadataSnapshot.fromSong(restored, Platform.NETEASE).album)
        assertEquals(original.coverUrl, PlaybackMetadataSnapshot.fromSong(restored, Platform.NETEASE).coverUrl)
        val anotherQueueSong = original.copy(key = MediaKey(Platform.NETEASE, "2"))
        assertSame(saved, PlaybackMetadataSnapshot.refreshOriginal(saved, anotherQueueSong))
    }

    @Test fun untrustedOrCredentialBearingArtworkNeverEntersThePublicSnapshot() {
        listOf("https://untrusted.example/cover.jpg", "https://user:password@p1.music.126.net/cover.jpg",
            "https://p1.music.126.net/cover.jpg?token=secret",
            "musxi://queue/private/0", "file:///private/cover.jpg").forEach { cover ->
            assertNull(PlaybackMetadataSnapshot.fromSong(original.copy(coverUrl = cover), Platform.NETEASE).coverUrl)
        }
        // Sanitization belongs to the actual source platform, not the queue platform.
        val candidate = original.copy(key = MediaKey(Platform.QQ, "A0000000000001"))
        assertNull(PlaybackMetadataSnapshot.fromSong(candidate, Platform.NETEASE).coverUrl)
    }

    @Test fun absentOrUnknownServicePlatformsDoNotInventASource() {
        assertNull(PlaybackMetadataSnapshot.platform(null))
        assertNull(PlaybackMetadataSnapshot.platform("future-platform"))
        assertEquals(Platform.QQ, PlaybackMetadataSnapshot.platform("QQ"))
    }

    @Test fun realSourceTicketsFenceLateMetadataAcrossSubstitutionRestorationAndQueueReplacement() {
        val candidate = original.copy(key = MediaKey(Platform.QQ, "A0000000000001"),
            album = "Candidate album", coverUrl = "https://y.gtimg.cn/cover.jpg")
        val songs = listOf(original, candidate).associateBy { it.key.stableId }
        val sources = QueueSources(object : PlaybackSourceProvider {
            override val authorization = emptyFlow<Long?>()
            override fun currentGeneration() = 1L
            override fun resolve(mediaId: String, generation: Long) = StreamLease(songs.getValue(mediaId)) {
                if (mediaId.startsWith("qq:")) "https://isure.stream.qqmusic.qq.com/song.mp3"
                else "https://m.music.126.net/song.mp3"
            }
        })
        // Duplicate queue identities still get different tickets; leaving this item resets
        // only its source, so next/previous cannot inherit the substitute's metadata.
        val tickets = sources.replace(listOf(original.key.stableId, original.key.stableId), 1)
        val initial = sources.resolve(tickets[0])
        sources.selectSource(tickets[0], candidate.key.stableId, 1)
        assertFalse(initial.isCurrent())
        val selected = sources.resolve(tickets[0])
        val selectedSnapshot = PlaybackMetadataSnapshot.fromSong(selected.metadata!!, Platform.NETEASE)
        assertEquals(Platform.QQ, selectedSnapshot.sourcePlatform)
        assertEquals(candidate.album, selectedSnapshot.album)
        assertEquals(candidate.coverUrl, selectedSnapshot.coverUrl)
        val nextSnapshot = PlaybackMetadataSnapshot.fromSong(sources.resolve(tickets[1]).metadata!!, Platform.NETEASE)
        assertEquals(Platform.NETEASE, nextSnapshot.sourcePlatform)
        sources.resetSource(tickets[0])
        assertFalse(selected.isCurrent())
        val restored = sources.resolve(tickets[0])
        assertEquals(PlaybackMetadataSnapshot.fromSong(original, Platform.NETEASE),
            PlaybackMetadataSnapshot.fromSong(restored.metadata!!, Platform.NETEASE))
        sources.replace(listOf(original.key.stableId), 1)
        assertFalse(restored.isCurrent())
    }
}
