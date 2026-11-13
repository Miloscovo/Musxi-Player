package tech.musxi.player.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem

/**
 * What a long press on the playing cover offers, for a local recording only: pick another picture,
 * or (when one was picked) go back to the file's own cover. Null on platform songs, whose cover
 * belongs to the platform.
 */
class CoverActions(
    /** The cover is a picture the user picked, so it can be restored. */
    val custom: Boolean,
    val onChange: () -> Unit,
    val onRestore: () -> Unit,
)

/** The cover's long-press menu; anchor it in a Box around the cover. */
@Composable
internal fun CoverActionsMenu(
    expanded: Boolean,
    actions: CoverActions,
    onDismiss: () -> Unit,
    align: MenuAlign = MenuAlign.START,
    preferAbove: Boolean = false,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, align = align, preferAbove = preferAbove) {
        MenuHeader("封面", if (actions.custom) "这一条用的是你选的图片" else "这一条用的是文件自带的封面")
        MenuItem("更换封面", icon = Icons.Outlined.Image, onClick = { onDismiss(); actions.onChange() })
        if (actions.custom) {
            MenuItem("恢复默认封面", icon = Icons.Outlined.HideImage, onClick = { onDismiss(); actions.onRestore() })
        }
    }
}
