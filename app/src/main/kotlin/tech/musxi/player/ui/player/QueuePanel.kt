package tech.musxi.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.PlayingBars
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.SectionTitle
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tabular
import kotlin.math.abs
import kotlin.math.roundToInt

/** One row of the queue. `key` is only for Compose identity, never shown. */
data class QueueRowUi(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val coverUrl: String? = null,
    /** Set when the row cannot play as it is (a local file gone or no longer readable): the reason, shown on the row. */
    val unavailable: String? = null,
)

/**
 * Everything the queue panel needs.
 *
 * `editing` is false while a queue command is in flight: the backend rejects edits that carry a
 * stale revision, so the panel must not offer a second drag while the first one is being applied.
 */
data class QueueUi(
    val items: List<QueueRowUi> = emptyList(),
    val currentIndex: Int = -1,
    val editing: Boolean = true,
    val revision: Long = 0,
    /** Raw slot indices after `currentIndex`, in the order playback will play them. */
    val upcoming: List<Int> = emptyList(),
    /** Counting word for the header: "首" for songs, "条" for local recordings. */
    val noun: String = "首",
)

/** What a long press on a queue row offers. Null entries are left out of the menu. */
class QueueRowActions(
    val onPlayNext: ((Int) -> Unit)? = null,
    val onAddToPlaylist: ((Int) -> Unit)? = null,
)

/** Test handles. */
internal const val QueueListTag = "queue-list"
internal const val QueueUndoTag = "queue-undo"

/**
 * The play queue's body, drawn inside the app's sheet ([QueueSheet]) so it pulls, flicks and
 * rubber-bands like every other sheet.
 *
 * Layout: the song that is playing on top, then "接下来播放", with the songs already played above it
 * in a quieter colour (they are the queue too, and jumping back is one tap). Gestures, from the
 * interaction spec:
 * - tap a row: play it;
 * - long press: the row lifts and a menu grows from it (下一首播放 · 加入歌单 · 从队列移除);
 * - drag the handle on the right: reorder; the row follows the finger, its neighbours make way,
 *   and every row crossed ticks;
 * - swipe left (or the menu): remove, with "撤销" at the bottom for four seconds; the removal is
 *   only sent when that time is up or the sheet closes.
 * In shuffled playback the rows show the real playing order and cannot be dragged.
 */
