package tech.musxi.player

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import tech.musxi.accounts.NeteaseWebLogin
import tech.musxi.accounts.NeteaseWebLoginSession
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.account.NeteaseWebLoginSheetHost
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** Opt-in, real official WebView in the product Sheet. Never submits or clears an account.
 * Uses in-process semantic/view actions so a game floating above Musxi receives no input.
 * The harness replaces this Activity's composition; it does not exercise the account-page entry.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
class NeteaseWebSheetDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun officialPageCloseReopenReloadBackAndSecureRestoration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowNeteaseWebSheet") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val activity = phone.activity
        val app = activity.application as MusxiApplication
        val report = JSONObject().put("status", "running")
            .put("harness", "production Sheet and real official WebView; account entry bypassed")
            .put("authenticationSubmitted", false)
        val cases = JSONArray()
        report.put("cases", cases)
        val output = File(app.filesDir, "netease-web-sheet-device.json")
        fun record(name: String, result: String) {
            cases.put(JSONObject().put("case", name).put("result", result))
            output.writeText(report.toString(2))
        }
        fun secure(): Boolean {
            var value = false
            phone.runOnIdle { value = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
            return value
        }
        fun webViews(): List<WebView> {
            val result = mutableListOf<WebView>()
            fun visit(view: View) {
                if (view is WebView) result += view
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                visit(activity.window.decorView)
            } else phone.runOnIdle { visit(activity.window.decorView) }
            return result
        }
        var session by mutableStateOf<NeteaseWebLoginSession?>(null)
        fun close() { session?.close(); session = null }
        fun open() {
            phone.runOnIdle {
                session = requireNotNull(NeteaseWebLogin.startSession(app.accounts, reauthenticate = true,
                    closed = { session = null }))
            }
            compose.waitUntil(20_000) { secure() && webViews().isNotEmpty() }
            compose.onNodeWithTag("netease-web-close").assertIsDisplayed()
        }
        fun loaded() {
            compose.waitUntil(60_000) {
                var ready = false
                phone.runOnIdle { ready = webViews().any { it.progress == 100 && android.net.Uri.parse(it.url).host == "y.music.163.com" } }
                ready
            }
        }
        fun pageNodes(): List<AccessibilityNodeInfo> {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo, inWeb: Boolean) {
                val web = inWeb || node.className?.toString() == "android.webkit.WebView"
                if (web) result += node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, web) }
            }
            automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == activity.packageName }
                .forEach { visit(it, false) }
            return result
        }
        fun imeVisible(): Boolean {
            var visible = false
            phone.runOnIdle { visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return visible
        }
        fun touchOfficialNode(node: AccessibilityNodeInfo) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty)
            phone.runOnIdle {
                val origin = IntArray(2)
                activity.window.decorView.getLocationOnScreen(origin)
                val down = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1], 0)
                    try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
        }
        fun label(node: AccessibilityNodeInfo): String? = node.text?.toString() ?: node.contentDescription?.toString()
        fun visibleInput(node: AccessibilityNodeInfo): Boolean {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            return (node.isEditable || node.className?.toString() == "android.widget.EditText") &&
                !node.isPassword && node.isVisibleToUser && !bounds.isEmpty
        }
        compose.waitUntil(60_000) { app.accounts.states.value.values.all { it.phase == AccountPhase.CONNECTED } }
        val owners = Platform.entries.associateWith { app.accounts.states.value.getValue(it).profile?.id }
        val queue = app.playback.queueState.value
        val settings = app.settings.value
        val initialSecure = secure()
        try {
            phone.runOnIdle {
                activity.setContent {
                    MusxiTheme(accent = Accent.fromName(settings.accent), mode = ThemeMode.DARK) {
                        GlassHost(enabled = settings.transparencyEnabled,
                            overlay = { NeteaseWebLoginSheetHost(session, activity, true, ::close) },
                            content = { Box(Modifier.fillMaxSize()) })
                    }
                }
            }
            open()
            loaded()
            val first = webViews().single()
            record("official_page_load_and_secure", "passed")
            // Read accessibility metadata only; never retain field values, account labels or HTML.
            compose.waitUntil(15_000) { pageNodes().size > 1 }
            val loginChoices = setOf("手机号登录", "其他登录方式", "其它登录方式", "其他方式登录", "手机登录", "手机号码登录", "手机号验证码登录", "使用其他账号登录")
            val safeLabels = loginChoices + setOf("登录", "注册", "服务条款", "用户协议", "隐私政策", "《服务条款》", "《隐私政策》", "打开APP", "打开网易云音乐", "立即登录", "一键登录", "立即体验", "下一步", "获取验证码", "同意并登录", "密码登录", "验证码登录", "同意", "不同意", "取消", "继续", "同意并继续", "同意并开启服务", "暂不同意")
            fun safePageLabels(): JSONArray = JSONArray().also { labels ->
                pageNodes().forEach { node ->
                    val text = node.text?.toString() ?: node.contentDescription?.toString()
                    if (text in safeLabels) labels.put(JSONObject().put("label", text).put("clickable", node.isClickable))
                }
            }
            compose.waitUntil(20_000) { pageNodes().any { it.text?.toString() in loginChoices } }
            report.put("safeOfficialLabelsBefore", safePageLabels())
            val terms = pageNodes().firstOrNull { label(it) in setOf("服务条款", "用户协议", "隐私政策", "《服务条款》", "《隐私政策》") && it.isClickable }
            if (terms != null) {
                touchOfficialNode(terms)
                compose.waitUntil(20_000) {
                    var history = false
                    phone.runOnIdle { history = webViews().size > 1 || webViews().any { it.canGoBack() } }
                    history
                }
                compose.waitUntil(30_000) {
                    var finished = false
                    phone.runOnIdle { finished = webViews().lastOrNull()?.progress == 100 }
                    finished
                }
                compose.waitForIdle()
                report.put("termsWebViewCountBeforeBack", webViews().size)
                assertNotNull(session)
                phone.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
                report.put("termsSessionPresentAfterBack", session != null)
                output.writeText(report.toString(2))
                compose.waitUntil(20_000) { webViews().size == 1 && pageNodes().any { label(it) in loginChoices } }
                assertNotNull(session)
                record("official_terms_and_web_back_priority", "passed")
            } else record("terms_navigation", "pending_no_accessible_terms_link")
            // Give the next independent check a fresh entry page, without popup/history leftovers.
            compose.onNodeWithTag("netease-web-reload").performClick()
            loaded()
            compose.waitUntil(20_000) { pageNodes().any { label(it) in loginChoices && it.isClickable } }
            val choice = pageNodes().firstOrNull { label(it) in loginChoices && it.isClickable }
            if (choice != null) {
                touchOfficialNode(choice)
                report.put("safePhoneEntryLabels", safePageLabels())
                record("phone_input_waiting_for_official_page", "awaiting_user_if_official_consent_is_shown")
                compose.waitUntil(120_000) { pageNodes().any(::visibleInput) }
                val fields = JSONArray()
                pageNodes().filter { it.isEditable || it.className?.toString() == "android.widget.EditText" }.forEach {
                    val bounds = android.graphics.Rect()
                    it.getBoundsInScreen(bounds)
                    fields.put(JSONObject().put("class", it.className).put("bounds", bounds.toShortString())
                        .put("visible", it.isVisibleToUser).put("password", it.isPassword).put("editable", it.isEditable))
                }
                report.put("fieldGeometry", fields)
                record("official_phone_login_choice", "passed")
            }
            val nodes = pageNodes()
            report.put("safeOfficialLabelsAfter", safePageLabels())
            report.put("officialPageNodeCount", nodes.size)
            val input = nodes.firstOrNull(::visibleInput)
            report.put("nonPasswordEditableFieldPresent", input != null)
            if (input != null) {
                touchOfficialNode(input)
                compose.waitUntil(10_000) { imeVisible() }
                compose.onNodeWithTag("netease-web-submit").assertIsDisplayed()
                val button = compose.onNodeWithTag("netease-web-submit").fetchSemanticsNode().boundsInWindow
                var imeTop = 0
                phone.runOnIdle {
                    val decor = activity.window.decorView
                    imeTop = decor.height - (ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                }
                assertTrue("Submit overlaps IME", button.bottom <= imeTop + 2)
                record("input_focus_keyboard_and_submit_above_ime", "passed")
                phone.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime()) }
                compose.waitUntil(10_000) { !imeVisible() }
            } else record("keyboard", "pending_no_accessible_non_password_input")
            compose.onNodeWithTag("netease-web-submit").assertIsDisplayed()
            compose.onNodeWithTag("netease-web-reload").performClick()
            loaded()
            record("reload_official_entry", "passed")
            compose.onNodeWithTag("netease-web-close").performClick()
            report.put("closeSessionNull", session == null).put("closeInitialSecure", initialSecure)
                .put("closeSecureImmediately", secure())
            output.writeText(report.toString(2))
            compose.waitUntil(10_000) { session == null && secure() == initialSecure }
            assertTrue(webViews().isEmpty())
            record("close_destroys_webview_restores_secure", "passed")
            open()
            loaded()
            assertNotSame(first, webViews().single())
            record("reopen_new_webview_real_page", "passed")
            phone.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { session == null && secure() == initialSecure }
            record("back_at_entry_closes_sheet", "passed")
            open()
            loaded()
            compose.onNodeWithTag("musxi-sheet-drag").performTouchInput {
                swipe(Offset(centerX, 8f), Offset(centerX, height * 12f), durationMillis = 450)
            }
            compose.waitUntil(10_000) { session == null && secure() == initialSecure }
            record("sheet_drag_dismiss", "passed")
            assertTrue("Account identity changed", owners == Platform.entries.associateWith { app.accounts.states.value.getValue(it).profile?.id })
            assertTrue("Playback queue changed", queue == app.playback.queueState.value)
            assertEquals(settings, app.settings.value)
            assertTrue(app.accounts.states.value.values.all { it.phase == AccountPhase.CONNECTED })
            record("accounts_queue_settings_preserved", "passed")
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("errorType", failure.javaClass.simpleName)
            val geometry = JSONArray()
            pageNodes().forEach { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                geometry.put(JSONObject().put("class", node.className).put("bounds", bounds.toShortString())
                    .put("visible", node.isVisibleToUser).put("editable", node.isEditable)
                    .put("clickable", node.isClickable))
            }
            report.put("failureGeometry", geometry)
            throw failure
        } finally {
            phone.runOnIdle { close() }
            // Let the Sheet finish disposal before recreation; otherwise Android copies the
            // temporary secure flag into the replacement Activity's window attributes.
            try { compose.waitUntil(10_000) { webViews().isEmpty() && !secure() } } catch (_: Exception) { }
            output.writeText(report.toString(2))
            // Recreate the real product composition, preserving application data and game state.
            phone.runOnIdle { activity.recreate() }
        }
    }
}
