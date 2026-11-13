package tech.musxi.player.ui.player

import androidx.compose.ui.graphics.Color
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.playback.FailureReason
import tech.musxi.playback.PlaybackRepeatMode
import tech.musxi.playback.PlaybackState
import tech.musxi.playback.PlaybackSuppression
import tech.musxi.player.ui.components.coverColors
import tech.musxi.player.ui.theme.displayName

/** What the player is doing, reduced to the states that need to be said out loud. */
enum class PlayerStatus {
    /** Nothing to explain: the row shows the platform instead. */
    NORMAL,

    /** Another app holds audio focus. Transient, so it must not look like a failure. */
    INTERRUPTED,

    /** No route for sound; the fix lives in the output-device tool, not in a dialog. */
    NO_OUTPUT,

    /** This track will not play; a source swap is normally already under way. */
    FAILED,

    /** Queue ran out with repeat off. */
    ENDED,
}

/**
 * Everything the full player shows.
 *
 * The seven states the player can be in all resolve to one line of text — the third row of the
 * title block. That is deliberate: a status strip that appears and disappears moves the cover and
 * the controls, and a page that jumps every time the phone rings is not a quiet container.
 */
data class PlayerUi(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** Drives the placeholder cover, and the backdrop tint until the real cover's colour is known. */
    val coverSeed: String = "",
    val coverUrl: String? = null,
    /** Platform that owns the queue item. */
    val queuePlatform: Platform? = null,
    /** Platform actually streaming; differs only after a cross-platform swap. */
    val sourcePlatform: Platform? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val repeat: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val shuffle: Boolean = false,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val status: PlayerStatus = PlayerStatus.NORMAL,
    /** True while a source swap is searching, which changes the wording of [PlayerStatus.FAILED]. */
    val swapSearching: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val qualityName: String? = null,
    /** Why the track did not play, when the service said; shown beside [PlayerStatus.FAILED]. */
    val failureReason: FailureReason? = null,
    /**
     * `实际播放：《标题》· 歌手 · 平台` under the artist line. Non-null only when the recording
     * supplying the audio is not the queue's own song; the title and artist above stay the original.
     */
    val actualSourceLine: String? = null,
    /**
     * A recording from the local library is playing. It has no platform, source or quality, so the
     * page hides those controls and words its lines about the file instead.
     */
    val local: Boolean = false,
    /** The local recording is a video played for its sound: the cover says so. */
    val localVideo: Boolean = false,
    /** Why the local file did not play; only read beside [PlayerStatus.FAILED] while [local]. */
    val localError: LocalAudioError? = null,
    /** Normal third line while [local]: the folder the queue came from, or "本地音频". */
    val localSourceLine: String? = null,
) {
    /**
     * 0..1, clamped; the thumb and the filled part read this unless the shell hands the page a live
     * position (see `FullPlayer(liveProgress = …)`), in which case [positionMs] is not used.
     */
    val progress: Float get() = progressFraction(positionMs, durationMs)
}

/**
 * Collapses the playback state. Priority is fixed so two simultaneous problems never fight over one
 * line: no output > failure > interruption > ended > platform.
 */
fun playerUiOf(
    state: PlaybackState,
    swapSearching: Boolean = false,
    /** Name of the local folder the queue came from; ignored for platform songs. */
    localFolderName: String? = null,
): PlayerUi {
    val local = isLocalPlayback(state)
    val status = when {
        state.suppression == PlaybackSuppression.OUTPUT -> PlayerStatus.NO_OUTPUT
        state.failure != null || state.error != null -> PlayerStatus.FAILED
        state.suppression == PlaybackSuppression.AUDIO_FOCUS ||
            state.suppression == PlaybackSuppression.OTHER -> PlayerStatus.INTERRUPTED
        state.ended -> PlayerStatus.ENDED
        else -> PlayerStatus.NORMAL
    }
    // After a swap the queue's own song is still the one the user chose: it keeps the title block,
    // and the recording that is really playing gets its own line.
    val actual = swappedRecordingOf(state)
    val original = state.original.takeIf { actual != null }
    return PlayerUi(
        title = original?.title?.takeIf { it.isNotBlank() } ?: state.title,
        artist = original?.artists?.joinToString(" / ")?.takeIf { it.isNotBlank() } ?: state.artist,
        album = original?.album?.takeIf { it.isNotBlank() } ?: state.album,
        coverSeed = state.title,
        coverUrl = state.coverUrl,
        queuePlatform = state.queuePlatform,
        sourcePlatform = state.sourcePlatform,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        playing = state.playing && !state.buffering,
        buffering = state.buffering,
        repeat = state.repeatMode,
        shuffle = state.shuffleEnabled,
        queueIndex = state.queueIndex,
        queueSize = state.queueSize,
        status = status,
        // The service never swaps a local file for a platform song; a stale search must not
        // reword the failure or spin the play button.
        swapSearching = swapSearching && !local,
        hasPrevious = state.hasPrevious,
        hasNext = state.hasNext,
        qualityName = state.qualityName,
        failureReason = state.failureReason?.reason,
        actualSourceLine = actual?.let(::actualSourceLineOf),
        local = local,
        localVideo = local && state.localAudio?.kind == tech.musxi.core.localaudio.LocalMediaKind.VIDEO,
        localError = state.localError.takeIf { local },
        localSourceLine = if (local) localSourceLineOf(localFolderName) else null,
    )
}

