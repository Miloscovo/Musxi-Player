package tech.musxi.core.localaudio

import org.junit.Assert.*
import org.junit.Test

class LocalAudioNamesTest {
    @Test fun numericTracksUseNaturalOrderWithoutIntegerOverflow() {
        val source = listOf("10.mp3", "2.mp3", "1.mp3", "999999999999999999999999999999999.mp3", "1000000000000000000000000000000000.mp3")
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3") + source.takeLast(2), source.sortedWith(LocalAudioNames))
    }
    @Test fun leadingZerosAndCaseHaveDeterministicOrder() {
        val names = listOf("第10章", "第02章", "第1章", "第2章", "A1", "a1")
        val sorted = names.sortedWith(LocalAudioNames)
        assertEquals(listOf("A1", "a1", "第1章", "第02章", "第2章", "第10章"), sorted)
        for (a in names) for (b in names) assertEquals(-Integer.signum(LocalAudioNames.compare(a, b)), Integer.signum(LocalAudioNames.compare(b, a)))
    }
    @Test fun metadataNamesAreBoundedAndFallbackWorksForDotOnlyName() {
        assertNull(LocalAudioNames.clean(" \n\t"))
        assertEquals("你好", LocalAudioNames.clean("\n你好\u0000 "))
        val item = LocalAudioItem(LocalAudioLimits.newId(), LocalAudioMetadata(".opus"), LocalImportMode.COPY, addedAtMs = 0)
        assertEquals(".opus", item.title)
        assertEquals(LocalAudioLimits.MAX_NAME, LocalAudioNames.clean("a".repeat(2000))!!.length)
    }
}