@Composable
fun ColumnScope.QueueSheetBody(
    ui: QueueUi,
    showCover: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onRemove: (Int) -> Unit = {},
    /** from, to, expected revision — the caller receives the revision captured by the gesture. */
    onMove: (Int, Int, Long) -> Unit = { _, _, _ -> },
    onClear: () -> Unit = {},
    actions: QueueRowActions = QueueRowActions(),
) {
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val rowHeight = queueRowHeight(showCover)
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    // Drag state lives here, not in the row: only this composable knows the row height that turns
    // pixels of finger travel into a number of rows, and the neighbours need it to make way.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragRevision by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var crossedRows by remember { mutableIntStateOf(0) }
    // A removal waits [UNDO_MILLIS] before it is sent, with "撤销" on screen: the row is only hidden
    // until then. The backend has no "put back at this place" command, and undoing by not sending
    // works the same for the platform queue and the local one. Closing the sheet sends it at once.
    var pending by remember { mutableStateOf<PendingRemoval?>(null) }
    val latest = androidx.compose.runtime.rememberUpdatedState(ui)
    val send = androidx.compose.runtime.rememberUpdatedState(onRemove)
    fun commit(removal: PendingRemoval?) {
        removal ?: return
        // Found again by key: the queue may have moved on while the undo bar was up.
        val index = latest.value.items.indexOfFirst { it.key == removal.key }
        if (index >= 0) send.value(index)
    }
    fun remove(row: QueueRowUi) {
        // A second removal sends the first one now; only the latest can be undone.
        commit(pending)
        pending = PendingRemoval(row.key, row.title)
    }
    val queueSnapshot = ui.items.toList()
    // Read by the handle's gesture, which must not restart when the list recomposes mid-drag (a new
    // pointerInput key would cancel it and lose the release). A queue change still cancels it below.
    val liveUi = androidx.compose.runtime.rememberUpdatedState(ui)
    val liveSize = androidx.compose.runtime.rememberUpdatedState(queueSnapshot.size)
    val order = remember(ui.items.size, ui.currentIndex, ui.upcoming) {
        queueDisplayOrder(ui.items.size, ui.currentIndex, ui.upcoming)
    }
    val shuffled = order.shuffled
    val playOrder = remember(order) { order.upcoming.withIndex().associate { (i, slot) -> slot to i + 1 } }
    val sections = remember(ui.items.size, ui.currentIndex, order) { queueSections(ui.items.size, ui.currentIndex, order) }
    val listState = rememberLazyListState()

    // A queue command changed the list under a finger: drop the lifted row at once.
    LaunchedEffect(queueSnapshot, ui.revision, ui.editing) {
        if (dragRevision != null) {
            draggingIndex = -1
            dragOffset = 0f
            dragRevision = null
        }
    }
    LaunchedEffect(pending) {
        val waiting = pending ?: return@LaunchedEffect
        delay(UNDO_MILLIS)
        if (pending == waiting) {
            commit(waiting)
            pending = null
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { commit(pending) }
    }

    QueueHeader(count = ui.items.size, noun = ui.noun, editing = ui.editing, onClear = onClear)
    val current = ui.items.getOrNull(ui.currentIndex)
    if (current != null) {
        NowPlayingCard(current, showCover, onClick = { onSelect(ui.currentIndex) })
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(QueueListTag),
        ) {
            val hidden = pending?.key
            val shown = if (hidden == null) sections else sections.filterNot { it is QueueEntry.Row && ui.items[it.slot].key == hidden }
            itemsIndexed(shown, key = { _, entry -> entry.key(ui) }) { _, entry ->
                when (entry) {
                    is QueueEntry.Heading -> QueueHeading(entry.title, note = entry.note(shuffled))
                    is QueueEntry.Row -> {
                        val index = entry.slot
                        val row = ui.items[index]
                        val lifted = index == draggingIndex
                        val shift = if (draggingIndex < 0 || lifted || entry.played) 0f
                        else makeWayOffset(index, draggingIndex, dragOffset, rowHeightPx)
                        QueueLine(
                            row = row,
                            number = if (shuffled) playOrder[index] ?: (index + 1) else index + 1,
                            played = entry.played,
                            editing = ui.editing,
                            showCover = showCover,
                            rowHeight = rowHeight,
                            showHandle = !shuffled && !entry.played,
                            lifted = lifted,
                            offsetPx = if (lifted) dragOffset else shift,
                            onClick = { onSelect(index) },
                            onLongClick = {
                                haptic(Haptic.LONG_PRESS)
                                menuFor = row.key
                            },
                            onSwipeRemove = {
                                haptic(Haptic.CONFIRM)
                                remove(row)
                            },
                            menu = {
                                QueueRowMenu(
                                    expanded = menuFor == row.key,
                                    onDismiss = { menuFor = null },
                                    onPlayNext = actions.onPlayNext?.takeIf { index != ui.currentIndex }?.let { act -> { act(index) } },
                                    onAddToPlaylist = actions.onAddToPlaylist?.let { act -> { act(index) } },
                                    onRemove = { remove(row) },
                                )
                            },
                            onHandleStart = {
                                if (!liveUi.value.editing) return@QueueLine false
                                draggingIndex = index
                                dragOffset = 0f
                                dragRevision = liveUi.value.revision
                                crossedRows = 0
                                haptic(Haptic.LONG_PRESS)
                                true
                            },
                            onHandleDrag = { amount, fingerY, rowPx ->
                                dragOffset += amount
                                val steps = dragTargetIndex(index, dragOffset, rowHeightPx, liveSize.value) - index
                                if (steps != crossedRows) {
                                    crossedRows = steps
                                    haptic(Haptic.TICK)
                                }
                                autoScroll(scope, listState, fingerY, rowPx, rowHeightPx)
                            },
                            onHandleEnd = { cancelled ->
                                val expected = dragRevision
                                // Dropped if the queue changed or editing stopped while the finger was down.
                                if (!cancelled && expected != null && liveUi.value.revision == expected && liveUi.value.editing) {
                                    val target = dragTargetIndex(index, dragOffset, rowHeightPx, liveSize.value)
                                    if (target != index) onMove(index, target, expected)
                                }
                                draggingIndex = -1
                                dragOffset = 0f
                                dragRevision = null
                            },
                        )
                    }
                }
            }
        }
        val waiting = pending
        if (waiting != null) {
            UndoBar(
                title = waiting.title,
                onUndo = { pending = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp))
}

/** How long "已移除 · 撤销" stays before the removal is sent. */
internal const val UNDO_MILLIS = 4000L

/** A removal waiting out its undo time; [key] finds the row again, [title] names it in the bar. */
private data class PendingRemoval(val key: String, val title: String)

/** One entry of the queue list: a heading or a row (a slot index into [QueueUi.items]). */
internal sealed interface QueueEntry {
    fun key(ui: QueueUi): String

    data class Heading(val title: String, val upNext: Boolean) : QueueEntry {
        override fun key(ui: QueueUi) = "heading-$title"
        fun note(shuffled: Boolean): String? =
            if (upNext && shuffled) "随机播放中，按实际顺序显示；切回顺序播放后可拖动排序" else null
    }

    data class Row(val slot: Int, val played: Boolean) : QueueEntry {
        override fun key(ui: QueueUi) = ui.items[slot].key
    }
}

/**
 * The list below the "now playing" card: up next first (what the user opened the queue for), then
 * the songs already played, quieter. Pure, so the ordering is tested without Compose.
 */
internal fun queueSections(size: Int, currentIndex: Int, order: QueueOrder): List<QueueEntry> {
    val entries = mutableListOf<QueueEntry>()
    val upcoming = order.upcoming
    if (upcoming.isNotEmpty()) {
        entries += QueueEntry.Heading("接下来播放", upNext = true)
        upcoming.forEach { entries += QueueEntry.Row(it, played = false) }
    }
    // Shuffled: slots before the current one are not a history, they are still to come in the
    // timeline above; only sequential playback has a "played" part.
    val played = if (order.shuffled || currentIndex < 0) emptyList() else (0 until currentIndex.coerceAtMost(size)).toList()
    if (played.isNotEmpty()) {
        entries += QueueEntry.Heading("已播放", upNext = false)
        played.forEach { entries += QueueEntry.Row(it, played = true) }
    }
    return entries
}

/**
 * How far a neighbour moves while row [dragging] is carried by [offset] px: the rows between the
 * lifted row's old and new place slide one row the other way, so the gap opens where it will land.
 */
internal fun makeWayOffset(index: Int, dragging: Int, offset: Float, rowHeightPx: Float): Float {
    if (rowHeightPx <= 0f) return 0f
    val steps = (offset / rowHeightPx).roundToInt()
    val target = dragging + steps
    return when {
        steps > 0 && index in (dragging + 1)..target -> -rowHeightPx
        steps < 0 && index in target until dragging -> rowHeightPx
        else -> 0f
    }
}

/** Scrolls the list a little while a dragged row is held near its top or bottom edge. */
private fun autoScroll(
    scope: kotlinx.coroutines.CoroutineScope,
    listState: LazyListState,
    fingerY: Float,
    rowHeight: Float,
    rowHeightPx: Float,
) {
    // fingerY is relative to the row; the list edges are judged from the row's own position.
    val viewport = listState.layoutInfo.viewportEndOffset.toFloat()
    val info = listState.layoutInfo.visibleItemsInfo
    if (info.isEmpty() || viewport <= 0f) return
    val edge = rowHeightPx
    val rowTop = info.firstOrNull()?.offset?.toFloat() ?: return
    val y = rowTop + fingerY
    val delta = when {
        y < edge -> -(edge - y) / 4f
        y > viewport - edge -> (y - (viewport - edge)) / 4f
        else -> 0f
    }
    if (delta != 0f && rowHeight > 0f) scope.launch { listState.scrollBy(delta) }
}

/**
 * Where a row dragged by offsetPx ends up.
 *
 * Rounded, not truncated: half a row is the threshold, which is where the eye already expects the
 * swap. Clamped to the list so over-dragging past either end is a no-op instead of a crash.
 */
internal fun dragTargetIndex(from: Int, offsetPx: Float, rowHeightPx: Float, size: Int): Int {
    if (rowHeightPx <= 0f || size <= 0) return from
    val steps = (offsetPx / rowHeightPx).roundToInt()
    return (from + steps).coerceIn(0, size - 1)
}

/**
 * Height of a queue row. Fixed, because the drag turns finger travel into a number of rows with it,
 * but computed: the two text lines at the current font scale plus the shared row padding, never less
 * than [RowSpec.minHeight]. At 200% the row grows instead of clipping its text.
 */
@Composable
internal fun queueRowHeight(showCover: Boolean): Dp {
    val type = MaterialTheme.typography
    val text = with(LocalDensity.current) { type.titleSmall.lineHeight.toDp() + type.bodySmall.lineHeight.toDp() }
    val lead = if (showCover) RowSpec.coverCompact else 0.dp
    return maxOf(RowSpec.minHeight, maxOf(text, lead) + RowSpec.vertical * 2)
}

@Composable
private fun QueueHeader(count: Int, noun: String, editing: Boolean, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("播放队列", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                "$count $noun",
                style = MaterialTheme.typography.bodySmall.tabular,
                color = cs.onSurfaceVariant,
            )
        }
        // Neutral, not destructive red: clearing a queue is reversible (the songs stay in the
        // library) and red here would be the loudest thing on a quiet page.
        Text(
            "清空",
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (editing && count > 0) Modifier.combinedClickable(onClick = onClear) else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (editing && count > 0) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** The song that is playing, set apart above the list: what "you are here" means in a queue. */
@Composable
private fun NowPlayingCard(row: QueueRowUi, showCover: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = nowPlayingAccent()
    Row(
        Modifier
            .padding(horizontal = Space.page - 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            Cover(seed = row.title, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small, url = row.coverUrl)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.artist,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        PlayingBars(accent, tallest = 14.dp)
        Spacer(Modifier.width(8.dp))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun QueueHeading(title: String, note: String?) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 12.dp, bottom = 4.dp)) {
        SectionTitle(title)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueLine(
    row: QueueRowUi,
    number: Int,
    played: Boolean,
    editing: Boolean,
    showCover: Boolean,
    rowHeight: Dp,
    showHandle: Boolean,
    lifted: Boolean,
    offsetPx: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeRemove: () -> Unit,
    menu: @Composable () -> Unit,
    /** The handle was grabbed; false refuses the drag (editing is off). */
    onHandleStart: () -> Boolean,
    /** Finger moved [amount] px down; [fingerY] and [rowPx] place it for edge scrolling. */
    onHandleDrag: (amount: Float, fingerY: Float, rowPx: Float) -> Unit,
    /** Finger lifted (or the gesture was cancelled). */
    onHandleEnd: (cancelled: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Swipe left to remove: the row follows the finger, and past a third of its width it leaves.
    val swipe = remember(row.key) { Animatable(0f) }
    val dim = if (played) 0.55f else 1f
    Box(Modifier.fillMaxWidth().height(rowHeight)) {
        // What a swipe uncovers: the remove mark, at the right edge, in the error colour.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val reveal = (-swipe.value / size.width).coerceIn(0f, 1f)
                if (reveal > 0f) drawRect(cs.errorContainer.copy(alpha = (reveal * 3f).coerceAtMost(1f)))
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (swipe.value < 0f) {
                Icon(
                    Icons.Outlined.DeleteOutline, null,
                    tint = cs.onErrorContainer,
                    modifier = Modifier.padding(end = Space.page).size(22.dp),
                )
            }
        }
        Box {
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = swipe.value
                        translationY = offsetPx
                        // The carried row sits above its neighbours, a little larger, with a shadow.
                        shadowElevation = if (lifted) 12.dp.toPx() else 0f
                        if (lifted) {
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .background(if (lifted || swipe.value != 0f) cs.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
                    .pointerInput(row.key, editing) {
                        if (!editing) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (-swipe.value > size.width / 3f) {
                                        swipe.animateTo(-size.width.toFloat(), Motion.release())
                                        onSwipeRemove()
                                        swipe.snapTo(0f)
                                    } else {
                                        swipe.animateTo(0f, Motion.release())
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f, Motion.release()) } },
                        ) { change, amount ->
                            // Left only: a right swipe has nothing to reveal.
                            val next = (swipe.value + amount).coerceAtMost(0f)
                            if (abs(amount) > 0f) change.consume()
                            scope.launch { swipe.snapTo(next) }
                        }
                    }
                    .then(
                        if (editing) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
                    )
                    .padding(horizontal = Space.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val slot = (if (showCover) 24.dp else 28.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)
                Box(Modifier.width(slot), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f * dim),
                    )
                }
                Spacer(Modifier.width(if (showCover) 10.dp else 14.dp))
                if (showCover) {
                    Cover(
                        seed = row.title,
                        modifier = Modifier.size(40.dp).graphicsLayer { alpha = dim },
                        shape = MaterialTheme.shapes.small,
                        url = row.coverUrl,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface.copy(alpha = if (row.unavailable != null) 0.6f else 0.92f * dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val reason = row.unavailable
                    Text(
                        if (reason == null) {
                            androidx.compose.ui.text.AnnotatedString(row.artist)
                        } else {
                            androidx.compose.ui.text.buildAnnotatedString {
                                if (row.artist.isNotBlank()) append("${row.artist} · ")
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = cs.error))
                                append(reason)
                                pop()
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(row.durationMs),
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = cs.onSurfaceVariant.copy(alpha = dim),
                )
                // The handle drags right away; the rest of the row taps to play and long-presses
                // for the menu. One gesture per place, as the spec asks.
                val start = androidx.compose.runtime.rememberUpdatedState(onHandleStart)
                val move = androidx.compose.runtime.rememberUpdatedState(onHandleDrag)
                val end = androidx.compose.runtime.rememberUpdatedState(onHandleEnd)
                val handle = if (!showHandle) Modifier else Modifier.pointerInput(row.key) {
                    var active = false
                    // Vertical only, and it consumes the touch, so the row's sideways swipe never
                    // sees a drag that started on the handle. No long press: it exists only for this.
                    detectVerticalDragGestures(
                        onDragStart = { active = start.value() },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            if (active) move.value(amount, change.position.y, size.height.toFloat())
                        },
                        onDragEnd = { if (active) end.value(false); active = false },
                        onDragCancel = { if (active) end.value(true); active = false },
                    )
                }
                Box(Modifier.size(40.dp).then(handle), contentAlignment = Alignment.Center) {
                    if (showHandle) {
                        Icon(
                            Icons.Filled.DragHandle, "拖动排序",
                            tint = cs.onSurfaceVariant.copy(alpha = if (editing) 0.55f else 0.25f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomStart)) { menu() }
        }
    }
}

@Composable
private fun QueueRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, preferAbove = false) {
        if (onPlayNext != null) {
            MenuItem("下一首播放", onClick = { onDismiss(); onPlayNext() }, icon = Icons.Outlined.SkipNext)
        }
        if (onAddToPlaylist != null) {
            MenuItem("加入歌单", onClick = { onDismiss(); onAddToPlaylist() }, icon = Icons.AutoMirrored.Outlined.PlaylistAdd)
        }
        MenuItem("从队列移除", onClick = { onDismiss(); onRemove() }, icon = Icons.Outlined.DeleteOutline)
    }
}

@Composable
private fun UndoBar(title: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = Space.page, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.inverseSurface)
            .testTag(QueueUndoTag)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "已移除「$title」",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.inverseOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "撤销",
            style = MaterialTheme.typography.labelLarge,
            color = cs.inversePrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}


/**
 * The queue as a sheet over the player: the app's [tech.musxi.player.ui.components.MusxiSheet], so it
 * opens at half height, flicks to full, rubber-bands and closes by pulling down like every other
 * sheet, instead of the bespoke panel pinned to the bottom it used to be.
 *
 * Always keep the call in the tree and drive it with [visible]: the sheet slides out showing what it
 * showed.
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    ui: QueueUi,
    onDismiss: () -> Unit,
    showCover: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onRemove: (Int) -> Unit = {},
    onMove: (Int, Int, Long) -> Unit = { _, _, _ -> },
    onClear: () -> Unit = {},
    actions: QueueRowActions = QueueRowActions(),
) {
    tech.musxi.player.ui.components.MusxiSheet(visible = visible, onDismiss = onDismiss) {
        QueueSheetBody(
            ui = ui,
            showCover = showCover,
            onSelect = onSelect,
            onRemove = onRemove,
            onMove = onMove,
            onClear = onClear,
            actions = actions,
        )
    }
}
