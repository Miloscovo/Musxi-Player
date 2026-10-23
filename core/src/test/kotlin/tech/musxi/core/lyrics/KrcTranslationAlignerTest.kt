package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KrcTranslationAlignerTest {
    @Test
    fun attachesOnlyAfterFullVerificationAndPreservesOriginalTimeline() {
        val original = """
            [offset:-100]
            [00:02.00]second  line
            [00:01.10]first
        """.trimIndent()
        val expected = LrcParser.parse(original)

        val parsed = KrcTranslationAligner.parse(
            original,
            listOf(
                KrcTranslationAligner.Row(2_000, " second line ", "二"),
                KrcTranslationAligner.Row(1_000, "first", "一"),
            ),
        )

        assertEquals(expected.plainText, parsed.plainText)
        assertEquals(listOf(1_000L, 1_900L), parsed.lines.map { it.timeMs })
        assertEquals(listOf("first", "second  line"), parsed.lines.map { it.text })
        assertEquals(listOf("一", "二"), parsed.lines.map { it.translation })
        assertAvailable(parsed)
        assertTrue(unmodifiable(parsed.lines))
    }

    @Test
    fun duplicateTimesUseInputOrderForBothSides() {
        val parsed = KrcTranslationAligner.parse(
            "[00:01.00]first\n[00:01.00]second",
            listOf(
                KrcTranslationAligner.Row(1_000, "second", "二"),
                KrcTranslationAligner.Row(1_000, "first", "一"),
            ),
        )

        assertMismatch(parsed)
        assertTrue(parsed.lines.all { it.translation == null })

        val ordered = KrcTranslationAligner.parse(
            "[00:01.00]first\n[00:01.00]second",
            listOf(
                KrcTranslationAligner.Row(1_000, "first", "一"),
                KrcTranslationAligner.Row(1_000, "second", "二"),
            ),
        )
        assertEquals(listOf("一", "二"), ordered.lines.map { it.translation })
    }

    @Test
    fun signedTimesAvoidFalseMatchesAfterDisplayClamping() {
        val accepted = KrcTranslationAligner.parse(
            "[offset:-1000]\n[00:00.50]negative",
            listOf(KrcTranslationAligner.Row(-400, "negative", "负")),
        )
        assertEquals(0L, accepted.lines.single().timeMs)
        assertEquals("负", accepted.lines.single().translation)

        val displayedAtZero = KrcTranslationAligner.parse(
            "[offset:-1000]\n[00:00.50]negative",
            listOf(KrcTranslationAligner.Row(0, "negative", "假匹配")),
        )
        assertMismatch(displayedAtZero)
    }

    @Test
    fun toleranceIncludesExactlyOneHundredMilliseconds() {
        val within = KrcTranslationAligner.parse(
            "[00:01.00]line",
            listOf(KrcTranslationAligner.Row(1_100, "line", "译")),
        )
        assertEquals("译", within.lines.single().translation)

        val outside = KrcTranslationAligner.parse(
            "[00:01.00]line",
            listOf(KrcTranslationAligner.Row(1_101, "line", "译")),
        )
        assertMismatch(outside)
    }

    @Test
    fun textAndCountMismatchesRejectTheWholeDocument() {
        val differentText = KrcTranslationAligner.parse(
            "[00:01.00]first\n[00:02.00]second",
            listOf(
                KrcTranslationAligner.Row(1_000, "changed", "一"),
                KrcTranslationAligner.Row(2_000, "second", "二"),
            ),
        )
        assertMismatch(differentText)

        val missingRow = KrcTranslationAligner.parse(
            "[00:01.00]first\n[00:02.00]second",
            listOf(KrcTranslationAligner.Row(1_000, "first", "一")),
        )
        assertMismatch(missingRow)

        val emptyRows = KrcTranslationAligner.parse(
            "[00:01.00]first",
            emptyList(),
        )
        assertMismatch(emptyRows)
    }

    @Test
    fun blankPlaceholderAndCopiedTranslationsAreHidden() {
        val original = """
            [00:01.00]first    song
            [00:02.00]second
            [00:03.00]third
            [00:04.00]fourth
        """.trimIndent()
        val expected = LrcParser.parse(original)
        val parsed = KrcTranslationAligner.parse(
            original,
            listOf(
                KrcTranslationAligner.Row(1_000, " first song ", "first song"),
                KrcTranslationAligner.Row(2_000, "second", "  "),
                KrcTranslationAligner.Row(3_000, "third", " // "),
                KrcTranslationAligner.Row(4_000, "fourth", " 四 "),
            ),
        )

        assertEquals(expected.plainText, parsed.plainText)
        assertEquals(listOf(null, null, null, "四"), parsed.lines.map { it.translation })
        assertAvailable(parsed)
    }

    @Test
    fun verifiedRowsWithoutDisplayableTranslationsReturnNone() {
        val original = "[00:01.00]first\n[00:02.00]second"
        val parsed = KrcTranslationAligner.parse(
            original,
            listOf(
                KrcTranslationAligner.Row(1_000, "first", null),
                KrcTranslationAligner.Row(2_000, "second", "//"),
            ),
        )

        assertEquals(LrcParser.parse(original).lines, parsed.lines)
        assertEquals(TranslationAvailability.NONE, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
        assertEquals(TranslationReason.NOT_PROVIDED, parsed.translation.lookup.reason)
    }

    @Test
    fun invalidRowsUseInvalidDataAndNeverAttach() {
        val original = "[00:01.00]line"
        val invalidRows = listOf(
            listOf(KrcTranslationAligner.Row(1_000, "line", "bad\u0000text")),
            listOf(KrcTranslationAligner.Row(1_000, "line", "two\nlines")),
            listOf(KrcTranslationAligner.Row(1_000, "line", "译".repeat(4_097))),
            listOf(KrcTranslationAligner.Row(1_000, "line", null).copy(timeMs = -86_400_001)),
            listOf(KrcTranslationAligner.Row(1_000, "line", null).copy(timeMs = 172_800_001)),
            List(10_001) { KrcTranslationAligner.Row(1_000, "line") },
        )

        invalidRows.forEach { rows ->
            val parsed = KrcTranslationAligner.parse(original, rows)
            assertInvalidData(parsed)
            assertEquals(LyricLine(1_000, "line"), parsed.lines.single())
        }
    }

    @Test
    fun aggregateUtf8LimitsAreEnforced() {
        val original = "[00:01.00]line"
        val tooMuchTranslationData = List(22) {
            KrcTranslationAligner.Row(1_000, "line", "译".repeat(4_096))
        }
        assertInvalidData(KrcTranslationAligner.parse(original, tooMuchTranslationData))

        val tooMuchSourceData = List(129) {
            KrcTranslationAligner.Row(1_000, "a".repeat(4_096))
        }
        assertInvalidData(KrcTranslationAligner.parse(original, tooMuchSourceData))
    }

    @Test
    fun untimedOriginalsKeepExistingUnsupportedSemantics() {
        val plain = KrcTranslationAligner.parse(
            "plain lyrics",
            listOf(KrcTranslationAligner.Row(1_000, "invalid time", "译")),
        )
        assertEquals(TranslationLookupStatus.UNSUPPORTED, plain.translation.lookup.status)
        assertEquals(TranslationReason.NOT_SYNCED, plain.translation.lookup.reason)

        val empty = KrcTranslationAligner.parse(
            " \n ",
            listOf(KrcTranslationAligner.Row(1_000, "invalid time", "译")),
        )
        assertEquals(TranslationLookupStatus.UNSUPPORTED, empty.translation.lookup.status)
        assertEquals(TranslationReason.NO_LYRICS, empty.translation.lookup.reason)
    }

    @Test
    fun invalidOriginalStillThrows() {
        val thrown = try {
            KrcTranslationAligner.parse(
                "[00:01.00]broken\u0000",
                listOf(KrcTranslationAligner.Row(1_000, "broken", "译")),
            )
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue(thrown is IllegalArgumentException)
    }

    private fun assertAvailable(parsed: ParsedLyrics) {
        assertEquals(TranslationAvailability.AVAILABLE, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
        assertNull(parsed.translation.lookup.reason)
    }

    private fun assertMismatch(parsed: ParsedLyrics) {
        assertEquals(TranslationAvailability.UNKNOWN, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, parsed.translation.lookup.status)
        assertEquals(TranslationReason.VERSION_MISMATCH, parsed.translation.lookup.reason)
    }

    private fun assertInvalidData(parsed: ParsedLyrics) {
        assertEquals(TranslationAvailability.UNKNOWN, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, parsed.translation.lookup.status)
        assertEquals(TranslationReason.INVALID_DATA, parsed.translation.lookup.reason)
    }

    private fun unmodifiable(lines: List<LyricLine>): Boolean = try {
        @Suppress("UNCHECKED_CAST")
        (lines as MutableList<LyricLine>).clear()
        false
    } catch (_: UnsupportedOperationException) {
        true
    }
}
