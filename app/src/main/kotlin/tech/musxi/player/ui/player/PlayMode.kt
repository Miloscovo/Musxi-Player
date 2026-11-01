package tech.musxi.player.ui.player

import tech.musxi.playback.PlaybackRepeatMode

/**
 * The three play orders the user chooses between, as on the PC build.
 *
 * Media3 stores two independent settings — shuffle on/off and repeat off/all/one — which gave six
 * combinations behind two cryptic toggles. The product offers three; this is the only mapping.
 * "顺序" wraps to the start like the PC build, so it is repeat-all.
 */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    SHUFFLE("随机播放"),
    REPEAT_ONE("单曲循环"),
    ;

    val shuffle: Boolean get() = this == SHUFFLE
    val repeat: PlaybackRepeatMode get() = if (this == REPEAT_ONE) PlaybackRepeatMode.ONE else PlaybackRepeatMode.ALL
}

/** Reads the mode back from the player. Repeat-one wins, because it is the most specific. */
fun playModeOf(shuffle: Boolean, repeat: PlaybackRepeatMode): PlayMode = when {
    repeat == PlaybackRepeatMode.ONE -> PlayMode.REPEAT_ONE
    shuffle -> PlayMode.SHUFFLE
    else -> PlayMode.SEQUENTIAL
}