/**
 * The recording supplying the audio, when it is a different song from the queue's own. Null when
 * either side is unknown: with nothing to compare there is nothing to tell the user.
 */
internal fun swappedRecordingOf(state: PlaybackState): SongSummary? {
    val original = state.original ?: return null
    val actual = state.actualSource ?: return null
    return actual.takeIf { it.key != original.key }
}

/** `实际播放：《晴天》· Jay · 网易云`. A blank title or artist is left out instead of printed as a gap. */
internal fun actualSourceLineOf(actual: SongSummary): String {
    val title = actual.title.takeIf { it.isNotBlank() }?.let { "《$it》" }
    val rest = listOfNotNull(
        actual.artists.joinToString(" / ").takeIf { it.isNotBlank() },
        actual.key.platform.displayName,
    ).joinToString(" · ")
    // No space before the first dot, matching the source panel's "《标题》· 歌手" form.
    return "实际播放：" + if (title == null) rest else "$title· $rest"
}

/**
 * The third line of the title block: the single place this page says anything about state.
 *
 * A reason always beats the platform name, because the platform is the least surprising fact on
 * screen while "why did it stop" is the only thing the user cannot infer.
 */
fun thirdLineOf(ui: PlayerUi): String = when (ui.status) {
    // Tells the user what to do, because the play button stays live: pressing it retries the route.
    PlayerStatus.NO_OUTPUT -> "没有可用的音频输出 · 点按重试"
    // While a swap is searching, the app is already handling it: stating it as an error and leaving
    // a live play button sends two opposite signals, so this wording stays neutral.
    // The settled failure names its reason when the service reported one; the searching line does
    // not, because the swap under way is the news and the reason is for the panel to explain.
    // A local file is about the file: no source, account or platform wording.
    PlayerStatus.FAILED -> when {
        ui.local -> localAudioErrorText(ui.localError)
        ui.swapSearching -> "当前音源不可用 · 正在换源"
        else -> withFailureReason("暂无可用音源", ui.failureReason)
    }
    PlayerStatus.INTERRUPTED -> "已被其他应用暂停"
    PlayerStatus.ENDED -> if (ui.repeat == PlaybackRepeatMode.OFF && !ui.hasNext) "队列已播完" else "已播完"
    PlayerStatus.NORMAL ->
        // A video says it plays sound only, so the missing picture reads as a choice, not a fault.
        if (ui.local) listOfNotNull("视频 · 仅播放声音".takeIf { ui.localVideo }, ui.localSourceLine ?: localSourceLineOf(null))
            .joinToString(" · ")
        else listOfNotNull(platformLine(ui).takeIf(String::isNotBlank), ui.qualityName).joinToString(" · ")
}

/**
 * Normal case: which platform this plays from. The "provided by" form only appears after a swap —
 * before that it would just repeat the library the user came from.
 *
 * Names are spelled out in full here. The short `displayName` mixes lengths ("网易云" against
 * "QQ音乐"), which reads as two different kinds of thing side by side in the same sentence.
 */
private fun platformLine(ui: PlayerUi): String {
    val queue = ui.queuePlatform ?: return ""
    val source = ui.sourcePlatform?.takeIf { it != queue }
    return if (source == null) queue.fullName()
    else "${queue.fullName()} · 由${source.fullName()}提供"
}

private fun Platform.fullName(): String = when (this) {
    Platform.NETEASE -> "网易云音乐"
    Platform.QQ -> "QQ音乐"
    Platform.KUGOU -> "酷狗音乐"
}

/**
 * True when the line is a problem report rather than a platform, so it can be tinted.
 *
 * A swap in progress is not a problem report: it is the app doing its job. Only the settled failure
 * — nothing playable and nothing being searched — earns the error colour.
 */
fun isProblemLine(ui: PlayerUi): Boolean = when (ui.status) {
    PlayerStatus.NO_OUTPUT -> true
    PlayerStatus.FAILED -> !ui.swapSearching
    else -> false
}

