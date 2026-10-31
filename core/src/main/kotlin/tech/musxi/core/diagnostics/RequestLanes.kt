package tech.musxi.core.diagnostics

import java.util.concurrent.Executor

/** Audio resolves can proceed during a full library read. Account mutations and every ordinary
 * request keep their existing order on [library]. Source-search lookups may use a separate bounded
 * lane; leaving it unset keeps the original two-lane behavior.
 * Callers still fence session acquisition, completion and delivery against account changes.
 */
class RequestLanes(
    private val library: Executor,
    private val audio: Executor,
    private val playbackSearch: Executor = library,
) {
    fun execute(operation: RequestOperation, playbackSearch: Boolean = false, work: () -> Unit) {
        require(!playbackSearch || operation == RequestOperation.SEARCH || operation == RequestOperation.SONG) {
            "Only SEARCH and SONG requests may use the playback-search lane"
        }
        when {
            operation == RequestOperation.AUDIO -> audio
            playbackSearch -> this.playbackSearch
            else -> library
        }.execute(work)
    }
}
