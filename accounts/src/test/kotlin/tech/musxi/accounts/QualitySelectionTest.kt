package tech.musxi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QualitySelectionTest {
    @Test fun cancellationStopsDowngradeForEveryPlatform() {
        for (platform in listOf("netease", "qq", "kugou")) {
            var active = true
            var requests = 0
            val failure = assertThrows(tech.musxi.core.library.LibraryPageFailure::class.java) {
                resolvePreferredQuality(platform, "$platform:best", null, ::failureCategory,
                    isCurrent = { active }) {
                    requests++
                    active = false
                    throw CategorizedFailure("membership")
                }
            }
            assertEquals("cancelled", failure.category)
            assertEquals(1, requests)
        }
    }

    @Test fun cancelledQueuedRequestNeverResolvesExactOrPreferredQuality() {
        for (exact in listOf(null, "netease:standard")) {
            val failure = assertThrows(tech.musxi.core.library.LibraryPageFailure::class.java) {
                resolvePreferredQuality("netease", "netease:best", exact, ::failureCategory,
                    isCurrent = { false }) { throw AssertionError("Cancelled request reached platform") }
            }
            assertEquals("cancelled", failure.category)
        }
    }

    @Test fun riskAndRateFailuresNeverDowngradeAcrossPlatforms() {
        for (platform in listOf("netease", "qq", "kugou")) for (category in listOf("risk", "rate", "expired", "timeout")) {
            var requests = 0
            val original = CategorizedFailure(category)
            val failure = assertThrows(CategorizedFailure::class.java) {
                resolvePreferredQuality(platform, "$platform:best", null, ::failureCategory) {
                    requests++
                    throw original
                }
            }
            assertSame(original, failure)
            assertEquals(1, requests)
        }
    }

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