/**
 * The colours the player page derives from its cover.
 *
 * This page is the only one allowed to take colour from the cover, so it takes it seriously: the
 * backdrop *and* the accent come from the same hue, and the theme accent is not used here at all.
 * The result is that opening a track feels like entering a room with its own light, which is the
 * only way "the container is quiet, the cover speaks" can be seen rather than claimed.
 */
data class PlayerPalette(
    val backdropTop: Color,
    val backdropBottom: Color,
    /** Playback controls, progress and the small enabled translation badge. */
    val accent: Color,
    /** Glyph colour that sits on [accent]. */
    val onAccent: Color,
)

/**
 * Derives the page palette from the cover, **relative to the cover's own saturation**.
 *
 * [cover] is the cover's average colour (see [rememberCoverColor]). With a real hue in it, that hue
 * and chroma drive the page. Without one (null while it loads or after a failure, or a grey cover
 * whose hue is just noise) the palette comes from [seed] exactly as it did before covers were read.
 *
 * The first attempt clamped HSL saturation to small absolute numbers, which did nothing visible:
 * at lightness 0.94 an HSL saturation of 0.08 is a chroma of 0.0096 — indistinguishable from grey.
 * The tint only appears if the target is expressed as chroma and then converted back to the
 * saturation that lightness allows. Everything below is therefore in chroma terms, scaled from the
 * cover itself so a muted cover gets a muted page and a vivid one gets a warmer room.
 *
 * Text is deliberately NOT derived. It stays the theme's `onSurface`, so an unlucky seed cannot make
 * the title or the timings unreadable — the accent and backdrop are the only borrowed colour.
 */
fun playerPalette(seed: String, dark: Boolean, cover: Int? = null): PlayerPalette {
    val tint = cover?.let(::coverTintOf)
    val hue = tint?.hue ?: ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val second = (hue + 38f) % 360f
    val coverChroma = tint?.chroma ?: coverColors(seed).let { (a, b) -> maxOf(a.chroma(), b.chroma()) }
    // ~22% of the cover's colour on the background: enough to read as a tinted room, not a poster.
    val backdropChroma = coverChroma * 0.22f
    // 85% of it on the controls: they carry the colour, but never out-saturate the artwork.
    val accentChroma = coverChroma * 0.85f
    return if (dark) {
        PlayerPalette(
            backdropTop = hslWithChroma(hue, 0.15f, backdropChroma),
            backdropBottom = hslWithChroma(second, 0.10f, backdropChroma * 0.85f),
            accent = hslWithChroma(hue, 0.66f, accentChroma),
            onAccent = hslWithChroma(hue, 0.14f, accentChroma * 0.55f),
        )
    } else {
        PlayerPalette(
            backdropTop = hslWithChroma(hue, 0.94f, backdropChroma),
            backdropBottom = hslWithChroma(second, 0.97f, backdropChroma * 0.75f),
            accent = hslWithChroma(hue, 0.42f, accentChroma),
            onAccent = hslWithChroma(hue, 0.98f, accentChroma * 0.18f),
        )
    }
}

/** Hue in degrees (0 until 360) and chroma (0..1) of a cover's average colour. */
internal data class CoverTint(val hue: Float, val chroma: Float)

/**
 * Below this chroma (about 8 of 255 between the strongest and weakest channel) a cover is grey for
 * our purposes: its hue is compression noise, so borrowing it would tint the room with an accident.
 */
internal const val GREY_COVER_CHROMA = 0.03f

/** The hue and chroma of an opaque ARGB [argb], or null for a grey colour that has no hue to lend. */
internal fun coverTintOf(argb: Int): CoverTint? {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val high = maxOf(r, g, b)
    val chroma = high - minOf(r, g, b)
    if (chroma < GREY_COVER_CHROMA) return null
    val sector = when (high) {
        r -> ((g - b) / chroma + 6f) % 6f
        g -> (b - r) / chroma + 2f
        else -> (r - g) / chroma + 4f
    }
    return CoverTint(hue = (sector * 60f).coerceIn(0f, 359.99f), chroma = chroma)
}

/** HSL with a chroma target: converts the desired colourfulness into the saturation that this
 *  lightness can actually express. Near white or near black, saturation numbers lie. */
private fun hslWithChroma(hue: Float, lightness: Float, chroma: Float): Color {
    val room = (1f - kotlin.math.abs(2f * lightness - 1f)).coerceAtLeast(0.05f)
    return Color.hsl(hue, (chroma / room).coerceIn(0f, 1f), lightness)
}

internal fun Color.chroma(): Float = maxOf(red, green, blue) - minOf(red, green, blue)

/** Backdrop only, for callers that just need the gradient. */
fun playerBackdrop(seed: String, dark: Boolean): Pair<Color, Color> {
    val palette = playerPalette(seed, dark)
    return palette.backdropTop to palette.backdropBottom
}
