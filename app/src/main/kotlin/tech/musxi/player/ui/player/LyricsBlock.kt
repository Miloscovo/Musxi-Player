package tech.musxi.player.ui.player

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.Size
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.TextLayoutResult
import tech.musxi.core.lyrics.LyricWordPosition
import tech.musxi.core.lyrics.LyricsWordTimeline
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.theme.Motion
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
    /** Tint the current line with the cover's colour ("颜色跟随封面"), or leave it plainly brightest. */
    accentCurrent: Boolean = false,
    /** The "字号" setting of the 我的 page; it sizes the lyrics only. */
    fontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    /**
     * Where the song is between playback updates. Given, a line with word timing sweeps word by
     * word; null (design sheets, tests) leaves every line highlighted as a whole.
     */
    playhead: LyricsPlayhead? = null,
    onSeekLine: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onRelogin: () -> Unit = {},
    /** The finger touched the lyrics: the page stops following until it is released for a while. */
    onUserScroll: () -> Unit = {},
    onReturnToCurrent: () -> Unit = {},
    onToggleTranslation: () -> Unit = {},
    onRefreshTranslation: () -> Unit = {},
    /**
     * Read-only (the lock-screen page): lines cannot be tapped to seek and the list does not scroll
     * by hand, so a phone in a pocket cannot be talked into anything. It still follows the song.
     */
    readOnly: Boolean = false,
    /** Scales every lyric size on top of [fontSize]; the lock screen reads from further away. */
    sizeScale: Float = 1f,
) {
    val sizes = lyricsTextSizes(fontSize).scaledBy(sizeScale)
    Box(modifier) {
        when (ui.phase) {
            LyricsPhase.LOADING -> Skeleton(Modifier.align(Alignment.Center))
            LyricsPhase.TIMED -> TimedLyrics(
                ui = ui,
                accentCurrent = accentCurrent,
                sizes = sizes,
                playhead = playhead,
                onSeekLine = onSeekLine,
                onUserScroll = onUserScroll,
                readOnly = readOnly,
            )
            LyricsPhase.PLAIN -> PlainLyrics(ui, sizes)
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
        if (ui.phase == LyricsPhase.TIMED && ui.browsing && !readOnly) {
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
    sizes: LyricsTextSizes,
    playhead: LyricsPlayhead?,
    onSeekLine: (Long) -> Unit,
    onUserScroll: () -> Unit,
    readOnly: Boolean = false,
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
            viewportSize, density, sizes,
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
            userScrollEnabled = !readOnly,
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
                    sizes = sizes,
                    // Only the current line sweeps; the rest keep their whole-line colour.
                    playhead = if (index == ui.activeIndex) playhead else null,
                    offsetMs = ui.offsetMs,
                    // Fading by distance from the current line rather than by a mask over the block:
                    // a mask has to be a colour, and the backdrop is a gradient, so any mask colour
                    // shows up as a rectangle of the wrong shade.
                    distance = if (ui.activeIndex < 0) 9 else index - ui.activeIndex,
                    onSeek = if (readOnly) null else ({ onSeekLine(line.timeMs) }),
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
    sizes: LyricsTextSizes,
    playhead: LyricsPlayhead?,
    offsetMs: Long,
    distance: Int,
    /** Null on a read-only block: the row is then not a button at all. */
    onSeek: (() -> Unit)?,
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
        animationSpec = Motion.enter(),
        label = "lyric-scale",
    )
    Box(
        Modifier.fillMaxWidth()
            .then(if (onSeek != null) Modifier.clickable(onClick = onSeek) else Modifier)
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
                // Emphasis belongs to the active row, regardless of script, translation data or
                // whether translations are visible.
                val style = if (active) lyricStyle(sizes).copy(fontWeight = FontWeight.SemiBold) else lyricStyle(sizes)
                if (active && playhead != null && line.words.isNotEmpty()) {
                    SweptLine(
                        line = line,
                        style = style,
                        sungColor = currentColor,
                        // The part still to come is the line's own colour, dimmed: the line stays
                        // the brightest on the page while the sweep shows how far the voice is.
                        waitingColor = currentColor.copy(alpha = currentColor.alpha * 0.42f),
                        playhead = playhead,
                        offsetMs = offsetMs,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                } else {
                    Text(
                        line.text,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                        style = style,
                        color = textColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (translation != null) {
                Text(
                    translation,
                    modifier = Modifier.padding(top = 3.dp),
                    style = translationStyle(sizes),
                    color = translationColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The current line with a soft sweep: what has been sung in the line's full colour, what is still to
 * come dimmed, and a short feathered edge between them that moves with the voice.
 *
 * The text is laid out once, the same way as an ordinary line, and the colours are only painted
 * over it, so the sweep never moves a glyph or changes the line's height. The edge follows each
 * word's own box, so a word broken onto a second row fills that row in turn, and a centred line
 * with different row widths still fills from the first glyph of each row.
 *
 * Only this row reads the playhead, inside the draw phase, so the frames redraw one line without
 * recomposing the list.
 */
@Composable
private fun SweptLine(
    line: LyricLine,
    style: TextStyle,
    sungColor: Color,
    waitingColor: Color,
    playhead: LyricsPlayhead,
    offsetMs: Long,
    modifier: Modifier = Modifier,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    // The position the sweep draws. Advanced every frame only while the song plays; a paused or
    // seeked song gets one update per playback report, so a still page does not redraw.
    var shownMs by remember { mutableLongStateOf(playhead.positionNow()) }
    LaunchedEffect(playhead.playing, playhead.revision) {
        shownMs = playhead.positionNow()
        while (playhead.playing) withFrameMillis { shownMs = playhead.positionNow() }
    }
    val feather = with(LocalDensity.current) { 14.dp.toPx() }
    Text(
        line.text,
        modifier = modifier
            // The glyphs are drawn once in white and then coloured in place (SrcIn), which needs a
            // layer of their own so the colour does not reach the page behind.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val result = layout ?: return@drawWithContent
                val position = LyricsWordTimeline.sample(line.words, shownMs - offsetMs)
                for (row in 0 until result.lineCount) {
                    val left = result.getLineLeft(row)
                    val right = result.getLineRight(row)
                    val width = (right - left).coerceAtLeast(1f)
                    val edge = (sweepEdgeOnRow(result, line, position, row, left, right) - left) / width
                    val half = feather / 2f / width
                    // The first and last rows reach the layer's edges, so no glyph is left uncoloured.
                    val top = if (row == 0) 0f else result.getLineTop(row)
                    val bottom = if (row == result.lineCount - 1) size.height else result.getLineBottom(row)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to sungColor,
                            (edge - half).coerceIn(0f, 1f) to sungColor,
                            (edge + half).coerceIn(0f, 1f) to waitingColor,
                            1f to waitingColor,
                            startX = left,
                            endX = right,
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, bottom - top),
                        blendMode = BlendMode.SrcIn,
                    )
                }
            },
        style = style,
        color = Color.White,
        textAlign = TextAlign.Center,
        onTextLayout = { layout = it },
    )
}

/**
 * Where the sung part of [row] ends, in pixels. A row before the current word's row is full, a row
 * after it is empty; on its own row the edge stands inside the current word at its progress.
 */
private fun sweepEdgeOnRow(
    layout: TextLayoutResult,
    line: LyricLine,
    position: LyricWordPosition,
    row: Int,
    rowLeft: Float,
    rowRight: Float,
): Float {
    if (position.index < 0) return rowLeft
    val word = line.words[position.index]
    val wordRowStart = layout.getLineForOffset(word.textStart)
    val wordRowEnd = layout.getLineForOffset((word.textEnd - 1).coerceAtLeast(word.textStart))
    return when {
        row < wordRowStart -> rowRight
        row > wordRowEnd -> rowLeft
        else -> {
            // The part of the word on this row: from its first glyph here to its last glyph here.
            val rowStartOffset = maxOf(word.textStart, layout.getLineStart(row))
            val rowEndOffset = minOf(word.textEnd, layout.getLineEnd(row, visibleEnd = true))
            if (rowEndOffset <= rowStartOffset) return if (row < wordRowEnd) rowRight else rowLeft
            val from = layout.getBoundingBox(rowStartOffset).left
            val to = layout.getBoundingBox(rowEndOffset - 1).right
            // Split the word's progress across its rows by width, so a broken word fills evenly.
            val rows = (wordRowStart..wordRowEnd).map { r ->
                val s = maxOf(word.textStart, layout.getLineStart(r))
                val e = minOf(word.textEnd, layout.getLineEnd(r, visibleEnd = true))
                if (e <= s) 0f else layout.getBoundingBox(e - 1).right - layout.getBoundingBox(s).left
            }
            val total = rows.sum().coerceAtLeast(1f)
            val before = rows.take(row - wordRowStart).sum()
            val filled = (position.progress * total - before).coerceIn(0f, to - from)
            from + filled
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
@Composable
private fun lyricStyle(sizes: LyricsTextSizes): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
        fontSize = sizes.line,
        lineHeight = sizes.lineHeight,
        fontWeight = FontWeight.Medium,
        // Balanced breaking: with centred text a single orphan word on the second row reads as a
        // stray island ("rain", "bead", "now" all did).
        lineBreak = LineBreak.Heading,
    )

@Composable
private fun translationStyle(sizes: LyricsTextSizes): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = sizes.translation,
        lineHeight = sizes.translationLineHeight,
        fontWeight = FontWeight.Normal,
    )

/** Text with no timeline: readable, scrollable, no highlight, and one line saying why. */
@Composable
private fun PlainLyrics(ui: LyricsUi, sizes: LyricsTextSizes) {
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
            // A little more air than timed lines: plain text is read as a page, not followed.
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = sizes.line, lineHeight = sizes.lineHeight * (26f / 24f)),
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
