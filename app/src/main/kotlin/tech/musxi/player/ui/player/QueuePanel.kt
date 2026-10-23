package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.tabular

/** One row of the queue. `key` is only for Compose identity, never shown. */
data class QueueRowUi(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
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
)

/**
 * The play queue: a bottom sheet over the player.
 *
 * Same shell as the source panel — rounded top, `surfaceContainerHigh`, a grab handle — because two
 * different sheet shapes for two interruptions of the same page would be one shape too many.
 *
 * The current row is marked with the accent, the only place in this panel that takes colour: the
 * queue's job is to say "you are here" in a list of identical rows, and a 6dp dot would be too quiet
 * to find. Everything else — numbers, durations, drag handles — stays neutral.
 */
@Composable
fun QueuePanel(
    ui: QueueUi,
    modifier: Modifier = Modifier,
    /** Covers identify a track; without them the list leans on numbering alone. Both are on the table. */
    showCover: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onRemove: (Int) -> Unit = {},
    /** from, to, expected revision — the caller receives the revision captured by the gesture. */
    onMove: (Int, Int, Long) -> Unit = { _, _, _ -> },
    onClear: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val rowHeight = if (showCover) 60.dp else 56.dp
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    // Drag state lives here, not in the row: only this composable knows the row height that turns
    // pixels of finger travel into a number of rows.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragRevision by remember { mutableStateOf<Long?>(null) }
    // A copy makes the pointer key sensitive to row content as well as the collection identity.
    val queueSnapshot = ui.items.toList()

    // A pointerInput key change cancels the gesture coroutine. Clearing these values here also
    // removes the lifted row immediately if that cancellation is delivered before the next frame.
    LaunchedEffect(queueSnapshot, ui.revision, ui.editing) {
        if (dragRevision != null) {
            draggingIndex = -1
            dragOffset = 0f
            dragRevision = null
        }
    }

    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(cs.surfaceContainerHigh)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
        Header(count = ui.items.size, editing = ui.editing, onClear = onClear, onClose = onClose)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 380.dp)
                // The list is taller than the screen on a short queue screen: fade the bottom so the
                // cut row reads as "there is more" instead of as a rendering fault.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.88f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(ui.items, key = { _, row -> row.key }) { index, row ->
                val lifted = index == draggingIndex
                QueueLine(
                    index = index,
                    row = row,
                    current = index == ui.currentIndex,
                    editing = ui.editing,
                    showCover = showCover,
                    lifted = lifted,
                    dragOffsetPx = if (lifted) dragOffset else 0f,
                    onClick = { onSelect(index) },
                    onRemove = { onRemove(index) },
                    modifier = Modifier.pointerInput(
                        index,
                        row.key,
                        ui.editing,
                        ui.revision,
                        queueSnapshot,
                    ) {
                        if (!ui.editing) return@pointerInput
                        var gestureRevision: Long? = null
                        var gestureEditing = false
                        // Long-press, then drag — the platform habit for reordering a list. The
                        // whole row is the target, not just the 20dp handle: the finger is already
                        // on the song's name, and a handle that small is a poor thing to chase.
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                gestureRevision = ui.revision
                                gestureEditing = ui.editing
                                draggingIndex = index
                                dragOffset = 0f
                                dragRevision = ui.revision
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val expectedRevision = gestureRevision
                                gestureRevision = null
                                if (expectedRevision != null &&
                                    dragRevision == expectedRevision &&
                                    gestureEditing
                                ) {
                                    val target = dragTargetIndex(
                                        index,
                                        dragOffset,
                                        rowHeightPx,
                                        queueSnapshot.size,
                                    )
                                    if (target != index) onMove(index, target, expectedRevision)
                                }
                                draggingIndex = -1
                                dragOffset = 0f
                                dragRevision = null
                                gestureEditing = false
                            },
                            onDragCancel = {
                                gestureRevision = null
                                gestureEditing = false
                                draggingIndex = -1
                                dragOffset = 0f
                                dragRevision = null
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(count: Int, editing: Boolean, onClear: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("播放队列", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                "$count 首 · 点一首跳到它",
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
                .clickable(enabled = editing && count > 0, onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (editing && count > 0) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun QueueLine(
    index: Int,
    row: QueueRowUi,
    current: Boolean,
    editing: Boolean,
    showCover: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    dragOffsetPx: Float = 0f,
) {
    val cs = MaterialTheme.colorScheme
    val accent = nowPlayingAccent()
    Row(
        modifier.fillMaxWidth().height(if (showCover) 60.dp else 56.dp)
            // The dragged row travels with the finger and sits above its neighbours, so the list
            // says "this one is in your hand" without reordering data until the finger lifts.
            .graphicsLayer {
                translationY = dragOffsetPx
                shadowElevation = if (lifted) 12.dp.toPx() else 0f
                if (lifted) {
                    scaleX = 1.01f
                    scaleY = 1.01f
                }
            }
            .then(if (lifted) Modifier.background(cs.surfaceBright) else Modifier)
            .then(if (editing) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The slot where the number sits becomes the playing signal, exactly as in the library's
        // detail page: the same idea in both lists, so the two pages agree about what "current" is.
        // Without covers the slot takes the room the artwork used to hold, so the numbers stay the
        // list's only rhythm instead of leaving a hole on the left.
        Box(Modifier.width(if (showCover) 24.dp else 28.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(8.dp, 13.dp, 6.dp).forEach { barHeight ->
                        Box(
                            Modifier.width(3.dp).height(barHeight).clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.width(if (showCover) 10.dp else 14.dp))
        if (showCover) {
            Cover(
                seed = row.title,
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (current) cs.onSurface else cs.onSurface.copy(alpha = 0.92f),
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
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(row.durationMs),
            style = MaterialTheme.typography.labelSmall.tabular,
            color = cs.onSurfaceVariant,
        )
        // A grab handle, not a button: dragging is the queue's main job and the handle makes it
        // discoverable, but tapping it must not do anything — an earlier version wired this icon to
        // "remove from queue", which meant tapping the handle deleted the song. Removal is left to
        // the swipe/long-press gesture in the interaction round.
        Box(
            Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.DragHandle, "拖动排序",
                tint = cs.onSurfaceVariant.copy(alpha = if (editing) 0.55f else 0.25f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
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
