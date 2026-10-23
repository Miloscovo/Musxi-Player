package tech.musxi.core.diagnostics

import java.util.concurrent.Executor

/** Audio resolves can proceed during a full library read. Each supplied lane must be serial.
 * Account mutations and every non-audio request keep their existing order on [library].
 * Callers still fence session acquisition, completion and delivery against account changes.
 */
class RequestLanes(private val library: Executor, private val audio: Executor) {
    fun execute(operation: RequestOperation, work: () -> Unit) {
        (if (operation == RequestOperation.AUDIO) audio else library).execute(work)
    }
}
