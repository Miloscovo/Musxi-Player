package tech.musxi.player.lockscreen

/** Reasons are observable by settings/permission UI; a launch request is never proof of display. */
enum class LockScreenLyricsPhase {
    DISABLED, HOST_UNAVAILABLE, NEEDS_PERMISSION, WAITING_FOR_LOCK, WAITING_FOR_PLAYBACK,
    REQUESTED, SHOWN, DISMISSED, UNCONFIRMED, FAILED,
}

data class LockScreenLyricsEnvironment(
    val enabled: Boolean = false,
    val hostAvailable: Boolean = false,
    val overlayGranted: Boolean = false,
    val interactive: Boolean = false,
    val locked: Boolean = false,
    val hasMedia: Boolean = false,
    val playing: Boolean = false,
)

data class LockScreenLyricsState(
    val phase: LockScreenLyricsPhase = LockScreenLyricsPhase.DISABLED,
    val environment: LockScreenLyricsEnvironment = LockScreenLyricsEnvironment(),
    val cycle: Long = 0,
    val requestId: Long? = null,
    val launchAttempts: Long = 0,
    val confirmedDisplays: Long = 0,
)

enum class LockScreenLyricsEvent { STATE_CHANGED, SCREEN_OFF, SCREEN_ON, USER_PRESENT }
enum class LockScreenLyricsEffect { NONE, LAUNCH, CLOSE }

/** Main-thread policy: one attempt per screen cycle, no retries after dismissal or silent rejection. */
class LockScreenLyricsSession {
    var state = LockScreenLyricsState()
        private set
    private var nextRequest = 0L
    private var consumed = false
    private var heldPhase: LockScreenLyricsPhase? = null
    private var offHandled = false

    fun update(environment: LockScreenLyricsEnvironment, event: LockScreenLyricsEvent): LockScreenLyricsEffect {
        if (event == LockScreenLyricsEvent.SCREEN_OFF && (environment.interactive || offHandled))
            return LockScreenLyricsEffect.NONE
        if (environment.interactive) offHandled = false
        val hadHost = state.requestId != null
        if (event == LockScreenLyricsEvent.SCREEN_OFF) {
            offHandled = true
            consumed = false
            heldPhase = null
            state = state.copy(cycle = state.cycle + 1, requestId = null)
        }
        if (event == LockScreenLyricsEvent.USER_PRESENT) {
            consumed = true
            heldPhase = LockScreenLyricsPhase.DISMISSED
        }
        val unavailable = when {
            !environment.enabled -> LockScreenLyricsPhase.DISABLED
            !environment.hostAvailable -> LockScreenLyricsPhase.HOST_UNAVAILABLE
            !environment.overlayGranted -> LockScreenLyricsPhase.NEEDS_PERMISSION
            !environment.hasMedia -> LockScreenLyricsPhase.WAITING_FOR_PLAYBACK
            state.phase == LockScreenLyricsPhase.REQUESTED && !environment.playing -> LockScreenLyricsPhase.WAITING_FOR_PLAYBACK
            !environment.interactive || !environment.locked -> LockScreenLyricsPhase.WAITING_FOR_LOCK
            else -> null
        }
        state = state.copy(environment = environment)
        if (unavailable != null || event == LockScreenLyricsEvent.USER_PRESENT ||
            event == LockScreenLyricsEvent.SCREEN_OFF) {
            // Mode changes, permission loss and unlocking cannot leave an old host on top.
            if (hadHost && event != LockScreenLyricsEvent.SCREEN_OFF) {
                consumed = true
                heldPhase = LockScreenLyricsPhase.DISMISSED
            }
            state = state.copy(phase = unavailable ?: LockScreenLyricsPhase.DISMISSED, requestId = null)
            return if (hadHost) LockScreenLyricsEffect.CLOSE else LockScreenLyricsEffect.NONE
        }
        // Pausing an already displayed page keeps its play button usable. It never launches a new page.
        if (state.requestId != null) return LockScreenLyricsEffect.NONE
        if (consumed) {
            state = state.copy(phase = heldPhase ?: LockScreenLyricsPhase.DISMISSED)
            return LockScreenLyricsEffect.NONE
        }
        state = state.copy(phase = if (environment.playing) LockScreenLyricsPhase.WAITING_FOR_LOCK
            else LockScreenLyricsPhase.WAITING_FOR_PLAYBACK)
        if (event != LockScreenLyricsEvent.SCREEN_ON || !environment.playing) return LockScreenLyricsEffect.NONE
        consumed = true
        state = state.copy(phase = LockScreenLyricsPhase.REQUESTED, requestId = ++nextRequest,
            launchAttempts = state.launchAttempts + 1)
        return LockScreenLyricsEffect.LAUNCH
    }

    fun accepts(requestId: Long): Boolean = state.requestId == requestId &&
        state.environment.let { it.enabled && it.hostAvailable && it.overlayGranted && it.locked &&
            it.interactive && it.hasMedia && (it.playing || state.phase == LockScreenLyricsPhase.SHOWN) }

    fun displayed(requestId: Long): Boolean {
        if (!accepts(requestId)) return false
        if (state.phase != LockScreenLyricsPhase.SHOWN)
            state = state.copy(phase = LockScreenLyricsPhase.SHOWN, confirmedDisplays = state.confirmedDisplays + 1)
        return true
    }

    fun dismiss(requestId: Long): Boolean = finish(requestId, LockScreenLyricsPhase.DISMISSED)
    fun failed(requestId: Long): Boolean = finish(requestId, LockScreenLyricsPhase.FAILED)
    fun timedOut(requestId: Long): Boolean =
        if (state.phase == LockScreenLyricsPhase.REQUESTED) finish(requestId, LockScreenLyricsPhase.UNCONFIRMED) else false

    private fun finish(requestId: Long, phase: LockScreenLyricsPhase): Boolean {
        if (state.requestId != requestId) return false
        consumed = true
        heldPhase = phase
        state = state.copy(requestId = null, phase = phase)
        return true
    }
}
