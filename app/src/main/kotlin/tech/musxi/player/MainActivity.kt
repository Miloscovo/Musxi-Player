package tech.musxi.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import tech.musxi.player.ui.MainShell
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Real front-end entry point. `DeveloperActivity` stays in the debug source set for fixed-step
 * validation only; it must not be presented as the product UI.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MusxiApplication
        setContent {
            val settings by app.settings.collectAsState()
            val dark = when (settings.themeMode) {
                tech.musxi.core.settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                tech.musxi.core.settings.ThemeMode.LIGHT -> false
                tech.musxi.core.settings.ThemeMode.DARK -> true
            }
            val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            // Saved by name; a name this build does not know falls back to the default.
            MusxiTheme(accent = Accent.fromName(settings.accent), mode = mode) {
                // On a phone, spinners turn; tests and design sheets leave them still (see ProgressArc).
                androidx.compose.runtime.CompositionLocalProvider(
                    tech.musxi.player.ui.components.LocalLiveIndicators provides true,
                ) {
                    MainShell(app, dark = dark)
                }
            }
        }
    }
}
