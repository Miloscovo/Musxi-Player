package tech.musxi.player.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.first
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.player.ui.theme.nowPlayingAccent

/**
 * The lyrics block: the one thing on the player page that can replace the cover.
 *
 * It takes the cover's place *and the air above it*. That air existed only to stop the square from
 * touching the top bar; once the slot holds text instead of artwork, the space belongs to the text.
 * Translation and favourite actions are grouped beside the song information below, leaving the
 * reading area free of controls; playback states stay visible in the title block's third line.
 *
 * Alignment is centred and nothing sits on an edge. The cost of centring — the eye losing a fixed
 * line start — is paid for with the paragraph gap and the current line's weight.
 */
@Composable
fun LyricsBlock(
    ui: LyricsUi,
    modifier: Modifier = Modifier,
    /** Tint the current line, or leave it plainly brightest. Off by decision. */
    accentCurrent: Boolean = false,
    onSeekLine: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onRelogin: () -> Unit = {},
    /** The finger touched the lyrics: the page stops following until it is released for a while. */
    onUserScroll: () -> Unit = {},
    onReturnToCurrent: () -> Unit = {},
    onToggleTranslation: () -> Unit = {},
    onRefreshTranslation: () -> Unit = {},
) {
    Box(modifier) {
        when (ui.phase) {
            LyricsPhase.LOADING -> Skeleton(Modifier.align(Alignment.Center))
            LyricsPhase.TIMED -> TimedLyrics(
                ui = ui,
                accentCurrent = accentCurrent,
                onSeekLine = onSeekLine,
                onUserScroll = onUserScroll,
            )
            LyricsPhase.PLAIN -> PlainLyrics(ui)
            LyricsPhase.EMPTY -> EmptyLyrics(Modifier.align(Alignment.Center))
            LyricsPhase.ERROR -> FailedLyrics(
                ui.errorKind,
                onRetry,
                onRelogin,
                Modifier.align(Alignment.Center),
            )
        }

        // A way back without waiting out the timer. Auto-resume after three seconds exists so the
        // page never gets stuck, but someone rereading a verse should not have to race it.
        if (ui.phase == LyricsPhase.TIMED && ui.browsing) {
            ReturnToCurrent(
                onClick = onReturnToCurrent,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
    }
}

/**
 * The way out of browsing.
 *
 * 48dp of touch target even though the pill is 32dp tall: the same mistake as the translation chip
 * — a control smaller than a fingertip is not a control. `minimumInteractiveComponentSize` keeps the
 * padding visual and the target honest.
 */
@Composable
private fun ReturnToCurrent(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        "回到当前",
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = cs.onSurfaceVariant.copy(alpha = 0.85f),
    )
}

@Composable
private fun TimedLyrics(
    ui: LyricsUi,
    accentCurrent: Boolean,
    onSeekLine: (Long) -> Unit,
    onUserScroll: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        val density = LocalDensity.current
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        // A group can be one line, bilingual, or several wrapped lines. Follow its measured centre,
        // not its top. No extra end padding: at either boundary the content itself stops the scroll.
        // Short documents do not scroll at all; Arrangement.Center balances the whole document.
        LaunchedEffect(
            ui.activeIndex, ui.isFollowing, ui.lines, ui.translation.showsText,
            viewportSize, density,
        ) {
            // Before the first timestamp (including a new song), show the document's beginning.
            val index = ui.activeIndex.coerceAtLeast(0)
            val viewportHeight = viewportSize.height
            if (!ui.isFollowing || index !in ui.lines.indices || viewportHeight <= 0) return@LaunchedEffect
            // Wait for the actual list, including the first layout, rather than losing the initial
            // request when layoutInfo still reports a zero viewport.
            snapshotFlow { listState.layoutInfo }.first {
                it.viewportSize == viewportSize && it.totalItemsCount == ui.lines.size
            }
            // Realise an offscreen row before measuring it. scrollToItem remeasures synchronously,
            // so both placements complete together without exposing an intermediate frame.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index, -viewportHeight / 2)
            }
            val row = snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }
                .first { it != null } ?: return@LaunchedEffect
            listState.scrollToItem(index, (row.size - viewportHeight) / 2)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .testTag("lyrics-viewport")
                // A true transparency fade at both edges. The obvious version — drawing a gradient
                // of the backdrop colour over the list — cannot work here: the backdrop is itself a
                // gradient, so any single colour shows as a band. Offscreen + DstIn fades the
                // content's alpha instead of painting anything.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // Clip as well as fade: without it a line cut by the block's bottom edge left a
                    // row of stray glyph tops outside the fade, which read as dirt on the screen.
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    // A short document has no hidden context. Do not fade its first/last lines,
                    // or erase the boundary line after following reaches the beginning or end.
                    val topAlpha = if (listState.canScrollBackward) 0f else 1f
                    val bottomAlpha = if (listState.canScrollForward) 0f else 1f
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = topAlpha),
                            0.10f to Color.Black,
                            0.90f to Color.Black,
                            1f to Color.Black.copy(alpha = bottomAlpha),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .pointerInput(Unit) {
                // Watch, do not consume: the list keeps its own scroll, and a finger on it is what
                // "the user has taken over" means. Pass.Initial sees the event before the list does.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && it.positionChange() != Offset.Zero }) {
                            onUserScroll()
                        }
                    }
                }
            },
            verticalArrangement = Arrangement.Center,
        ) {
            itemsIndexed(ui.lines) { index, line ->
                LyricRow(
                    line = line,
                    translation = ui.translationOf(line),
                    nextTimeMs = ui.lines.getOrNull(index + 1)?.timeMs,
                    active = index == ui.activeIndex,
                    accentCurrent = accentCurrent,
                    // Fading by distance from the current line rather than by a mask over the block:
                    // a mask has to be a colour, and the backdrop is a gradient, so any mask colour
                    // shows up as a rectangle of the wrong shade.
                    distance = if (ui.activeIndex < 0) 9 else index - ui.activeIndex,
                    onSeek = { onSeekLine(line.timeMs) },
                )
            }
        }
    }
}

