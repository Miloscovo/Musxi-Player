package tech.musxi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QualitySelectionTest {
    @Test fun bestFallsBackWhenPlatformMaximumIsRejected() {
        val requested = mutableListOf<String>()

        val selected = resolvePreferredQuality("kugou", "kugou:best", null,
            category = ::failureCategory,
            request = { quality ->
                requested += quality
                if (quality == "kugou:high") throw CategorizedFailure("membership")
                quality
            })

        assertEquals("kugou:flac", selected)
        assertEquals(listOf("kugou:high", "kugou:flac"), requested)
    }

    @Test fun networkFailureStopsInsteadOfDowngradingQuality() {
        val requested = mutableListOf<String>()
        val failure = CategorizedFailure("network")

        val thrown = assertThrows(CategorizedFailure::class.java) {
            resolvePreferredQuality("kugou", "kugou:best", null,
                category = ::failureCategory,
                request = { quality ->
                    requested += quality
                    throw failure
                })
        }

        assertSame(failure, thrown)
        assertEquals(listOf("kugou:high"), requested)
    }

    @Test fun exactQualityNeverFallsBack() {
        val requested = mutableListOf<String>()
        val failure = CategorizedFailure("permission")

        val thrown = assertThrows(CategorizedFailure::class.java) {
            resolvePreferredQuality("netease", "netease:hires", "netease:hires",
                category = ::failureCategory,
                request = { quality ->
                    requested += quality
                    throw failure
                })
        }

        assertSame(failure, thrown)
        assertEquals(listOf("netease:hires"), requested)
    }

    @Test fun hiresRejectionFallsBackToLossless() {
        val requested = mutableListOf<String>()

        val selected = resolvePreferredQuality("netease", "netease:hires", null,
            category = ::failureCategory,
            request = { quality ->
                requested += quality
                if (quality == "netease:hires") throw CategorizedFailure("copyright")
                quality
            })

        assertEquals("netease:lossless", selected)
        assertEquals(listOf("netease:hires", "netease:lossless"), requested)
    }

    private fun failureCategory(error: Throwable): String? =
        (error as? CategorizedFailure)?.category

    private class CategorizedFailure(val category: String) : Exception(category)
}
