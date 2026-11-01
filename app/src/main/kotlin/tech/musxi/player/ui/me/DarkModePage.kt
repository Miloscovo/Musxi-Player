package tech.musxi.player.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.ui.theme.Space

/**
 * Single-choice sub-page for the dark mode: three rows, a check on the chosen one. It is the model
 * for the other pickers (quality, font size, accent): back bar, title, one card of [MeChoiceRow]s,
 * an optional footer. Choosing does not close the page; back does.
 */
@Composable
fun DarkModePage(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Room for the floating dock, so the last row can scroll above it. */
    bottomInset: Dp = 0.dp,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(horizontal = Space.page)) {
        // 56dp bar with the 48dp back target, the same as the playlist page.
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = cs.onSurface, modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("深色模式", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Spacer(Modifier.height(Space.section))
            MeGroup(title = null, footer = "“跟随系统”会随手机的深色模式自动切换。") {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) MeDivider()
                    MeChoiceRow(themeModeLabel(mode), selected = mode == selected) { onSelect(mode) }
                }
            }
            Spacer(Modifier.height(Space.section + bottomInset))
        }
    }
}
