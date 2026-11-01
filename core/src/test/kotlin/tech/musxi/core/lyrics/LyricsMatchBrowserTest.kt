package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.Page
import tech.musxi.core.library.SongSummary

class LyricsMatchBrowserTest {
    private val original = MediaKey(Platform.QQ, "original")
    private val candidate = MediaKey(Platform.NETEASE, "candidate")
    private val identities = mapOf(
        Platform.KUGOU to LibraryCacheIdentity("1".repeat(64), 1, true),
        Platform.QQ to LibraryCacheIdentity("2".repeat(64), 1, true),
        Platform.NETEASE to LibraryCacheIdentity("3".repeat(64), 1, true),
    ).toMutableMap()
    private val bindings = mutableMapOf<Pair<String, MediaKey>, LyricsBinding>()
    private val accesses = mutableListOf<Triple<String, MediaKey, Boolean>>()
    private val previewCalls = mutableListOf<Pair<MediaKey, (Result<ParsedLyrics>) -> Unit>>()
    private val changed = mutableListOf<Pair<MediaKey, LyricsBinding?>>()
    private val browser = newBrowser()

    @Test
    fun previewNeverWritesStoreAndSearchUsesCandidateAccount() {
        browser.follow(original)
        browser.search(Platform.NETEASE, "title", 1)

        assertEquals(listOf(candidate), browser.state.value.candidates.map { it.key })
        assertFalse(accesses.any { it.third })

        browser.preview(candidate)
        previewCalls.single().second(Result.success(lyrics()))

        assertEquals(lyrics(), browser.state.value.preview)
        assertFalse(accesses.any { it.third })
        assertTrue(bindings.isEmpty())
    }

    @Test
    fun bindThenFollowFromColdStateReadsStoredOffset() {
        browser.follow(original)
        browser.bind(original, candidate, 9_500)

        val stored = bindings.getValue(identities.getValue(Platform.QQ).owner to original)
        assertEquals(9_500L, stored.offsetMs)
        assertEquals(identities.getValue(Platform.NETEASE).owner, stored.candidateOwner)
        assertEquals(identities.getValue(Platform.NETEASE).generation, stored.candidateGeneration)
        assertEquals(stored, changed.single().second)

        val coldBrowser = newBrowser()
        coldBrowser.follow(original)

        assertEquals(stored, coldBrowser.state.value.binding)
        assertEquals(candidate, previewCalls.last().first)
    }

    @Test
    fun resetRemovesBindingAndNotifiesMainLyricsRefresh() {
        browser.follow(original)
        browser.bind(original, candidate, 2_000)
        browser.reset(original)

        assertTrue(bindings.isEmpty())
        assertNull(browser.state.value.binding)
        assertEquals(original to null, changed.last())
        assertFalse(browser.state.value.saving)
    }

    @Test
    fun persistedBindingRemainsValidAcrossCandidateGenerationButAsyncWorkStillUsesItsGeneration() {
        browser.follow(original)
        browser.bind(original, candidate, 4_500)

        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(generation = 2)
        val coldBrowser = newBrowser()
        coldBrowser.follow(original)

        assertEquals(4_500L, coldBrowser.state.value.binding?.offsetMs)
        assertEquals(candidate, previewCalls.last().first)
        val stalePreview = previewCalls.last().second
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(generation = 3)
        coldBrowser.accountChanged()
        stalePreview(Result.success(lyrics()))
        assertNull(coldBrowser.state.value.preview)
        previewCalls.last().second(Result.success(ParsedLyrics(emptyList(), "current")))
        assertEquals("current", coldBrowser.state.value.preview?.plainText)
    }

    @Test
    fun repeatedFollowForSameKeyAndOwnerDoesNotClearOrReadAgain() {
        browser.follow(original)
        browser.search(Platform.NETEASE, "title", 1)
        val candidateCount = browser.state.value.candidates.size
        val readsBefore = accesses.count { !it.third }

        browser.follow(original)

        assertEquals(candidateCount, browser.state.value.candidates.size)
        assertEquals(readsBefore, accesses.count { !it.third })
    }

    @Test
    fun followAndAccountChangeRejectLatePreviewCallbacks() {
        browser.follow(original)
        browser.preview(candidate)
        val first = previewCalls.single().second

        val nextOriginal = MediaKey(Platform.QQ, "next")
        browser.follow(nextOriginal)
        first(Result.success(lyrics()))

        assertNull(browser.state.value.preview)
        assertEquals(nextOriginal, browser.state.value.originalKey)

        browser.preview(candidate)
        val stale = previewCalls.last().second
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(generation = 2)
        browser.accountChanged()
        stale(Result.success(lyrics()))

        assertNull(browser.state.value.preview)
        assertEquals(nextOriginal, browser.state.value.originalKey)
    }

    private fun lyrics() = ParsedLyrics(emptyList(), "preview")

    private fun song(key: MediaKey) = SongSummary(
        key,
        key.id,
        listOf("artist"),
        "album",
        1_000,
    )

    private fun newBrowser() = LyricsMatchBrowser(
        identity = { identities[it] },
        search = { _, _, _, callback -> callback(Result.success(Page(
            listOf(song(candidate), song(original), song(MediaKey(Platform.KUGOU, "other"))),
            null,
            3,
        ))) },
        previewFetch = { key, _, callback -> previewCalls += key to callback },
        bindingAccess = { key, owner, transform, callback ->
            val storageKey = owner.owner to key
            synchronized(bindings) {
                if (transform == null) {
                    accesses += Triple(owner.owner, key, false)
                    callback(Result.success(bindings[storageKey]))
                } else {
                    accesses += Triple(owner.owner, key, true)
                    val next = transform(bindings[storageKey])
                    if (next == null) bindings.remove(storageKey) else bindings[storageKey] = next
                    callback(Result.success(next))
                }
            }
        },
        onBindingChanged = { key, binding -> changed += key to binding },
    )
}
