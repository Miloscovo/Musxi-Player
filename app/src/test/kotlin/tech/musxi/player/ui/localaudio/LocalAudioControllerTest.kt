package tech.musxi.player.ui.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioImportResult
import tech.musxi.core.localaudio.LocalAudioSourceCheckResult
import tech.musxi.core.localaudio.LocalImportMode

/** The route's plain rules: name checks, notices, the selection check and the one-retry rule. */
class LocalAudioControllerTest {

    @Test fun namesFollowTheBackendRule() {
        assertEquals("名称不能为空", nameProblem("   "))
        assertEquals("名称太长了", nameProblem("a".repeat(1025)))
        assertEquals("名称不能包含换行或控制字符", nameProblem("a\nb"))
        assertNull(nameProblem("  睡前音声  "))
        assertNull(nameProblem("a".repeat(1024)))
    }

    @Test fun importNoticeNamesTheFirstFailure() {
        val ok = LocalAudioImportResult("a.mp3", audioId = localId(1))
        val bad = LocalAudioImportResult("b.mp3", error = LocalAudioError.UNSUPPORTED_AUDIO)
        assertEquals("已导入 1 个", importNotice(listOf(ok), LocalImportMode.COPY))
        assertEquals("已导入 1 个，1 个没有导入：不是支持的音频或视频文件，或者视频没有声音", importNotice(listOf(ok, bad), LocalImportMode.COPY))
        val refused = LocalAudioImportResult("c.mp3", error = LocalAudioError.PERMISSION_REQUIRED)
        assertEquals("此来源不支持长期访问，可改用“复制到 Musxi”", importNotice(listOf(refused), LocalImportMode.REFERENCE))
    }

    @Test fun sourceCheckCountsUnappliedResultsAsProblems() {
        val fine = LocalAudioSourceCheckResult(localId(1), LocalAudioAvailability.AVAILABLE, null, applied = true)
        val missing = LocalAudioSourceCheckResult(localId(2), LocalAudioAvailability.MISSING, LocalAudioError.NOT_FOUND, applied = true)
        val stale = LocalAudioSourceCheckResult(localId(3), null, LocalAudioError.STALE_STATE, applied = false)
        assertEquals("1 条可用", sourceCheckSummary(listOf(fine)))
        assertEquals("1 条可用，2 条有问题", sourceCheckSummary(listOf(fine, missing, stale)))
        assertEquals("检查期间列表有变化，请重新检查", sourceCheckSummary(listOf(stale)))
    }

    @Test fun selectionMustStillExistAndBelong() {
        val state = sampleLocalLibrary()
        val sleep = localId(100)
        assertTrue(selectionStillValid(state, listOf(localId(1), localId(3)), memberOf = sleep))
        // 4 is not in 睡前音声, 9 does not exist, 999 is no folder.
        assertFalse(selectionStillValid(state, listOf(localId(4)), memberOf = sleep))
        assertFalse(selectionStillValid(state, listOf(localId(9))))
        assertFalse(selectionStillValid(state, listOf(localId(1)), folders = listOf(localId(999))))
        assertFalse(selectionStillValid(state.copy(ready = false), listOf(localId(1))))
    }

    @Test fun staleStateRetriesOnceWithTheNewestRevision() {
        val fake = FakeLocalAudioController()
        val revisions = mutableListOf<Long>()
        var answer: Result<Unit>? = null
        fake.withFreshRevision<Unit>(
            stillValid = { true },
            command = { revision, done ->
                revisions += revision
                if (revisions.size == 1) {
                    fake.library.value = fake.library.value.copy(revision = 9)
                    done(Result.failure(tech.musxi.core.localaudio.LocalAudioFailure(LocalAudioError.STALE_STATE)))
                } else done(Result.success(Unit))
            },
        ) { answer = it }
        assertEquals(listOf(7L, 9L), revisions)
        assertTrue(answer!!.isSuccess)
    }

    @Test fun noRetryWhenTheSelectionNoLongerFits() {
        val fake = FakeLocalAudioController()
        var calls = 0
        var valid = true
        var answer: Result<Unit>? = null
        fake.withFreshRevision<Unit>(
            stillValid = { valid },
            command = { _, done ->
                calls++
                valid = false
                done(Result.failure(tech.musxi.core.localaudio.LocalAudioFailure(LocalAudioError.STALE_STATE)))
            },
        ) { answer = it }
        assertEquals(1, calls)
        // Told apart from a plain STALE_STATE: the user has to pick again, not just retry.
        assertTrue(answer!!.exceptionOrNull() is SelectionChanged)
    }

    @Test fun theRetryRunsWhereTheCallerSays() {
        val fake = FakeLocalAudioController()
        val revisions = mutableListOf<Long>()
        val held = mutableListOf<() -> Unit>()
        var answer: Result<Unit>? = null
        fake.withFreshRevision<Unit>(
            stillValid = { true },
            command = { revision, done ->
                revisions += revision
                if (revisions.size == 1) done(Result.failure(tech.musxi.core.localaudio.LocalAudioFailure(LocalAudioError.STALE_STATE)))
                else done(Result.success(Unit))
            },
            retry = { held += it },
        ) { answer = it }
        // Nothing retried inside the first answer; it waits for the caller's runner.
        assertEquals(1, revisions.size)
        assertEquals(null, answer)
        held.single().invoke()
        assertEquals(2, revisions.size)
        assertTrue(answer!!.isSuccess)
    }

    @Test fun otherErrorsAreNotRetried() {
        val fake = FakeLocalAudioController()
        var calls = 0
        fake.withFreshRevision<Unit>(
            stillValid = { true },
            command = { _, done -> calls++; done(Result.failure(tech.musxi.core.localaudio.LocalAudioFailure(LocalAudioError.NOT_FOUND))) },
        ) {}
        assertEquals(1, calls)
    }

    @Test fun libraryStatusLine() {
        assertEquals("2 个文件夹 · 4 条", localLibraryStatus(sampleLocalLibrary()))
        assertEquals("正在打开…", localLibraryStatus(sampleLocalLibrary().copy(ready = false)))
    }
}
