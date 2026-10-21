package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultipleTimestampsAndPreservesStableOrderAndDuplicates() {
        val parsed = LrcParser.parse(
            """
            [00:02.500][00:01.20]Hello
            [00:02.500]Again
            [00:02.500]Again
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LyricLine(1_200, "Hello"),
                LyricLine(2_500, "Hello"),
                LyricLine(2_500, "Again"),
                LyricLine(2_500, "Again")
            ),
            parsed.lines
        )
        assertEquals("Hello\nAgain\nAgain", parsed.plainText)
    }

    @Test
    fun appliesLastValidOffsetAndClampsNegativeTimes() {
        val parsed = LrcParser.parse(
            """
            [offset:+100]
            [00:00.500]Adjusted
            [offset:not-a-number]
            [offset:-700]
            [00:00.500]Clamped
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LyricLine(0, "Adjusted"),
                LyricLine(0, "Clamped")
            ),
            parsed.lines
        )
    }

    @Test
    fun supportsOneTwoAndThreeFractionDigits() {
        val parsed = LrcParser.parse(
            """
            [01:02.3]A
            [01:02.34]B
            [01:02.345]C
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LyricLine(62_300, "A"),
                LyricLine(62_340, "B"),
                LyricLine(62_345, "C")
            ),
            parsed.lines
        )
    }

    @Test
    fun acceptsTwentyFourHoursAndRejectsLongerTimestamps() {
        assertEquals(
            24L * 60L * 60L * 1_000L,
            LrcParser.parse("[1440:00.000]end").lines.single().timeMs
        )
        assertInvalid("[1440:00.001]too late")
    }

    @Test
    fun rejectsTimestampsWithAnEmptyOrLongFraction() {
        val parsed = LrcParser.parse("[00:01.]invalid\n[00:01.1234]also invalid")

        assertTrue(parsed.lines.isEmpty())
        assertEquals("[00:01.]invalid\n[00:01.1234]also invalid", parsed.plainText)
    }

    @Test
    fun removesMetadataAndBuildsCleanedPlainText() {
        val parsed = LrcParser.parse(
            "[ar:Artist]\r\n[ti:Title]\r\n\r\n[00:01]Hello\r\nWorld\r\n[00:02]Again"
        )

        assertEquals(
            listOf(
                LyricLine(1_000, "Hello"),
                LyricLine(2_000, "Again")
            ),
            parsed.lines
        )
        assertEquals("Hello\nWorld\nAgain", parsed.plainText)
    }

    @Test
    fun returnsCleanedUntimedLyricsWithoutValidTimestamps() {
        val parsed = LrcParser.parse("[by:Writer]\n\n first \n\nsecond\n[al:Album]")

        assertTrue(parsed.lines.isEmpty())
        assertEquals("first\n\nsecond", parsed.plainText)
    }

    @Test
    fun acceptsEmptyAndInstrumentalInputs() {
        assertEquals(ParsedLyrics(emptyList(), ""), LrcParser.parse(""))
        assertEquals(
            ParsedLyrics(emptyList(), ""),
            LrcParser.parse("[ti:Instrumental]\n[00:01.00]")
        )
    }

    @Test
    fun activeIndexUsesLastLineAtOrBeforePosition() {
        val lines = listOf(
            LyricLine(1_000, "one"),
            LyricLine(2_000, "two-a"),
            LyricLine(2_000, "two-b"),
            LyricLine(4_000, "four")
        )

        assertEquals(-1, LrcParser.activeIndex(emptyList(), 0))
        assertEquals(-1, LrcParser.activeIndex(lines, 999))
        assertEquals(0, LrcParser.activeIndex(lines, 1_000))
        assertEquals(2, LrcParser.activeIndex(lines, 2_500))
        assertEquals(3, LrcParser.activeIndex(lines, 4_000))
        assertEquals(3, LrcParser.activeIndex(lines, 99_000))
    }

    @Test
    fun activeIndexDoesNotModifyLyrics() {
        val parsed = LrcParser.parse("[00:02]two\n[00:01]one")
        val snapshot = parsed.lines.toList()

        LrcParser.activeIndex(parsed.lines, 1_500)

        assertEquals(snapshot, parsed.lines)
    }

    @Test
    fun returnsImmutableLyricsList() {
        val lines = LrcParser.parse("[00:01]one").lines

        try {
            @Suppress("UNCHECKED_CAST")
            (lines as MutableList<LyricLine>).add(LyricLine(2_000, "two"))
            fail("Expected an immutable list")
        } catch (_: UnsupportedOperationException) {
            assertEquals(1, lines.size)
        }
    }

    @Test
    fun rejectsControlCharactersWithFixedMessage() {
        assertInvalid("hello\u0000world")
        assertInvalid("hello\u000Bworld")
        assertInvalid("hello\u007Fworld")
        assertEquals("a\tb", LrcParser.parse("[00:01]a\tb").lines.single().text)
    }

    @Test
    fun rejectsSourceAndGeneratedLineLimitsWithFixedMessage() {
        assertInvalid(List(10_001) { "line" }.joinToString("\n"))
        assertInvalid("a".repeat(4_097))
        assertInvalid((List(30) { "[0:01]".repeat(682) + "line" }).joinToString("\n"))
    }

    @Test
    fun rejectsUtf8ByteLimitWithFixedMessage() {
        assertInvalid(
            List(129) { "汉".repeat(4_096) }.joinToString("\n")
        )
    }

    private fun assertInvalid(text: String) {
        try {
            LrcParser.parse(text)
            fail("Expected IllegalArgumentException")
        } catch (exception: IllegalArgumentException) {
            assertEquals("Invalid lyrics", exception.message)
        }
    }
}
