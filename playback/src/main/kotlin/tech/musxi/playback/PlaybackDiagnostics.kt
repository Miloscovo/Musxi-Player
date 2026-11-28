package tech.musxi.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.playback.PlaybackFailure

/** A process-local, bounded timeline. No identifiers, locations, exception messages or free text. */
enum class PlaybackDiagnosticStage {
    SERVICE_CREATED, SERVICE_DESTROYED, CONTROLLER_CONNECTING, CONTROLLER_CONNECTED,
    CONTROLLER_REJECTED, CONTROLLER_TIMEOUT, CONTROLLER_DISCONNECTED,
    RESTORE_CHECKING, RESTORE_EMPTY, RESTORED_PAUSED, RESTORE_FAILED, RESTORE_SUPERSEDED,
    FOREGROUND_WAIT, FOREGROUND_READY, FOREGROUND_TIMEOUT, FOREGROUND_DENIED, FOREGROUND_INVALIDATED,
    PREPARING, FOCUS_REQUESTED, FOCUS_DENIED, FOCUS_INTERRUPTED, FOCUS_RELEASED,
    OUTPUT_SUPPRESSED, OUTPUT_CHANGED, BECOMING_NOISY, SOURCE_RESOLVED, AUDIO_FORMAT_READ, DECODER_READY,
    OUTPUT_READY, MEDIA_READY, PLAYING, BUFFERING, PAUSED, ENDED, TIMER_STOPPED,
    SOURCE_FAILED, DECODE_FAILED, OUTPUT_FAILED, PLAYER_FAILED, PREPARE_TIMEOUT, BUFFER_TIMEOUT,
}

enum class PlaybackDiagnosticMedia { NONE, PLATFORM, LOCAL, CUE, DSF }

data class PlaybackDiagnosticEvent(
    val elapsedMs: Long,
    val stage: PlaybackDiagnosticStage,
    val media: PlaybackDiagnosticMedia = PlaybackDiagnosticMedia.NONE,
    val platform: Platform? = null,
    val failure: PlaybackFailure? = null,
    val media3ErrorCode: Int? = null,
    val connectionAttempt: Int? = null,
    val output: OutputType? = null,
)

internal class PlaybackDiagnosticHistory(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    private val events = MutableStateFlow<List<PlaybackDiagnosticEvent>>(emptyList())
    val state = events.asStateFlow()

    /** Called on the owning main thread. Repeated adjacent observations need only one entry. */
    fun record(event: PlaybackDiagnosticEvent) {
        val previous = events.value
        if (previous.lastOrNull()?.copy(elapsedMs = event.elapsedMs) == event) return
        events.value = java.util.Collections.unmodifiableList(previous.takeLast(capacity - 1) + event)
    }
}

internal object PlaybackDiagnostics {
    private val history = PlaybackDiagnosticHistory()
    val state = history.state

    fun record(stage: PlaybackDiagnosticStage, media: PlaybackDiagnosticMedia = PlaybackDiagnosticMedia.NONE,
        platform: Platform? = null, failure: PlaybackFailure? = null, media3ErrorCode: Int? = null,
        connectionAttempt: Int? = null, output: OutputType? = null) {
        history.record(PlaybackDiagnosticEvent(SystemClock.elapsedRealtime(), stage, media, platform, failure,
            media3ErrorCode, connectionAttempt, output))
    }
}
