package tech.musxi.player.ui.player

import androidx.compose.ui.graphics.Color
import tech.musxi.core.account.Platform
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
    /** Drives both the placeholder cover and the backdrop tint. */
    val coverSeed: String = "",
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
) {
    /** 0..1, clamped; the thumb and the filled part both read this. */
    val progress: Float get() = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
}

/**
 * Collapses the playback state. Priority is fixed so two simultaneous problems never fight over one
 * line: no output > failure > interruption > ended > platform.
 */
fun playerUiOf(state: PlaybackState, swapSearching: Boolean = false): PlayerUi {
    val status = when {
        state.suppression == PlaybackSuppression.OUTPUT -> PlayerStatus.NO_OUTPUT
        state.failure != null || state.error != null -> PlayerStatus.FAILED
        state.suppression == PlaybackSuppression.AUDIO_FOCUS ||
            state.suppression == PlaybackSuppression.OTHER -> PlayerStatus.INTERRUPTED
        state.ended -> PlayerStatus.ENDED
        else -> PlayerStatus.NORMAL
    }
    return PlayerUi(
        title = state.title,
        artist = state.artist,
        album = state.album,
        coverSeed = state.title,
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
        swapSearching = swapSearching,
        hasPrevious = state.hasPrevious,
        hasNext = state.hasNext,
    )
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
    PlayerStatus.FAILED -> if (ui.swapSearching) "当前音源不可用 · 正在换源" else "暂无可用音源"
    PlayerStatus.INTERRUPTED -> "已被其他应用暂停"
    PlayerStatus.ENDED -> if (ui.repeat == PlaybackRepeatMode.OFF && !ui.hasNext) "队列已播完" else "已播完"
    PlayerStatus.NORMAL -> platformLine(ui)
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
 * Derives the page palette from the cover seed, **relative to the cover's own saturation**.
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
fun playerPalette(seed: String, dark: Boolean): PlayerPalette {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val second = (hue + 38f) % 360f
    val coverChroma = coverColors(seed).let { (a, b) -> maxOf(a.chroma(), b.chroma()) }
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