/**
 * One lyric group: the original line and, when it exists and is switched on, its translation.
 *
 * Tapping a line seeks straight to it — one step, like the PC build. The two-step version (pick a
 * line, then press its play mark) protected against mis-taps but cost every deliberate jump an
 * extra press, and the row is already a large target that the scroll gesture protects.
 *
 * A blank line is not a spacer: it marks an instrumental stretch, so it shows ♪ and can be tapped
 * like any other line. An empty band gave no clue whether the song was still going.
 *
 * The current line is enlarged with `graphicsLayer` scale, **not** by changing its font size: a
 * bigger font re-measures the line, changes its box, and pushes every line below it.
 */
@Composable
private fun LyricRow(
    line: LyricLine,
    translation: String?,
    nextTimeMs: Long?,
    active: Boolean,
    accentCurrent: Boolean,
    distance: Int,
    onSeek: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val blank = line.text.isBlank()
    // An interlude shorter than four seconds is a breath between lines, not an instrumental
    // section; marking it would turn the list into a wall of dots.
    val interlude = blank && nextTimeMs != null && nextTimeMs - line.timeMs >= 4_000
    if (blank && !interlude) {
        Spacer(Modifier.height(12.dp))
        return
    }
    val currentColor = if (accentCurrent) nowPlayingAccent() else cs.onSurface
    val textColor = if (active) currentColor else cs.onSurfaceVariant.copy(alpha = fadeOf(distance))
    // The translation floor is higher than the lyric's: it starts quieter, so fading it by the same
    // curve would make it disappear two lines away from the current one.
    // The current line's translation is part of "now singing", not part of the context: at 0.7 it sat
    // at the same weight as the next line's original and the eye filed it away with the background.
    val translationColor = if (active) {
        cs.onSurfaceVariant.copy(alpha = 0.78f)
    } else {
        cs.onSurfaceVariant.copy(alpha = maxOf(0.45f, fadeOf(distance)))
    }
    val scale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "lyric-scale",
    )
    Box(
        Modifier.fillMaxWidth()
            .clickable(onClick = onSeek)
            // The marker's ink is only 10dp tall against a text line's ~12dp, so a full text row's
            // padding would leave it floating in its own band. Four dp instead of eight keeps the
            // gap above and below it within a couple of dp of a normal line.
            .padding(horizontal = 20.dp, vertical = if (interlude) 4.dp else 8.dp),
    ) {
        // fillMaxWidth + horizontalAlignment is what actually centres the line: a Text without a
        // full width is only as wide as its glyphs, so `textAlign = Center` had nothing to centre
        // it in and every non-active line hugged the left edge.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (interlude) {
                // Three dots rather than a "♪" glyph: the character's ink is a fraction of its em
                // box (measured 5x8.5dp at 16sp) and read as a smudge. The dots are drawn at a
                // fixed size so they cannot shrink with the font.
                InstrumentalDots(active = active, distance = distance)
            } else {
                Text(
                    line.text,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                    // Emphasis belongs to the active row, regardless of script, translation data
                    // or whether translations are visible.
                    style = if (active) {
                        lyricStyle.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        lyricStyle
                    },
                    color = textColor,
                    textAlign = TextAlign.Center,
                )
            }
            if (translation != null) {
                Text(
                    translation,
                    modifier = Modifier.padding(top = 3.dp),
                    style = translationStyle,
                    color = translationColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The instrumental marker: three dots, 8dp, 6dp apart.
 *
 * The row box is exactly one text row tall (24dp line box + the same 8dp padding), so the rhythm of
 * the list does not change when a stretch has no words. An earlier version used a taller box with
 * smaller dots, which made the gap above and below the marker visibly different from every other
 * line.
 *
 * Static on purpose: the direction asked for a 1.2s breathing loop, but an infinite animation never
 * lets the screenshot tests idle — this project has already had a sheet hang because of one.
 */
@Composable
private fun InstrumentalDots(active: Boolean, distance: Int) {
    val cs = MaterialTheme.colorScheme
    // Lighter than any lyric line: at 0.7 solid the dots read as a control ("more") and competed
    // with the current line instead of describing the stretch between lines.
    val alpha = if (active) 0.55f else 0.3f
    Row(
        Modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(cs.onSurface.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * One type scale for every line; the enlargement is painted, not laid out.
 *
 * 16sp on a 24sp line: about sixteen Chinese characters across the block, which is the size that
 * lets a normal lyric line stay on one row. The current line is scaled to ~18sp, so the hierarchy
 * is kept without spending width on every line. Translation uses 13sp/20sp for readable CJK text.
 */
private val lyricStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        // Balanced breaking: with centred text a single orphan word on the second row reads as a
        // stray island ("rain", "bead", "now" all did).
        lineBreak = LineBreak.Heading,
    )

private val translationStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )

/** Text with no timeline: readable, scrollable, no highlight, and one line saying why. */
@Composable
private fun PlainLyrics(ui: LyricsUi) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "这首歌没有时间轴，不能跟着滚动",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            ui.plainText,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLyrics(modifier: Modifier = Modifier) {
    Text(
        "这首歌没有歌词",
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The reasons a read failed, each with its own next step.
 *
 * [LyricsErrorKind.FORMAT] exists because the alternative was worse: protocol errors and over-limit
 * documents used to reach the user as "this platform does not provide lyrics", which blames the
 * platform for our failure and offers no way out.
 */
@Composable
private fun FailedLyrics(
    kind: LyricsErrorKind,
    onRetry: () -> Unit,
    onRelogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (kind) {
                LyricsErrorKind.NETWORK -> "网络断了，歌词没读到"
                LyricsErrorKind.ACCOUNT -> "登录已过期"
                LyricsErrorKind.RATE -> "请求太频繁"
                LyricsErrorKind.FORMAT -> "歌词读不出来"
                LyricsErrorKind.UNAVAILABLE -> "这个平台没有提供歌词"
            },
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (kind) {
                LyricsErrorKind.NETWORK -> "检查一下网络，再试一次"
                LyricsErrorKind.ACCOUNT -> "重新登录后歌词会恢复"
                LyricsErrorKind.RATE -> "稍等一下再试"
                // Neutral: it says what happened, not who is to blame, because we do not know.
                LyricsErrorKind.FORMAT -> "换个时机重试，多半能读到"
                LyricsErrorKind.UNAVAILABLE -> "换一首，或者到别的平台找这首歌"
            },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        when (kind) {
            LyricsErrorKind.NETWORK, LyricsErrorKind.RATE, LyricsErrorKind.FORMAT -> {
                Spacer(Modifier.height(12.dp))
                // Not the accent: on this page the accent marks the playing line and the main
                // button, and a retry is neither.
                TextButton(onClick = onRetry) {
                    Text("重试", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                }
            }
            LyricsErrorKind.ACCOUNT -> {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRelogin) {
                    Text("重新登录", style = MaterialTheme.typography.labelLarge)
                }
            }
            LyricsErrorKind.UNAVAILABLE -> Unit
        }
    }
}

/**
 * Loading is three quiet bars, not a spinner: an indeterminate indicator never idles, which hangs
 * the screenshot tests and tells the user nothing they did not already know.
 */
@Composable
private fun Skeleton(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf(214.dp, 268.dp, 176.dp).forEach { barWidth ->
            Box(
                Modifier.width(barWidth).height(12.dp).clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.12f)),
            )
        }
    }
}

/** The soft falloff the two fading edges were for, without painting anything over the page. */
private fun fadeOf(distance: Int): Float = when (val d = kotlin.math.abs(distance)) {
    0 -> 1f
    1 -> 0.82f
    2 -> 0.62f
    3 -> 0.44f
    else -> 0.3f
}
