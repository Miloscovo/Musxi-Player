package tech.musxi.player.ui.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowSwitch
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.player.wallpaperSource
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.rememberHaptics
import kotlin.math.roundToInt

internal const val WallpaperThumbTag = "wallpaper-thumb"

/** What the 外观 row shows; plain values so the row can be drawn without a backend (design sheets). */
internal data class WallpaperRowUi(
    val image: ImageBitmap? = null,
    val frame: WallpaperFrame = WallpaperFrame(),
    val enabled: Boolean = false,
    val busy: Boolean = false,
)

/** The 外观 row for [kind] wired to the shell's host; a placeholder row where no shell provides one. */
@Composable
internal fun WallpaperSettingRow(kind: WallpaperKind = WallpaperKind.PLAYER) {
    val host = LocalWallpaperHosts.current?.get(kind)
    if (host == null) {
        WallpaperRow(kind, WallpaperRowUi(), onToggle = {}, onAdjust = {}, onStrength = {}, onReplace = {}, onRestore = {})
        return
    }
    val state by host.repo.state.collectAsStateWithLifecycle()
    // Read so the "use the other one" item appears as soon as the other kind saves a picture.
    val otherState = host.other?.repo?.state?.collectAsStateWithLifecycle()?.value
    val saved = state.saved
    val image = rememberWallpaperImage(host, saved.asset?.id)
    WallpaperRow(
        kind = kind,
        ui = WallpaperRowUi(
            image = image,
            frame = saved.preferences.frame,
            enabled = saved.asset != null && saved.preferences.enabled,
            busy = !state.loaded || host.working,
        ),
        hasPicture = saved.asset != null,
        onToggle = host::setEnabled,
        onAdjust = host::adjust,
        onStrength = host::adjustStrength,
        onReplace = host::pickNew,
        onRestore = host::restore,
        // Offered only when the other picture differs from this one; the same picture twice is a no-op.
        onUseOther = if (otherState?.saved?.asset != null && otherState.saved.asset?.id != saved.asset?.id) host::useOther else null,
    )
}

/**
 * "界面壁纸" / "播放页壁纸": the picture on the left, a switch on the right (the owner's layout,
 * 11-12). The row toggles; the picture opens what can be done with it, on a tap or a long press,
 * like a cover. Without a picture the row picks one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WallpaperRow(
    kind: WallpaperKind,
    ui: WallpaperRowUi,
    onToggle: (Boolean) -> Unit,
    onAdjust: () -> Unit,
    onStrength: () -> Unit,
    onReplace: () -> Unit,
    onRestore: () -> Unit,
    hasPicture: Boolean = ui.image != null,
    /** Start from the other kind's saved picture; null when there is none to offer. */
    onUseOther: (() -> Unit)? = null,
) {
    val haptic = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    CardRow(
        onClick = {
            if (ui.busy) return@CardRow
            haptic(Haptic.TICK)
            if (hasPicture) onToggle(!ui.enabled) else onReplace()
        },
        modifier = Modifier.semantics { stateDescription = if (ui.enabled) "已开启" else "已关闭" },
        role = Role.Switch,
    ) {
        Box {
            WallpaperThumb(
                ui.image, ui.frame,
                Modifier.combinedClickable(
                    // Without a picture the menu still offers the other kind's one.
                    enabled = (hasPicture || onUseOther != null) && !ui.busy,
                    onClickLabel = "壁纸选项",
                    onClick = { menu = true },
                    onLongClick = { haptic(Haptic.LONG_PRESS); menu = true },
                ).semantics {
                    contentDescription = if (hasPicture) "${kind.title}缩略图" else "未设置${kind.title}"
                    if (hasPicture) onLongClick("壁纸选项") { menu = true; true }
                }.testTag(WallpaperThumbTag),
            )
            AnchoredMenu(expanded = menu, onDismiss = { menu = false }, align = MenuAlign.START, preferAbove = false) {
                MenuHeader(kind.title, kind.scope)
                if (hasPicture) {
                    MenuItem("调整位置", icon = Icons.Outlined.Crop, onClick = { menu = false; onAdjust() })
                    MenuItem("调整强度", icon = Icons.Outlined.Tune, onClick = { menu = false; onStrength() })
                }
                MenuItem(if (hasPicture) "换一张" else "选择图片", icon = Icons.Outlined.Image, onClick = { menu = false; onReplace() })
                onUseOther?.let { use ->
                    MenuItem(kind.useOtherLabel, icon = Icons.Outlined.Wallpaper, onClick = { menu = false; use() })
                }
                if (hasPicture) {
                    MenuItem("恢复默认", icon = Icons.Outlined.HideImage, onClick = { menu = false; onRestore() })
                }
            }
        }
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            kind.title,
            subtitle = when {
                !hasPicture && kind == WallpaperKind.INTERFACE -> "选一张图片作为曲库、搜索和我的页面背景"
                !hasPicture -> "选一张图片作为播放页和歌词页的背景"
                ui.enabled -> "点按图片可调整位置、强度或更换"
                else -> "已保存图片，打开即可使用"
            },
            titleLines = ALL_LINES, subtitleLines = ALL_LINES,
        )
        Spacer(Modifier.width(RowSpec.trailGap))
        RowSwitch(ui.enabled)
    }
}

/** The picture as the player shows it, in a small phone-shaped tile; a mark when there is none. */
@Composable
private fun WallpaperThumb(image: ImageBitmap?, frame: WallpaperFrame, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier.size(width = 26.dp, height = 44.dp).clip(shape).background(cs.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Icon(Icons.Outlined.Wallpaper, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        } else {
            Canvas(Modifier.size(width = 26.dp, height = 44.dp)) {
                val src = wallpaperSource(image.width, image.height, size.width, size.height, frame)
                if (src.isEmpty) return@Canvas
                drawImage(
                    image,
                    srcOffset = IntOffset(src.left.roundToInt(), src.top.roundToInt()),
                    srcSize = IntSize(src.width.roundToInt().coerceAtLeast(1), src.height.roundToInt().coerceAtLeast(1)),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
