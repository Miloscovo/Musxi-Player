package tech.musxi.player.ui.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.musxi.core.settings.LyricsColorMode
import tech.musxi.core.settings.ThemeMode as StoredThemeMode
import tech.musxi.player.MusxiApplication
import tech.musxi.player.lockscreen.LockScreenLyricsContent
import tech.musxi.player.lockscreen.LockScreenLyricsHostActivity
import tech.musxi.player.ui.components.LocalLiveIndicators
import tech.musxi.player.ui.player.LyricsPlayhead
import tech.musxi.player.ui.player.animatedPalette
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.player.progressFraction
import tech.musxi.player.ui.player.rememberCoverColor
import tech.musxi.player.ui.player.translationShown
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.player.ui.wallpaper.rememberSavedWallpaperLook
import java.time.LocalDateTime
import java.util.Date

/**
 * Production lock-screen lyrics host (plan C).
 *
 * Lifecycle, keyguard gating, and display confirmation belong to [LockScreenLyricsHostActivity] and
 * [tech.musxi.player.lockscreen.LockScreenLyricsController]; this subclass only renders
 * [LockScreenPage] from the public [MusxiApplication.lockScreenLyricsContent] stream and wires
 * playback controls and dismissal. It never wakes the screen, keeps the screen on, dismisses the
 * keyguard, auto-starts playback, or opens [tech.musxi.player.MainActivity].
 */
class LockScreenLyricsActivity : LockScreenLyricsHostActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        val app = application as MusxiApplication
        setContent {
            val settings by app.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                StoredThemeMode.SYSTEM -> isSystemInDarkTheme()
                StoredThemeMode.LIGHT -> false
                StoredThemeMode.DARK -> true
            }
            val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            MusxiTheme(accent = Accent.fromName(settings.accent), mode = mode) {
                CompositionLocalProvider(LocalLiveIndicators provides true) {
                    LockScreenLyricsRoute(
                        app = app,
                        dark = dark,
                        onDismiss = ::dismissToSystemLockScreen,
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

@Composable
private fun LockScreenLyricsRoute(
    app: MusxiApplication,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val initialContent = remember(app) {
        LockScreenLyricsContent.from(
            playback = app.playback.state.value,
            lyrics = app.lyrics.state.value,
            sourceWaiting = false,
            wallpaper = app.wallpaper.state.value.saved,
        )
    }
    val slowFlow = remember(app) {
        app.lockScreenLyricsContent.map(::slowLockScreenContentOf).distinctUntilChanged()
    }
    val slowContent by slowFlow.collectAsStateWithLifecycle(
        initialValue = remember(initialContent) { slowLockScreenContentOf(initialContent) },
    )

    // Fast path: position updates feed the playhead and a draw-only revision counter so the 3dp
    // progress line redraws without recomposing the whole lock-screen page twice a second.
    val playhead = remember { LyricsPlayhead() }
    val durationRef = remember { longArrayOf(initialContent.durationMs) }
    val progressRevision = remember { mutableLongStateOf(0L) }
    LaunchedEffect(app) {
        app.lockScreenLyricsContent.collect { content ->
            durationRef[0] = content.durationMs
            val pos = content.position
            playhead.update(
                positionMs = pos.positionMs,
                sampledAtMs = pos.sampledAtElapsedMs,
                speed = pos.speed,
                canAdvance = pos.canExtrapolate,
            )
            progressRevision.longValue++
        }
    }

    val (timeText, dateText) = rememberLiveClock(app)
    val showTranslation = remember(
        slowContent.lyrics.lines,
        slowContent.lyrics.plainText,
        settings.lyricsTranslationPreference,
    ) {
        translationShown(
            settings.lyricsTranslationPreference,
            songOverride = null,
            lines = slowContent.lyrics.lines,
            plainText = slowContent.lyrics.plainText,
        )
    }
    val ui = remember(slowContent, timeText, dateText, showTranslation) {
        lockScreenUiOf(slowContent, time = timeText, date = dateText, showTranslation = showTranslation)
    }
    val coverColor = rememberCoverColor(ui.coverUrl)
    val palette = animatedPalette(playerPalette(ui.coverSeed, dark, coverColor))
    val wallpaperLook = rememberSavedWallpaperLook(app.wallpaper, slowContent.wallpaper)
    val actions = remember(app, onDismiss) {
        LockScreenActions(
            onPlayPause = { app.playback.toggle() },
            onPrevious = { app.playback.previous() },
            onNext = { app.playback.next() },
            onUnlock = onDismiss,
        )
    }

    LockScreenPage(
        ui = ui,
        palette = palette,
        actions = actions,
        progress = {
            progressRevision.longValue
            progressFraction(playhead.positionNow(), durationRef[0])
        },
        playhead = playhead,
        fontSize = settings.lyricsFontSize,
        accentCurrent = settings.lyricsColorMode == LyricsColorMode.COVER,
        wallpaper = wallpaperLook,
        dark = dark,
    )
}

@Composable
private fun rememberLiveClock(context: Context): Pair<String, String> {
    fun nowFormatted(): Pair<String, String> {
        val time = DateFormat.getTimeFormat(context).format(Date())
        val date = lockScreenDate(LocalDateTime.now())
        return time to date
    }

    var formatted by remember { mutableStateOf(nowFormatted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                formatted = nowFormatted()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) formatted = nowFormatted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return formatted
}
