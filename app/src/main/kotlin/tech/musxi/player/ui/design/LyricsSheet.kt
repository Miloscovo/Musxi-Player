package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.LyricsBlock
import tech.musxi.player.ui.player.LyricsErrorKind
import tech.musxi.player.ui.player.LyricsPhase
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.TranslationChip
import tech.musxi.player.ui.player.TranslationUi
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** Which reading of the lyrics page is being looked at. */
enum class LyricsForm {
    /** B: a mode of the player page. The cover makes way; every control stays. */
    IN_PLAYER,

    /** A: its own page pushed on top. Drawn only so it can be compared and rejected. */
    FULL_PAGE,
}

/**
 * Lyrics sheets: the real [LyricsBlock], inside the real player page.
 *
 * A version that drew its own lyric list would review a page nobody is going to build — the same
 * mistake that once made four rounds of shell review look at a list that did not exist.
 */
@Composable
fun LyricsSheet(
    accent: Accent,
    mode: ThemeMode,
    ui: LyricsUi,
    form: LyricsForm = LyricsForm.IN_PLAYER,
    player: PlayerUi = playerNormal(),
    /** Whether the current lyric line takes the accent. The recommendation is no. */
    accentCurrent: Boolean = false,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val dark = mode != ThemeMode.LIGHT
        when (form) {
            LyricsForm.IN_PLAYER -> FullPlayer(
                ui = player,
                dark = dark,
                lyrics = ui,
                lyricsAccentCurrent = accentCurrent,
            )
            LyricsForm.FULL_PAGE -> LyricsFullPage(player, ui, dark)
        }
    }
}

