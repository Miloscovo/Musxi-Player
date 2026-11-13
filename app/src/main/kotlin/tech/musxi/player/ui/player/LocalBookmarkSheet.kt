package tech.musxi.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.musxi.core.localaudio.LocalAudioBookmark
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.tabular

/**
 * The bookmarks of the recording that is playing, in the app's sheet. A tap jumps there and closes
 * the sheet; a long press renames; a swipe left deletes. Adding happens on the player's bookmark button, where the
 * listener already is, so the sheet has no "add" of its own.
 */
@Composable
fun LocalBookmarkSheet(
    visible: Boolean,
    bookmarks: List<LocalAudioBookmark>,
    onDismiss: () -> Unit,
    onJump: (LocalAudioBookmark) -> Unit,
    onDelete: (LocalAudioBookmark) -> Unit,
    /** Saves a new name; the callback reports whether it was saved, so the dialog can stay open. */
    onRename: (LocalAudioBookmark, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
) {
    var renaming by remember { androidx.compose.runtime.mutableStateOf<LocalAudioBookmark?>(null) }
    var saving by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!visible && renaming != null) renaming = null
    // Kept so the sheet slides out showing what it showed.
    val last = remember { arrayOfNulls<List<LocalAudioBookmark>>(1) }
    if (visible) last[0] = bookmarks
    val shown = if (visible) bookmarks else last[0].orEmpty()
    MusxiSheet(visible = visible, onDismiss = onDismiss) {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 4.dp, bottom = 8.dp)) {
            Text("书签", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                if (shown.isEmpty()) "在播放页点书签按钮，记下当前位置" else "${shown.size} 个 · 点按跳转，长按改名，左滑删除",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.id }) { bookmark ->
                BookmarkRow(bookmark, onJump = { onJump(bookmark) }, onDelete = { onDelete(bookmark) },
                    onRename = { renaming = bookmark })
            }
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp))
    }
    renaming?.let { target ->
        tech.musxi.player.ui.localaudio.LocalNameDialog(
            title = "书签名称", initial = target.name, confirmLabel = "保存", saving = saving,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                if (name == target.name) { renaming = null; return@LocalNameDialog }
                saving = true
                onRename(target, name) { ok -> saving = false; if (ok) renaming = null }
            },
        )
    }
}

@Composable
private fun BookmarkRow(bookmark: LocalAudioBookmark, onJump: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val swipe = remember(bookmark.id) { Animatable(0f) }
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val reveal = (-swipe.value / size.width).coerceIn(0f, 1f)
                if (reveal > 0f) drawRect(cs.errorContainer.copy(alpha = (reveal * 3f).coerceAtMost(1f)))
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (swipe.value < 0f) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = cs.onErrorContainer, modifier = Modifier.padding(end = Space.page).size(22.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .graphicsLayer { translationX = swipe.value }
                .background(if (swipe.value != 0f) cs.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
                .pointerInput(bookmark.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (-swipe.value > size.width / 3f) {
                                    swipe.animateTo(-size.width.toFloat(), Motion.release())
                                    onDelete()
                                } else {
                                    swipe.animateTo(0f, Motion.release())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f, Motion.release()) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { swipe.snapTo((swipe.value + amount).coerceAtMost(0f)) }
                    }
                }
                .combinedClickable(onClick = onJump, onLongClick = onRename, onLongClickLabel = "改名")
                .padding(horizontal = Space.page),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Bookmark, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                bookmark.name,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(bookmark.positionMs),
                style = MaterialTheme.typography.labelLarge.tabular,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
