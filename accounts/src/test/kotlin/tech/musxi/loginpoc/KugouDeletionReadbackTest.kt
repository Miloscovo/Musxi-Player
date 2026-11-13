package tech.musxi.loginpoc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistDeletionCoordinator
import tech.musxi.core.library.PlaylistDeletionPort
import tech.musxi.core.library.PlaylistDeletionResult
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class KugouDeletionReadbackTest {
    private val session = KugouApi.Session("fixture-token", "123", "Fixture")
    private val target = EditablePlaylist(
        PlaylistSummary(MediaKey(Platform.KUGOU, "22"), "目标", null, 1, PlaylistKind.CREATED),
        1L, false)

    @Test fun unknownTotalEmptyTerminalAfterDeleteIsAppliedWithOnePost() {
        val account = Account(listOf("21", "22", "23", "24"))
        val result = run(account)

        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedAbsent)
        assertNull(result.error)
        assertEquals(listOf("21", "23", "24"), account.ids)
        assertEquals(1, account.posts)
        assertEquals("only an explicit empty page terminates an unknown total", 2, account.lastPage)
    }

    @Test fun lostDeleteResponseIsReconciledByCompleteAbsentReadback() {
        val account = Account(listOf("21", "22", "23", "24"), loseDeleteResponse = true)
        val result = run(account)

        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedAbsent)
        assertNull(result.error)
        assertEquals(listOf("21", "23", "24"), account.ids)
        assertEquals(1, account.posts)
    }

    @Test fun rejectedDeleteLeavesTheTargetPresentAndKeepsItsCategory() {
        val account = Account(listOf("21", "22", "23", "24"), rejectDelete = true)
        val result = run(account)

        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals(false, result.confirmedAbsent)
        assertEquals("platform", result.error)
        assertTrue("22" in account.ids)
        assertEquals(1, account.posts)
    }

    @Test fun nullTerminalWithoutTotalIsUnknownIncompleteNotAbsence() {
        val account = Account(listOf("21", "22", "23", "24"), nullTerminal = true)
        val result = run(account)

        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertNull(result.confirmedAbsent)
        assertEquals("incomplete", result.error)
        assertEquals(1, account.posts)
    }

    @Test fun targetOnALaterReadbackPageIsPresentNotConfirmed() {
        val account = Account((1000..1099).map { it.toString() } + "22", keepTargetOnDelete = true)
        val result = run(account)

        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals(false, result.confirmedAbsent)
        assertEquals("not_confirmed", result.error)
        assertEquals("the empty terminal page follows the target page", 3, account.lastPage)
        assertEquals("the target was observed on the second page", 2, account.targetSeenPage)
        assertEquals(1, account.posts)
    }

    private fun run(account: Account): PlaylistDeletionResult {
        val library = KugouLibraryApi(KugouApi(KugouApi.Device(MID), account))
        val port = object : PlaylistDeletionPort {
            override fun generation(platform: Platform): Long? = 1L

            override fun exists(target: EditablePlaylist, result: (Result<Boolean>) -> Unit) {
                result(runCatching {
                    library.deletablePlaylist(session, target.playlist.key.id) { true } != null
                }.mapError())
            }

            override fun delete(target: EditablePlaylist, result: (Result<Unit>) -> Unit) {
                result(runCatching {
                    library.deletePlaylist(session, target.playlist.key.id) { true }
                }.mapError())
            }
        }
        var result: Result<PlaylistDeletionResult>? = null
        PlaylistDeletionCoordinator(port).delete(target) { result = it }
        return requireNotNull(result).getOrThrow()
    }

    private fun <T> Result<T>.mapError(): Result<T> = recoverCatching {
        throw LibraryPageFailure(
            if (it is KugouApi.ApiException) it.code
            else if (it is IOException) "network"
            else "unavailable")
    }

    private class Account(private val rows: List<String>,
        private val loseDeleteResponse: Boolean = false,
        private val rejectDelete: Boolean = false,
        private val nullTerminal: Boolean = false,
        private val keepTargetOnDelete: Boolean = false) : KugouApi.Transport {
        val ids = rows.toMutableList()
        var posts = 0
        var lastPage = 0
        var targetSeenPage = 0

        override fun execute(request: KugouApi.Request): JSONObject {
            return when {
                request.endpoint.endsWith(LIST) -> page(JSONObject(request.body).getInt("page"))
                request.endpoint.endsWith(DELETE) -> {
                    posts++
                    if (rejectDelete) JSONObject().put("status", 0).put("data", JSONObject())
                    else {
                        if (!keepTargetOnDelete) ids.remove("22")
                        if (loseDeleteResponse) throw IOException("fixture lost response")
                        JSONObject().put("status", 1).put("data", JSONObject())
                    }
                }
                else -> throw AssertionError("unexpected endpoint ${request.endpoint}")
            }
        }

        private fun page(page: Int): JSONObject {
            lastPage = page
            val from = (page - 1) * PAGE_SIZE
            val to = minOf(from + PAGE_SIZE, ids.size)
            val info = JSONArray()
            if (from < to) for (id in ids.subList(from, to)) {
                info.put(JSONObject().put("listid", id).put("name", "L$id")
                    .put("is_edit", "1").put("is_def", "0").put("count", 1))
                if (id == "22" && targetSeenPage == 0) targetSeenPage = page
            }
            val data = JSONObject().put("info",
                if (nullTerminal && posts > 0 && info.length() == 0) JSONObject.NULL else info)
            return JSONObject().put("status", 1).put("data", data)
        }
    }

    companion object {
        private const val MID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val LIST = "/v7/get_all_list"
        private const val DELETE = "/v2/delete_list"
        private const val PAGE_SIZE = 100
    }
}
