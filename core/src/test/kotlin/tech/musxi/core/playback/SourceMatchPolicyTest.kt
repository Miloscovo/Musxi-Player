package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class SourceMatchPolicyTest {
    private val original = SongSummary(MediaKey(Platform.QQ, "A0000000000001"), "AIZO",
        listOf("Artist", "Guest"), "Album", 180_000)
    private val candidate = original.copy(key = MediaKey(Platform.NETEASE, "1"))
    private fun reason(song: SongSummary) = SourceMatchPolicy.assess(original, song)

    @Test fun completeMatchingMetadataWithUnicodeWhitespaceAndReorderedArtistsIsEligible() {
        assertEquals(SourceMatchReason.ELIGIBLE,
            reason(candidate.copy(title = " ａｉｚｏ ", artists = listOf(" guest ", "Ａｒｔｉｓｔ"), durationMs = 182_000)))
    }
    @Test fun differentSingerOrMissingCollaboratorNeverAutoSelects() {
        for (artists in listOf(listOf("Other"), listOf("Artist"), listOf("Artist & Guest")))
            assertEquals(SourceMatchReason.ARTIST_MISMATCH, reason(candidate.copy(artists = artists)))
    }
    @Test fun missingAndUnknownArtistsRequireManualChoiceOnEitherSide() {
        for (artists in listOf(emptyList(), listOf(""), listOf(" "), listOf("Unknown Artist"),
            listOf("Artist", "未知歌手"), listOf("群星"), listOf("未知艺术家"), listOf("未知艺人"),
            listOf("未知演唱者"), listOf("Various Artist"), listOf("N/A"), listOf("-"))) {
            assertEquals(SourceMatchReason.ARTIST_UNKNOWN, reason(candidate.copy(artists = artists)))
            assertEquals(SourceMatchReason.ARTIST_UNKNOWN,
                SourceMatchPolicy.assess(original.copy(artists = artists), candidate))
        }
    }
    @Test fun unknownDurationOnEitherSideRequiresManualChoice() {
        for (duration in listOf(0L, -1L)) {
            assertEquals(SourceMatchReason.DURATION_UNKNOWN, reason(candidate.copy(durationMs = duration)))
            assertEquals(SourceMatchReason.DURATION_UNKNOWN,
                SourceMatchPolicy.assess(original.copy(durationMs = duration), candidate))
        }
    }
    @Test fun bothAbsoluteAndRelativeDurationBoundsApplyInclusively() {
        assertEquals(SourceMatchReason.ELIGIBLE, reason(candidate.copy(durationMs = 183_000)))
        assertEquals(SourceMatchReason.DURATION_MISMATCH, reason(candidate.copy(durationMs = 183_001)))
        val short = original.copy(durationMs = 50_000)
        assertEquals(SourceMatchReason.ELIGIBLE, SourceMatchPolicy.assess(short, candidate.copy(durationMs = 51_000)))
        assertEquals(SourceMatchReason.DURATION_MISMATCH, SourceMatchPolicy.assess(short, candidate.copy(durationMs = 51_001)))
        assertEquals(SourceMatchReason.DURATION_MISMATCH, reason(candidate.copy(durationMs = Long.MAX_VALUE)))
    }
    @Test fun versionMarkersInEitherAlbumRequireConfirmationEvenWhenTitlesMatch() {
        for (label in listOf("Live", "现场版", "Concert Live 2020", "Cover", "翻唱", "伴奏", "纯音乐",
            "Remastered", "Remix", "Acoustic", "Re-recorded", "Demo", "Sped Up", "Slowed", "Radio Edit", "重录",
            "TV Size", "TV版", "DJ版", "Piano Version", "off vocal", "カバー", "ライブ")) {
            assertEquals(label, SourceMatchReason.VERSION_REQUIRES_CONFIRMATION, reason(candidate.copy(album = label)))
            assertEquals(label, SourceMatchReason.VERSION_REQUIRES_CONFIRMATION,
                SourceMatchPolicy.assess(original.copy(album = label), candidate))
        }
    }
    @Test fun identicalExplicitVersionsStillRequireManualChoice() {
        val live = original.copy(title = "AIZO (Live)")
        assertEquals(SourceMatchReason.VERSION_REQUIRES_CONFIRMATION,
            SourceMatchPolicy.assess(live, candidate.copy(title = live.title)))
    }
    @Test fun embeddedEnglishSyllablesAreNotVersionLabelsAndTitlePunctuationIsPreserved() {
        assertEquals(SourceMatchReason.ELIGIBLE, reason(candidate.copy(album = "Olive Discover")))
        assertEquals(SourceMatchReason.TITLE_MISMATCH, reason(candidate.copy(title = "A-I-Z-O")))
    }
}