/** The rejected reading, drawn with the same block and palette so only the structure differs. */
@Composable
private fun LyricsFullPage(player: PlayerUi, ui: LyricsUi, dark: Boolean) {
    val palette = playerPalette(player.coverSeed, dark)
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = palette.backdropBottom) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom))),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "返回",
                            tint = cs.onSurface, modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        player.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    // Balances the back button so the title stays optically centred.
                    Spacer(Modifier.size(48.dp))
                }
                // The whole rest of the page: no transport controls, no progress, no state line.
                LyricsBlock(
                    ui = ui,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/**
 * Invented lines, not a real song's lyrics.
 *
 * The repository must not carry lyric text (the backend contract forbids writing lyrics to disk at
 * all), and borrowed text would be a licence question on top. What the sheet needs is realistic
 * *shape*: varying line lengths, one long line that must wrap, and a blank line that is a paragraph
 * break rather than an empty row.
 */
private val sampleLines: List<Pair<Long, String>> = listOf(
    6_000L to "把窗推开一点 风就进来了",
    17_000L to "厨房还亮着灯 水壶轻轻响",
    28_000L to "我把今天折起来 放进抽屉里",
    39_000L to "有些话没说出口 就不算数吗",
    50_000L to "走过三条街 才想起没带伞",
    61_000L to "雨落在旧招牌上 一粒一粒地亮",
    72_000L to "你说过的那句 我记成了另一句",
    84_000L to "",
    95_000L to "如果此刻有一首歌 刚好唱到这一句",
    106_000L to "我会不会就承认 其实我一直在等",
    117_000L to "等一封信 等一个不响的门铃",
    128_000L to "等窗外的树 把影子挪回原来的位置",
    139_000L to "而风把窗帘吹起来的时候 我知道今晚又要很晚才能睡着",
    150_000L to "把窗关了吧 风有点凉",
)

/** Timed lyrics, mid-song: the active line sits where the anchor puts it. */
fun lyricsTimed(activeIndex: Int = 6, browsing: Boolean = false) = LyricsUi(
    phase = LyricsPhase.TIMED,
    lines = sampleLines.map { (timeMs, text) -> LyricLine(timeMs, text) },
    activeIndex = activeIndex,
    browsing = browsing,
    // No translation route for this source. The chip is still there — inert — because it is the
    // entry point where asking for a translation will live.
    translation = TranslationUi(TranslationChip.NONE),
)

/** True for a page the user has scrolled away from: the "back to current" pill appears. */
fun lyricsBrowsing() = lyricsBilingual(browsing = true)

/**
 * A song whose source has translations.
 *
 * The original is English on purpose: its glyphs are narrower than Chinese, so the two languages
 * disagree about how much room a row needs — which is exactly the case a Chinese-only sample would
 * have hidden. Two lines deliberately have no translation: the backend aligns only the rows it can
 * verify, and the layout must not leave an empty band for them.
 */
fun lyricsBilingual(
    activeIndex: Int = 6,
    browsing: Boolean = false,
    chip: TranslationChip = TranslationChip.ON,
) = LyricsUi(
    phase = LyricsPhase.TIMED,
    lines = bilingualLines.mapIndexed { index, (timeMs, original, translation) ->
        LyricLine(timeMs, original, if (index == 3 || index == 9) null else translation)
    },
    activeIndex = activeIndex,
    browsing = browsing,
    translation = TranslationUi(chip, canRefresh = true, hasText = true),
)

/** The same song with a translation line long enough to wrap under a wrapped original. */
fun lyricsLongLines(activeIndex: Int = 4) = LyricsUi(
    phase = LyricsPhase.TIMED,
    lines = longLines.mapIndexed { index, (timeMs, original, translation) ->
        LyricLine(timeMs, original, if (index == 2) null else translation)
    },
    activeIndex = activeIndex,
    translation = TranslationUi(TranslationChip.ON, canRefresh = true, hasText = true),
)

/**
 * Translations are on but the latest lookup failed.
 *
 * The text stays — the backend keeps verified translations through a failed refresh — and the chip
 * turns into the retry, so the failure costs no layout and no reading.
 */
fun lyricsTranslationWarning() = lyricsBilingual(chip = TranslationChip.WARNING)

/** The platform confirmed this song has no translation: the chip is visible but inert. */
fun lyricsTranslationNone() = lyricsBilingual(chip = TranslationChip.NONE)

/** No timeline at all: readable, but nothing can follow or highlight. */
fun lyricsPlain() = LyricsUi(
    phase = LyricsPhase.PLAIN,
    plainText = sampleLines.joinToString("\n") { it.second },
)

/** The read failed for a reason the user can act on. */
fun lyricsError(kind: LyricsErrorKind = LyricsErrorKind.NETWORK) =
    LyricsUi(phase = LyricsPhase.ERROR, errorKind = kind)

/** No lyrics exist for this song: a statement, not a fault, so there is no button. */
fun lyricsEmpty() = LyricsUi(phase = LyricsPhase.EMPTY)

/** A read in flight — and the shape a source swap collapses to as well. */
fun lyricsLoading() = LyricsUi(phase = LyricsPhase.LOADING)

/**
 * Invented bilingual lines: original, translation, and the times they share.
 *
 * Both languages are written here rather than borrowed, for the same two reasons as the Chinese
 * sample: the repository must not carry lyric text, and a translation of someone else's song would
 * be a licence question on top.
 */
private val bilingualLines: List<Triple<Long, String, String>> = listOf(
    Triple(6_000L, "I left the window open a crack", "我把窗推开了一点缝"),
    Triple(17_000L, "the kitchen light is still on", "厨房的灯还亮着"),
    Triple(28_000L, "the kettle hums, then gives up", "水壶响了一会儿，又安静了"),
    // No translation: the backend aligns only the rows it can verify.
    Triple(39_000L, "I fold today into a drawer", "我把今天折起来放进抽屉"),
    Triple(50_000L, "three streets before I missed the rain", "走过三条街才想起没带伞"),
    Triple(61_000L, "it lands on the old sign, bead by bead", "雨落在旧招牌上，一粒一粒地亮"),
    Triple(72_000L, "you said one thing, I kept another", "你说过的那句，我记成了另一句"),
    Triple(84_000L, "", ""),
    Triple(95_000L, "if a song reached this line right now", "如果此刻有一首歌，刚好唱到这一句"),
    // No translation on purpose, again.
    Triple(106_000L, "would I admit I have been waiting", "我会不会就承认，其实我一直在等"),
    Triple(117_000L, "for a letter, for a bell that never rings", "等一封信，等一个不响的门铃"),
    Triple(128_000L, "for the tree to move its shadow back", "等窗外的树，把影子挪回原来的位置"),
    Triple(139_000L, "and when the wind lifts the curtain I know tonight will be long", "而风把窗帘吹起来的时候，我知道今晚又要很晚才能睡着"),
    Triple(150_000L, "close the window, the wind is cold", "把窗关了吧，风有点凉"),
)

/** Lines that must wrap: a long original with an equally long translation under it. */
private val longLines: List<Triple<Long, String, String>> = listOf(
    Triple(6_000L, "Somewhere a door closes", "某处有一扇门关上了"),
    Triple(17_000L, "and the whole street hears it", "整条街都听见了"),
    Triple(28_000L, "nobody comes out to look", "没有人出来看"),
    Triple(
        39_000L,
        "so I keep walking past the lit windows and the small lives inside them",
        "于是我继续走过那些亮着的窗户，以及窗子里那些小小的生活",
    ),
    Triple(
        50_000L,
        "and every one of them could have been ours if the year had gone another way",
        "如果那一年拐向另一个方向，它们中的每一个都可能是我们的",
    ),
    Triple(61_000L, "the wind is cold, the wind is cold", "风有点凉，风有点凉"),
)

/**
 * A Chinese song that its platform *does* translate.
 *
 * The other bilingual sample has English originals, which hides the case this one exists for: a
 * Chinese current line beside a Latin translation. Both directions must keep a clear reading
 * hierarchy without changing emphasis based on the script or the presence of translation data.
 */
fun lyricsNativeBilingual(activeIndex: Int = 5) = LyricsUi(
    phase = LyricsPhase.TIMED,
    lines = nativeBilingualLines.mapIndexed { index, (timeMs, original, translation) ->
        LyricLine(timeMs, original, if (index == 2) null else translation)
    },
    activeIndex = activeIndex,
    translation = TranslationUi(TranslationChip.ON, canRefresh = true, hasText = true),
)

private val nativeBilingualLines = listOf(
    Triple(0L, "把窗推开一点 风就进来了", "I left the window open a crack"),
    Triple(4_000L, "厨房还亮着灯 水壶轻轻响", "the kitchen light is still on"),
    Triple(8_000L, "我把今天折起来 放进抽屉里", "I fold today into a drawer"),
    Triple(12_000L, "有些话没说出口 就不算数吗", "do words never said still count"),
    Triple(16_000L, "走过三条街 才想起没带伞", "three streets before I missed the rain"),
    Triple(20_000L, "雨落在旧招牌上 一粒一粒地亮", "it lands on the old sign, bead by bead"),
    Triple(24_000L, "你说过的那句 我记成了另一句", "you said one thing, I kept another"),
    Triple(28_000L, "如果此刻有一首歌 刚好唱到这一句", "if a song reached this line right now"),
)
