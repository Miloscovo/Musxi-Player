package tech.musxi.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {
    @Test
    fun updateAvailableReturnsVersionNotesAndValidatedReleasePage() {
        val fixture = Fixture(
            initialFetch = ReleaseFetch.Success(
                listOf(
                    release("v0.1.5", "older"),
                    release("v0.2.0", "## Changes\n- Faster", "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0"),
                ),
            ),
        )

        val result = fixture.check()

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                version = "v0.2.0",
                notes = "## Changes\n- Faster",
                releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
            ),
            result,
        )
        assertEquals(1, fixture.calls)
    }

    @Test
    fun noUpdateIgnoresDraftsMalformedTagsAndExcludedPrereleases() {
        val fixture = Fixture(
            initialFetch = ReleaseFetch.Success(
                listOf(
                    release("v0.3.0", draft = true),
                    release("nightly-latest"),
                    release("v0.1.0-alpha04", prerelease = true),
                ),
            ),
        )

        assertEquals(UpdateCheckResult.NoUpdate, fixture.check(includePrerelease = false))
    }

    @Test
    fun rateLimitedResultBacksOffUntilRetryAfter() {
        val fixture = Fixture(
            initialFetch = ReleaseFetch.RateLimited(retryAfterSeconds = 90),
        )
        val first = fixture.check()
        assertTrue(first is UpdateCheckResult.RateLimited)
        assertEquals(fixture.now + 90_000L, (first as UpdateCheckResult.RateLimited).retryAtEpochMillis)
        assertEquals(1, fixture.calls)

        fixture.now += 89_999L
        val waiting = fixture.check()
        assertEquals(first, waiting)
        assertEquals(1, fixture.calls)

        fixture.now += 1L
        fixture.fetch = ReleaseFetch.Success(
            listOf(release("v0.2.0", page = releasePage("v0.2.0"))),
        )
        assertTrue(fixture.check() is UpdateCheckResult.UpdateAvailable)
        assertEquals(2, fixture.calls)
    }

    @Test
    fun failedResultIsThrottledForThirtySeconds() {
        val fixture = Fixture(initialFetch = ReleaseFetch.Failed)

        assertEquals(UpdateCheckResult.Failed, fixture.check())
        assertEquals(UpdateCheckResult.Failed, fixture.check())
        assertEquals(1, fixture.calls)

        fixture.now += 30_000L
        fixture.fetch = ReleaseFetch.Success(emptyList())
        assertEquals(UpdateCheckResult.NoUpdate, fixture.check())
        assertEquals(2, fixture.calls)
    }

    @Test
    fun prereleaseOptionAndSemverOrderingSelectTheNewestEligibleRelease() {
        val alpha04 = release(
            "0.1.0-alpha04",
            prerelease = true,
            page = releasePage("0.1.0-alpha04"),
        )
        val fixture = Fixture(initialFetch = ReleaseFetch.Success(listOf(alpha04)))

        assertEquals(UpdateCheckResult.NoUpdate, fixture.check(includePrerelease = false))
        assertEquals(1, fixture.calls)

        val available = fixture.check(includePrerelease = true)
        assertEquals(
            "0.1.0-alpha04",
            (available as UpdateCheckResult.UpdateAvailable).version,
        )
        assertEquals(1, fixture.calls)
    }

    @Test
    fun stableVersionAndShortVFormCompareAboveCurrentPrerelease() {
        val shortStable = Fixture(
            currentVersion = "v0.1",
            initialFetch = ReleaseFetch.Success(
                listOf(
                    release(
                        "0.1.0-alpha10",
                        prerelease = true,
                        page = releasePage("0.1.0-alpha10"),
                    ),
                ),
            ),
        )
        assertEquals(UpdateCheckResult.NoUpdate, shortStable.check(includePrerelease = true))

        val alphaFixture = Fixture(
            initialFetch = ReleaseFetch.Success(
                listOf(
                    release("v0.2", page = releasePage("v0.2")),
                ),
            ),
        )
        assertEquals(
            "v0.2",
            (alphaFixture.check() as UpdateCheckResult.UpdateAvailable).version,
        )
    }

    @Test
    fun ignoredVersionSuppressesOnlyThatNewestRelease() {
        val fixture = Fixture(
            initialFetch = ReleaseFetch.Success(
                listOf(release("v0.2.0", page = releasePage("v0.2.0"))),
            ),
        )
        val available = fixture.check() as UpdateCheckResult.UpdateAvailable
        fixture.repository.ignore(available.version)

        assertEquals("v0.2.0", fixture.ignored.load())
        assertEquals(UpdateCheckResult.NoUpdate, fixture.check())
        assertEquals(1, fixture.calls)
    }

    @Test
    fun releasePageMustBeTheRepositoryHttpsReleasePage() {
        val fixture = Fixture(
            initialFetch = ReleaseFetch.Success(
                listOf(
                    release("v0.3.0", page = "https://example.com/releases/tag/v0.3.0"),
                ),
            ),
        )

        assertEquals(UpdateCheckResult.NoUpdate, fixture.check())
    }

    private class Fixture(
        val currentVersion: String = "0.1.0-alpha03",
        var initialFetch: ReleaseFetch,
    ) {
        var now = 1_000_000L
        var calls = 0
        var fetch = initialFetch
        val ignored = MemoryIgnoredVersionStore()
        val repository = UpdateRepository(
            currentVersion = currentVersion,
            transport = UpdateTransport {
                calls += 1
                fetch
            },
            clock = UpdateClock { now },
            ignoredVersionStore = ignored,
        )

        fun check(includePrerelease: Boolean = false) = repository.check(includePrerelease)
    }

    private class MemoryIgnoredVersionStore : IgnoredVersionStore {
        private var value: String? = null

        override fun load(): String? = value

        override fun save(version: String) {
            value = version
        }
    }

    private companion object {
        fun release(
            tag: String,
            notes: String = "notes",
            page: String = releasePage(tag),
            prerelease: Boolean = false,
            draft: Boolean = false,
        ) = GitHubRelease(
            tagName = tag,
            notes = notes,
            releasePage = page,
            prerelease = prerelease,
            draft = draft,
        )

        fun releasePage(tag: String) =
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/$tag"
    }
}
