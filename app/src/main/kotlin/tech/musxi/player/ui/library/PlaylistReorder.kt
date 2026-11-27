package tech.musxi.player.ui.library

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.musxi.core.library.MediaKey
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.rememberHaptics
import kotlin.math.roundToInt

internal fun playlistDropAnchor(from: Int, offset: Float, rowHeight: Float, keys: List<MediaKey>): MediaKey? {
    if (from !in keys.indices || rowHeight <= 0f) return null
    val target = (from + (offset / rowHeight).roundToInt()).coerceIn(0, keys.lastIndex)
    return when {
        target == from -> keys[from]
        target > from -> keys.getOrNull(target + 1)
        else -> keys[target]
    }
}

/** Shared gesture state; each playlist remains an independent lazy item. */
internal class PlaylistReorderState {
    var dragging by mutableStateOf<MediaKey?>(null)
    var revision by mutableLongStateOf(0L)
    var offset by mutableFloatStateOf(0f)
    var crossed by mutableStateOf(0)
    fun cancel() { dragging = null; offset = 0f }
}

@Composable
internal fun rememberPlaylistReorderState(revision: Long, saving: Boolean, enabled: Boolean): PlaylistReorderState {
    val state = remember { PlaylistReorderState() }
    LaunchedEffect(revision, saving, enabled) {
        if (state.dragging != null && (revision != state.revision || saving || !enabled)) state.cancel()
    }
    return state
}

@Composable
internal fun PlaylistReorderRow(
    item: PlaylistRowItem,
    index: Int,
    items: List<PlaylistRowItem>,
    revision: Long,
    saving: Boolean,
    listState: LazyListState,
    state: PlaylistReorderState,
    row: @Composable BoxScope.(PlaylistRowItem, Boolean, Float) -> Unit,
    onMove: (MediaKey, MediaKey?, Long) -> Unit,
    enabled: Boolean,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val liveItems = rememberUpdatedState(items)
    val liveRevision = rememberUpdatedState(revision)
    val liveSaving = rememberUpdatedState(saving)
    val liveMove = rememberUpdatedState(onMove)
    val rowHeight = with(LocalDensity.current) {
        maxOf(64.dp, MaterialTheme.typography.titleSmall.lineHeight.toDp() +
            MaterialTheme.typography.bodySmall.lineHeight.toDp() + 16.dp)
    }
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    val lifted = item.playlist.key == state.dragging
    val target = state.dragging?.let { key -> liveItems.value.indexOfFirst { item -> item.playlist.key == key } } ?: -1
    val steps = (state.offset / rowHeightPx).roundToInt()
    val shift = if (target < 0 || lifted) 0f else when {
        steps > 0 && index in target + 1..target + steps -> -rowHeightPx
        steps < 0 && index in target + steps until target -> rowHeightPx
        else -> 0f
    }
    Box(Modifier.fillMaxWidth().height(rowHeight).offset { IntOffset(0, shift.roundToInt()) }.zIndex(if (lifted) 1f else 0f)) {
        row(item, lifted, if (lifted) state.offset else 0f)
        Box(Modifier.align(Alignment.CenterEnd).padding(end = Space.page + 44.dp).size(44.dp).pointerInput(item.playlist.key, index, enabled) {
            var active = false
            detectVerticalDragGestures(
                onDragStart = {
                    active = enabled && !liveSaving.value
                    if (active) {
                        state.dragging = item.playlist.key
                        state.revision = liveRevision.value
                        state.offset = 0f
                        state.crossed = 0
                        haptics(Haptic.LONG_PRESS)
                    }
                },
                onVerticalDrag = { change, amount ->
                    if (!active || state.dragging != item.playlist.key || liveRevision.value != state.revision || liveSaving.value) return@detectVerticalDragGestures
                    change.consume()
                    state.offset += amount
                    val keys = liveItems.value.map { it.playlist.key }
                    val targetIndex = (index + (state.offset / rowHeightPx).roundToInt()).coerceIn(0, keys.lastIndex)
                    if (targetIndex - index != state.crossed) {
                        state.crossed = targetIndex - index
                        haptics(Haptic.TICK)
                    }
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val viewport = listState.layoutInfo.viewportEndOffset.toFloat()
                    val finger = (visible.firstOrNull { it.key == "playlist-${item.playlist.key.stableId}" }?.offset ?: 0) + change.position.y
                    val edge = rowHeightPx
                    val delta = when {
                        finger < edge -> -(edge - finger) / 4f
                        finger > viewport - edge -> (finger - viewport + edge) / 4f
                        else -> 0f
                    }
                    if (delta != 0f) scope.launch {
                        listState.scroll {
                            val moved = scrollBy(delta)
                            if (state.dragging == item.playlist.key) state.offset += moved
                        }
                    }
                },
                onDragEnd = {
                    val before = playlistDropAnchor(index, state.offset, rowHeightPx, liveItems.value.map { it.playlist.key })
                    if (active && state.dragging == item.playlist.key && liveRevision.value == state.revision && !liveSaving.value) {
                        if (before != item.playlist.key && (before != null || liveItems.value.last().playlist.key != item.playlist.key)) {
                            liveMove.value(item.playlist.key, before, state.revision)
                        }
                    }
                    active = false
                    state.dragging = null
                    state.offset = 0f
                },
                onDragCancel = { active = false; state.cancel() },
            )
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DragHandle, "拖动排序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (saving || !enabled) 0.25f else 0.65f))
        }
    }
}

internal data class PlaylistRowItem(val playlist: tech.musxi.core.library.PlaylistSummary)
