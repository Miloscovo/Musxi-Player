package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTranslationAlignerTest {
    private val original = """
        [00:01.00]first
        [00:02.00]second
        [00:03.00]third
    """.trimIndent()

    @Test
    fun invalidOriginalThrowsWhileUntimedOriginalsUseDistinctUnsupportedStates() {
        expectThrows<IllegalArgumentException> {
            LyricsTranslationAligner.parse("[00:01.00]broken\u0000", "[00:01.00]译文")
        }

        val unsynced = LyricsTranslationAligner.parse("plain lyrics", "[00:01.00]译文")
        assertUnsupported(unsynced, TranslationReason.NOT_SYNCED)

        val empty = LyricsTranslationAligner.parse("  \n\t", "[00:01.00]译文")
        assertUnsupported(empty, TranslationReason.NO_LYRICS)
    }

    @Test
    fun missingTranslationsAreVerifiedNoneWithoutChangingOriginalLines() {
        val expectedLines = LrcParser.parse(original).lines

        listOf(null, "", "  \r\n\t").forEach { translation ->
            val parsed = LyricsTranslationAligner.parse(original, translation)
            assertEquals(expectedLines, parsed.lines)
            assertEquals(TranslationAvailability.NONE, parsed.translation.availability)
            assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
            assertEquals(TranslationReason.NOT_PROVIDED, parsed.translation.lookup.reason)
            parsed.lines.forEach { assertNull(it.translation) }
        }
    }

    @Test
    fun exactAndPartialTranslationsAttachOnlyToTheirOriginalRows() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [00:01.00]first
                [00:02.00]second
                [00:03.00]third
                [00:04.00]fourth
                [00:05.00]fifth
            """.trimIndent(),
            """
                [00:01.00]一
                [00:02.00]二
                [00:03.00]三
                [00:05.00]五
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
        assertNull(parsed.translation.lookup.reason)
        assertEquals(
            listOf(
                LyricLine(1_000, "first", "一"),
                LyricLine(2_000, "second", "二"),
                LyricLine(3_000, "third", "三"),
                LyricLine(4_000, "fourth"),
                LyricLine(5_000, "fifth", "五"),
            ),
            parsed.lines,
        )
    }

    @Test
    fun multipleTimestampsAndDuplicateTimesKeepTheirFileOrder() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [00:01.00][00:02.00]repeated
                [00:01.00]same time
            """.trimIndent(),
            """
                [00:01.00]one
                [00:01.00]two
                [00:02.00]three
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(
            listOf(
                LyricLine(1_000, "repeated", "one"),
                LyricLine(1_000, "same time", "two"),
                LyricLine(2_000, "repeated", "three"),
            ),
            parsed.lines,
        )
    }

    @Test
    fun originalAndTranslationOffsetsAreAppliedIndependentlyBeforeMatching() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [offset:-100]
                [00:01.10]first
                [00:02.10]second
            """.trimIndent(),
            """
                [offset:100]
                [00:00.90]一
                [00:01.90]二
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(
            listOf(
                LyricLine(1_000, "first", "一"),
                LyricLine(2_000, "second", "二"),
            ),
            parsed.lines,
        )
    }

    @Test
    fun negativeTimesAreMatchedBeforeClampingToZero() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [offset:-1000]
                [00:00.50]becomes zero
            """.trimIndent(),
            "[00:00.00]also zero",
        )

        assertFailed(parsed, TranslationReason.VERSION_MISMATCH)
        assertEquals(LyricLine(0, "becomes zero"), parsed.lines.single())
    }

    @Test
    fun exactMatchesAreReservedBeforeNearbyTranslations() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [00:01.00]first
                [00:01.04]second
            """.trimIndent(),
            """
                [00:01.03]nearly second
                [00:01.04]exactly second
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(
            listOf(
                LyricLine(1_000, "first", "nearly second"),
                LyricLine(1_040, "second", "exactly second"),
            ),
            parsed.lines,
        )
    }

    @Test
    fun toleranceEndsAtOneHundredMillisecondsAndExactlyEightyPercentIsAvailable() {
        val parsed = LyricsTranslationAligner.parse(
            """
                [00:00.00]zero
                [00:01.00]one
                [00:02.00]two
                [00:03.00]three
                [00:04.00]four
            """.trimIndent(),
            """
                [00:00.10]零
                [00:01.101]too late
                [00:02.00]二
                [00:03.00]三
                [00:04.00]四
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals("零", parsed.lines[0].translation)
        assertNull(parsed.lines[1].translation)
        assertEquals("二", parsed.lines[2].translation)
        assertEquals("三", parsed.lines[3].translation)
        assertEquals("四", parsed.lines[4].translation)
    }

    @Test
    fun fewerThanEightyPercentOfTranslationRowsReportsVersionMismatch() {
        val originalText = """
            [00:00.00]zero
            [00:01.00]one
            [00:02.00]two
            [00:03.00]three
        """.trimIndent()
        val expectedLines = LrcParser.parse(originalText).lines

        val parsed = LyricsTranslationAligner.parse(
            originalText,
            """
                [00:00.00]零
                [00:01.00]一
                [00:02.00]二
                [00:03.101]too late
            """.trimIndent(),
        )

        assertFailed(parsed, TranslationReason.VERSION_MISMATCH)
        assertEquals(expectedLines, parsed.lines)
    }

    @Test
    fun unsupportedInvalidAndOversizedTranslationsDegradeWithoutThrowing() {
        val expectedLines = LrcParser.parse(original).lines
        val oversizedUtf8 = "译".repeat(90_000)
        val cases = listOf(
            "plain translation" to TranslationReason.UNSUPPORTED_FORMAT,
            "[bad]bad timestamp" to TranslationReason.UNSUPPORTED_FORMAT,
            "[00:01.00]nul\u0000translation" to TranslationReason.INVALID_DATA,
            oversizedUtf8 to TranslationReason.INVALID_DATA,
        )

        cases.forEach { (translation, reason) ->
            val parsed = LyricsTranslationAligner.parse(original, translation)
            assertFailed(parsed, reason)
            assertEquals(expectedLines, parsed.lines)
        }
    }

    @Test
    fun placeholdersAndCopiedOriginalTextAreNeverDisplayedAsTranslations() {
        val allPlaceholders = LyricsTranslationAligner.parse(
            original,
            """
                [00:01.00]//
                [00:02.00]//
                [00:03.00]//
            """.trimIndent(),
        )
        assertVerifiedNone(allPlaceholders, original)

        val partialOriginal = """
            [00:01.00]first
            [00:02.00]second
        """.trimIndent()
        val partial = LyricsTranslationAligner.parse(
            partialOriginal,
            """
                [00:01.00]//
                [00:02.00]second
            """.trimIndent(),
        )
        assertVerifiedNone(partial, partialOriginal)

        val none = LyricsTranslationAligner.parse(
            original,
            """
                [00:01.00]//
                [00:02.00]second
                [00:03.00]third
            """.trimIndent(),
        )
        assertVerifiedNone(none, original)
    }

    @Test
    fun alignedOutputPreservesTimesActiveIndexAndUnmodifiableLines() {
        val parsed = LyricsTranslationAligner.parse(
            original,
            """
                [00:01.00]一
                [00:02.00]二
                [00:03.00]三
            """.trimIndent(),
        )

        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), parsed.lines.map { it.timeMs })
        assertEquals(-1, LrcParser.activeIndex(parsed.lines, 999))
        assertEquals(0, LrcParser.activeIndex(parsed.lines, 1_000))
        assertEquals(1, LrcParser.activeIndex(parsed.lines, 2_500))
        assertEquals(2, LrcParser.activeIndex(parsed.lines, 10_000))
        expectThrows<UnsupportedOperationException> {
            (parsed.lines as MutableList<LyricLine>).add(LyricLine(4_000, "added"))
        }
    }

    @Test
    fun competingFuzzyTimestampsNeverConsumeAnotherRowsSlot() {
        val parsed = LyricsTranslationAligner.parse(
            "[00:00.000]a\n[00:00.500]b\n[00:01.000]c\n[00:01.100]d\n[00:01.200]e",
            "[00:00.000]甲\n[00:00.500]乙\n[00:01.000]丙\n[00:01.151]丁\n[00:01.201]戊",
        )
        assertFailed(parsed, TranslationReason.VERSION_MISMATCH)
        val singleSlot = LyricsTranslationAligner.parse(
            "[00:01.200]original",
            "[00:01.151]earlier\n[00:01.201]closer",
        )
        assertFailed(singleSlot, TranslationReason.VERSION_MISMATCH)
    }

    @Test
    fun copiedRowsDoNotRequireEightyPercentOfOriginalToHaveDisplayableTranslation() {
        val parsed = LyricsTranslationAligner.parse(
            "[00:01]a\n[00:02]b\n[00:03]c\n[00:04]d\n[00:05]e",
            "[00:01]a\n[00:02]b\n[00:03]c\n[00:04]d\n[00:05]译文",
        )
        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(listOf(null, null, null, null, "译文"), parsed.lines.map { it.translation })
    }

    private fun assertFailed(parsed: ParsedLyrics, reason: TranslationReason) {
        assertEquals(TranslationAvailability.UNKNOWN, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, parsed.translation.lookup.status)
        assertEquals(reason, parsed.translation.lookup.reason)
        parsed.lines.forEach { assertNull(it.translation) }
    }

    private fun assertUnsupported(parsed: ParsedLyrics, reason: TranslationReason) {
        assertEquals(TranslationAvailability.UNKNOWN, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.UNSUPPORTED, parsed.translation.lookup.status)
        assertEquals(reason, parsed.translation.lookup.reason)
        parsed.lines.forEach { assertNull(it.translation) }
    }

    private fun assertVerifiedNone(parsed: ParsedLyrics, originalText: String) {
        assertEquals(LrcParser.parse(originalText).lines, parsed.lines)
        assertEquals(TranslationAvailability.NONE, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
        assertEquals(TranslationReason.NOT_PROVIDED, parsed.translation.lookup.reason)
        parsed.lines.forEach { assertNull(it.translation) }
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }
}
