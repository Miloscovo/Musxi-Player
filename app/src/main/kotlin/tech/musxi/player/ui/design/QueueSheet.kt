package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.QueuePanel
import tech.musxi.player.ui.player.QueueRowUi
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Queue sheet: the panel over the player page, with the same scrim the source panel uses.
 *
 * The panel is real ([QueuePanel]) and the page behind it is the real player, so the sheet shows
 * what the user will actually see when the sheet is open — including how much of the page the panel
 * covers.
 */
@Composable
fun QueueSheet(
    accent: Accent,
    mode: ThemeMode,
    ui: QueueUi,
    player: PlayerUi = playerNormal(),
    /** The two candidate readings: with artwork per row, or plain numbered text. */
    showCover: Boolean = true,
) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                FullPlayer(ui = player, dark = mode != ThemeMode.LIGHT)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    QueuePanel(ui, showCover = showCover)
                }
            }
        }
    }
}

/** Twelve tracks, current one in the middle: the queue is usually opened to look around. */
fun queueSample(current: Int = 4) = QueueUi(
    items = listOf(
        row("晴天", "周杰伦", 4, 29),
        row("夜的第七章", "周杰伦", 4, 35),
        row("枫", "周杰伦", 4, 37),
        row("稻香", "周杰伦", 3, 43),
        row("告白气球", "周杰伦", 3, 35),
        row("七里香", "周杰伦", 4, 58),
        row("青花瓷", "周杰伦", 3, 59),
        row("千里之外", "周杰伦 · 费玉清", 4, 15),
        row("不能说的秘密", "周杰伦", 4, 56),
        row("以父之名", "周杰伦", 5, 42),
    ),
    currentIndex = current,
)

/** A queue being edited: every control dimmed while a command is in flight. */
fun queueEditing() = queueSample().copy(editing = false)

/** An empty queue: the panel still opens, and says so instead of showing nothing. */
fun queueEmpty() = QueueUi(items = emptyList(), currentIndex = -1)

private fun row(title: String, artist: String, minutes: Int, seconds: Int) = QueueRowUi(
    key = "q-$title",
    title = title,
    artist = artist,
    durationMs = (minutes * 60 + seconds) * 1000L,
)
