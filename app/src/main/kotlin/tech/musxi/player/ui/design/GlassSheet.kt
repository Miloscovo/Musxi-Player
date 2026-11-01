package tech.musxi.player.ui.design

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.components.GlassStyle
import tech.musxi.player.ui.components.LocalGlass
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.glass
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/** Which floating layer the glass sample shows over the page. */
enum class GlassSample { CARD, MENU, SHEET }

/** Test handles for the bottom card and the floating menu. */
const val GlassCardTag = "glass-card"
const val GlassMenuTag = "glass-menu"

/**
 * Glass sample: a colourful page with the three glass surfaces of the app, one sample at a time. CARD
 * is the bottom floating card (regular), MENU adds the floating menu above it (thin), SHEET is the
 * sheet resting at its middle detent over the page (regular). A sheet covers the card, so the card is
 * left out there.
 *
 * [glass] false draws the solid path that Robolectric can render. True lets [GlassHost] pick the
 * renderer for the API level (refraction on Android 13+); Robolectric may not draw Backdrop's lens
 * shader, so that picture shows what its graphics layer manages, not necessarily the bent rim.
 */
@Composable
fun GlassSheet(accent: Accent, mode: ThemeMode, sample: GlassSample, glass: Boolean = false) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        CompositionLocalProvider(LocalContentColor provides cs.onBackground) {
            GlassHost(
                enabled = glass,
                overlay = {
                    if (sample != GlassSample.SHEET) {
                        GlassCard(Modifier.align(Alignment.BottomCenter))
                    }
                    if (sample == GlassSample.MENU) {
                        GlassMenu(Modifier.align(Alignment.BottomEnd).padding(end = Space.page, bottom = 92.dp))
                    }
                    MusxiSheet(visible = sample == GlassSample.SHEET, onDismiss = {}) { SheetBody() }
                },
            ) { GlassPage() }
        }
    }
}

private val Covers = listOf(
    Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF30A46C), Color(0xFF3E63DD),
    Color(0xFF8E4EC6), Color(0xFFE93D82), Color(0xFF12A594), Color(0xFFF76B15),
)

private val PageRows = listOf(
    "晴天" to "周杰伦", "夜曲" to "周杰伦", "平凡之路" to "朴树", "海阔天空" to "Beyond",
    "后来" to "刘若英", "光年之外" to "G.E.M.邓紫棋", "起风了" to "买辣椒也用券", "稻香" to "周杰伦",
    "红豆" to "王菲", "十年" to "陈奕迅", "小幸运" to "田馥甄",
)

/** Strong colours and text edge to edge, so what the glass does to the page behind it is easy to see. */
@Composable
private fun GlassPage() {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page).padding(top = 24.dp)) {
        Text("曲库", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        PageRows.forEachIndexed { i, (title, artist) ->
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Covers[i % Covers.size]))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                // A coloured tag at the right edge, where the floating menu sits, so it has something to blur.
                Box(
                    Modifier.size(width = 56.dp, height = 24.dp).clip(RoundedCornerShape(12.dp))
                        .background(Covers[i % Covers.size].copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Text("无损", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            }
        }
    }
}

/** The mini player's size and shape: 64dp tall, 28dp corners, 20dp from the sides, regular glass. */
@Composable
private fun GlassCard(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = Space.page, vertical = 16.dp)
            .fillMaxWidth()
            .height(64.dp)
            .testTag(GlassCardTag)
            .glass(LocalGlass.current, RoundedCornerShape(28.dp), GlassStyle.REGULAR)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Covers[0]))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("晴天", style = MaterialTheme.typography.titleSmall, maxLines = 1, color = cs.onSurface)
            Text("周杰伦", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = cs.onSurfaceVariant)
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", Modifier.size(28.dp), tint = cs.onSurface)
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", Modifier.size(28.dp), tint = cs.onSurface)
    }
}

/** A floating menu: 220dp wide, 18dp corners, 44dp rows, thin glass. */
@Composable
private fun GlassMenu(modifier: Modifier) {
    Column(
        modifier
            .width(220.dp)
            .testTag(GlassMenuTag)
            .glass(LocalGlass.current, RoundedCornerShape(18.dp), GlassStyle.THIN)
            .padding(vertical = 6.dp),
    ) {
        MenuRow(Icons.Outlined.SkipNext, "下一首播放")
        MenuRow(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单")
        MenuRow(Icons.Outlined.FavoriteBorder, "收藏")
        MenuRow(Icons.Outlined.Album, "查看专辑")
        MenuRow(Icons.Outlined.Share, "分享")
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink)
    }
}

@Composable
private fun SheetBody() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
        Text("切换音源", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(8.dp))
        listOf("网易云音乐" to "270 秒 · 与原曲时长一致", "QQ 音乐" to "261 秒 · 短 8 秒", "酷狗音乐" to "需要会员").forEach { (name, note) ->
            Column(Modifier.fillMaxWidth().height(56.dp), verticalArrangement = Arrangement.Center) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
