package tech.musxi.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ProcessLifecycleOwner
import tech.musxi.player.ui.MainShell
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Real front-end entry point. `DeveloperActivity` stays in the debug source set for fixed-step
 * validation only; it must not be presented as the product UI.
 */
class MainActivity : ComponentActivity() {

    private var accent by mutableStateOf(Accent.MIST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MusxiApplication
        // One owner for the foreground flag. Screens must not each set it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = app.accounts.setForeground(true)
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = app.accounts.setForeground(false)
            },
        )
        setContent {
            val settings by app.settings.collectAsState()
            val dark = when (settings.themeMode) {
                tech.musxi.core.settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                tech.musxi.core.settings.ThemeMode.LIGHT -> false
                tech.musxi.core.settings.ThemeMode.DARK -> true
            }
            val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            MusxiTheme(accent = accent, mode = mode) {
                MainShell(app, dark = dark)
            }
        }
    }
}
