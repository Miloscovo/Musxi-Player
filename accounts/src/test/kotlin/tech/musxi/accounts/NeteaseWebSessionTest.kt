package tech.musxi.accounts

import android.app.Activity
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.ValueCallback
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import tech.musxi.neteaseloginpoc.WebLoginSession
import tech.musxi.neteaseloginpoc.WebLoginWindow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NeteaseWebSessionTest {
    private val windows = mutableListOf<WebLoginWindow>()
    private val app get() = RuntimeEnvironment.getApplication()
    private val cookieUrl = "https://music.163.com/"
    private class Listener : WebLoginWindow.Listener {
        var cancellations = 0
        var submissions = 0
        override fun ready(firstParty: Boolean, thirdParty: Boolean) {}
        override fun cookies(header: String) { submissions++ }
        override fun cancelled() { cancellations++ }
    }
    @Implements(CookieManager::class)
    class DelayedCookieShadow {
        companion object {
            var completion: ValueCallback<Boolean>? = null
            var cookies = true
            var removals = 0
            @JvmStatic @Implementation fun getInstance(): CookieManager = manager
            private val manager = object : android.webkit.RoboCookieManager() {
                override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
                    completion = callback; removals++
                }
                override fun hasCookies() = cookies
                override fun flush() {}
            }
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun seedPreparedProfile() {
        app.getSharedPreferences("netease-browser-lifecycle-v1", 0).edit().putBoolean("pending", false).commit()
        CookieManager.getInstance().setCookie(cookieUrl, "MUSIC_U=fixture; Path=/; Secure")
        idle()
    }
    private fun open(listener: Listener = Listener(), current: () -> Boolean = { true }): WebLoginWindow {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return WebLoginWindow(activity, listener, current).also { windows += it; idle() }
    }
    @After fun cleanup() { windows.forEach { it.close() }; idle() }

    @Test fun modernCloseAndReopenRetainWebCookiesAndDoNotSubmitCredentials() {
        seedPreparedProfile()
        val listener = Listener()
        open(listener).close(); idle()
        assertEquals(1, listener.cancellations)
        assertEquals(0, listener.submissions)
        assertTrue(CookieManager.getInstance().getCookie(cookieUrl).contains("MUSIC_U=fixture"))
        open().close(); idle()
        assertTrue(CookieManager.getInstance().getCookie(cookieUrl).contains("MUSIC_U=fixture"))
    }

    @Test fun explicitLogoutDestroysOpenBrowserAndClearsCookiesBeforeCompletion() {
        seedPreparedProfile()
        val listener = Listener()
        open(listener)
        var completed: Boolean? = null
        WebLoginSession.clear(app) { completed = it }; idle()
        assertEquals(true, completed)
        assertEquals(1, listener.cancellations)
        assertNull(CookieManager.getInstance().getCookie(cookieUrl))
        assertFalse(app.getSharedPreferences("netease-browser-lifecycle-v1", 0).getBoolean("pending", true))
    }

    @Test fun durablePendingPurgeClearsPriorCookiesBeforeNewPageIsLoaded() {
        seedPreparedProfile()
        WebLoginSession.markForClear(app)
        val window = open()
        assertFalse(CookieManager.getInstance().getCookie(cookieUrl).orEmpty().contains("MUSIC_U=fixture"))
        val container = WebLoginWindow::class.java.getDeclaredField("container").apply { isAccessible = true }
            .get(window) as LinearLayout
        val web = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<WebView>().single()
        assertEquals("https://y.music.163.com/m/login", shadowOf(web).lastLoadedUrl)
    }

    @Test fun staleAccountAttemptNeverLoadsLoginPage() {
        seedPreparedProfile()
        val listener = Listener()
        open(listener) { false }
        assertEquals(1, listener.cancellations)
        assertEquals(0, listener.submissions)
    }

    @Test @Config(sdk = [27]) fun legacyClosePurgesCookiesAndNextOpenWaitsForIt() {
        seedPreparedProfile()
        val window = open()
        CookieManager.getInstance().setCookie(cookieUrl, "MUSIC_U=legacy; Path=/; Secure"); idle()
        window.close(); idle()
        assertNull(CookieManager.getInstance().getCookie(cookieUrl))
        open().close(); idle()
        assertNull(CookieManager.getInstance().getCookie(cookieUrl))
    }

    @Test @Config(shadows = [DelayedCookieShadow::class])
    fun timedOutCleanupKeepsNewOpensBlockedUntilPhysicalDeletionCompletes() {
        DelayedCookieShadow.removals = 0
        DelayedCookieShadow.cookies = true
        var logout: Boolean? = null
        var prepared: Boolean? = null
        WebLoginSession.clear(app) { logout = it }; idle()
        shadowOf(Looper.getMainLooper()).idleFor(15, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(false, logout)
        WebLoginSession.prepare(app) { prepared = it }; idle()
        assertNull(prepared)
        assertEquals(1, DelayedCookieShadow.removals)
        DelayedCookieShadow.cookies = false
        requireNotNull(DelayedCookieShadow.completion).onReceiveValue(true); idle()
        assertEquals(true, prepared)
    }

    @Test @Config(shadows = [DelayedCookieShadow::class])
    fun lateDeletionCannotEraseANewerDurableLogoutIntent() {
        DelayedCookieShadow.cookies = true
        var completed: Boolean? = null
        WebLoginSession.clear(app) { completed = it }; idle()
        WebLoginSession.markForClear(app)
        DelayedCookieShadow.cookies = false
        requireNotNull(DelayedCookieShadow.completion).onReceiveValue(true); idle()
        assertEquals(false, completed)
        assertTrue(app.getSharedPreferences("netease-browser-lifecycle-v1", 0).getBoolean("pending", false))
    }

    @Test @Config(shadows = [DelayedCookieShadow::class])
    fun leftoverCookiesCannotCompleteExplicitLogout() {
        DelayedCookieShadow.cookies = true
        var completed: Boolean? = null
        WebLoginSession.clear(app) { completed = it }; idle()
        requireNotNull(DelayedCookieShadow.completion).onReceiveValue(false); idle()
        assertEquals(false, completed)
        assertTrue(app.getSharedPreferences("netease-browser-lifecycle-v1", 0).getBoolean("pending", false))
    }

    @Test @Config(shadows = [DelayedCookieShadow::class])
    fun emptyJarIsASuccessfulCleanupEvenWhenNothingWasRemoved() {
        DelayedCookieShadow.cookies = false
        var completed: Boolean? = null
        WebLoginSession.clear(app) { completed = it }; idle()
        requireNotNull(DelayedCookieShadow.completion).onReceiveValue(false); idle()
        assertEquals(true, completed)
        assertFalse(app.getSharedPreferences("netease-browser-lifecycle-v1", 0).getBoolean("pending", true))
    }
}
