package tech.musxi.player

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import tech.musxi.core.account.AccountCoordinator
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AvatarPolicy
import tech.musxi.core.account.Platform

/** Read-only real profile/CDN checks; report booleans, CDN hosts and dimensions only. */
@RunWith(AndroidJUnit4::class)
class BackendBatch5DeviceTest {
    private lateinit var phone: PhoneDriver
    @Before fun openProduct() { phone = PhoneDriver() }
    @After fun leavePaused() { if (this::phone.isInitialized) phone.runOnIdle { (phone.activity.application as MusxiApplication).playback.pause() } }

    /** Run separately with networking disabled by the host; never alters saved accounts. */
    @Test fun offlineColdStartDisplaysSavedAvatarProfiles() {
        val app = phone.activity.application as MusxiApplication
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Offline scenario still has an active network", network.activeNetwork)
        phone.waitUntil(10_000) {
            Platform.entries.all { app.accounts.states.value.getValue(it).profile?.avatarUrl != null }
        }
        val rows = JSONArray()
        Platform.entries.forEach { platform ->
            val state = app.accounts.states.value.getValue(platform)
            rows.put(JSONObject().put("platform", platform.name).put("phase", state.phase.name)
                .put("savedAvatarAvailableOffline", state.profile?.avatarUrl != null))
        }
        phone.clickText("我的", exact = true)
        phone.waitForIdle()
        Thread.sleep(800)
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(app.filesDir, "batch5-offline-me.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        File(app.filesDir, "batch5-offline-profiles.json").writeText(JSONObject()
            .put("networkAbsent", network.activeNetwork == null).put("profiles", rows).toString(2))
    }

    @Test fun realAccountPagesRemainConnectedAfterColdStart() {
        val app = phone.activity.application as MusxiApplication
        phone.waitUntil(60_000) {
            Platform.entries.all { app.accounts.states.value.getValue(it).phase == AccountPhase.CONNECTED }
        }
        phone.clickText("我的", exact = true)
        fun capture(name: String) {
            phone.waitForIdle()
            Thread.sleep(800)
            val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(app.filesDir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        capture("batch5-me.png")
        for ((platform, label) in listOf(Platform.KUGOU to "酷狗", Platform.QQ to "QQ音乐", Platform.NETEASE to "网易云")) {
            phone.clickText("$label ·")
            val heading = when (platform) {
                Platform.KUGOU -> "酷狗音乐"
                Platform.QQ -> "QQ音乐"
                Platform.NETEASE -> "网易云音乐"
            }
            phone.waitUntil(10_000) { phone.hasExactText(heading) }
            capture("batch5-account-${platform.name.lowercase()}.png")
            phone.runOnIdle { phone.activity.onBackPressedDispatcher.onBackPressed() }
        }
        phone.runOnIdle { app.playback.pause() }
    }

    @Test fun avatarsRefreshPersistAndDecodeWithoutChangingAccountOrQueue() {
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject()
        val rows = JSONArray()
        report.put("avatars", rows)
        try {
            phone.waitUntil(60_000) {
                Platform.entries.all { app.accounts.states.value.getValue(it).phase == AccountPhase.CONNECTED }
            }
            phone.runOnIdle { app.playback.pause() }
            val queueBefore = app.playback.queueState.value
            val identities = Platform.entries.associateWith { app.accounts.playbackIdentity(it) }
            val failures = mutableListOf<String>()
            for (platform in Platform.entries) {
                val before = app.accounts.states.value.getValue(platform)
                val row = JSONObject().put("platform", platform.name)
                rows.put(row)
                try {
                val refreshed = CompletableFuture<Result<Unit>>()
                phone.runOnIdle { app.accounts.refreshProfile(platform) { refreshed.complete(it) } }
                refreshed.get(40, TimeUnit.SECONDS).getOrThrow()
                val after = app.accounts.states.value.getValue(platform)
                val rawSaved = when (platform) {
                    Platform.KUGOU -> tech.musxi.loginpoc.SessionStore(app).load()?.avatarUrl
                    Platform.QQ -> tech.musxi.qqloginpoc.SessionStore(app).load()?.avatarUrl
                    Platform.NETEASE -> tech.musxi.neteaseloginpoc.SessionStore(app).load()?.avatarUrl
                }
                val avatar = after.profile?.avatarUrl
                if (platform == Platform.QQ && rawSaved == null) inspectQqShape(app, row)
                val parsed = runCatching { URI(rawSaved ?: "") }.getOrNull()
                if (platform == Platform.QQ) {
                    val fields = parsed?.rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }.orEmpty()
                    row.put("pictureKind", fields["b"]?.takeIf { it.matches(Regex("[a-z]{1,12}")) } ?: "other")
                        .put("pictureKeyHasSlash", fields["k"]?.contains('/') ?: false)
                        .put("pictureKeyHasPlus", fields["k"]?.contains('+') ?: false)
                        .put("ktiNumeric", fields["kti"]?.matches(Regex("[0-9]{1,10}")) ?: true)
                }
                row.put("rawAvatarPresent", rawSaved != null)
                    .put("rawHost", runCatching { URI(rawSaved ?: "").host }.getOrNull() ?: JSONObject.NULL)
                    .put("scheme", parsed?.scheme ?: JSONObject.NULL)
                    .put("port", parsed?.port ?: -1)
                    .put("hasCredentials", parsed?.rawUserInfo != null)
                    .put("hasFragment", parsed?.rawFragment != null)
                    .put("hasQuery", parsed?.rawQuery != null)
                    .put("queryKeys", JSONArray(parsed?.rawQuery?.split('&')?.map { it.substringBefore('=') }.orEmpty()))
                    .put("pathLength", parsed?.rawPath?.length ?: -1)
                    .put("addressLength", rawSaved?.length ?: 0)
                    .put("hasWhitespace", rawSaved?.any { it.isWhitespace() || it.isISOControl() } ?: false)
                    .put("authorityMatchesHost", parsed?.rawAuthority?.lowercase() == parsed?.host?.lowercase())
                    .put("pathNormalized", parsed?.normalize()?.rawPath == parsed?.rawPath)
                    .put("pathHasEscapes", parsed?.rawPath?.contains('%') ?: false)
                    .put("kugouIconPath", parsed?.rawPath?.startsWith("/kugouicon/") ?: false)
                    .put("accepted", avatar != null)
                    .put("persistedMatches", avatar == AvatarPolicy.sanitize(platform, rawSaved))
                    .put("generationUnchanged", before.generation == after.generation)
                    .put("identityUnchanged", before.profile?.id == after.profile?.id)
                    .put("cacheOwnerUnchanged", identities[platform] == app.accounts.playbackIdentity(platform))
                assertEquals(before.generation, after.generation)
                assertEquals(before.profile?.id, after.profile?.id)
                assertEquals(identities[platform], app.accounts.playbackIdentity(platform))
                assertEquals(avatar, AvatarPolicy.sanitize(platform, rawSaved))
                // The exact encrypted identity can be published synchronously, without a verifier.
                val restored = AccountCoordinator()
                restored.restore(platform, after.profile)
                assertEquals(AccountPhase.REVALIDATING, restored.states.value.getValue(platform).phase)
                assertEquals(avatar, restored.states.value.getValue(platform).profile?.avatarUrl)
                assertNotNull("Verified profile has no accepted avatar: ${platform.name}", avatar)
                val connection = URL(requireNotNull(avatar)).openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                try {
                    row.put("status", connection.responseCode)
                    assertEquals("Avatar CDN did not return an image: ${platform.name}", 200, connection.responseCode)
                    val bytes = connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val block = ByteArray(8192)
                        while (true) {
                            val count = input.read(block)
                            if (count < 0) break
                            check(out.size() + count <= 2 * 1024 * 1024)
                            out.write(block, 0, count)
                        }
                        out.toByteArray()
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    row.put("width", bitmap?.width ?: 0).put("height", bitmap?.height ?: 0)
                    assertNotNull("Avatar cannot be decoded: ${platform.name}", bitmap)
                    assertTrue(requireNotNull(bitmap).width in 120..200 && bitmap.height in 120..200)
                    bitmap.recycle()
                } finally { connection.disconnect() }
                } catch (failure: Throwable) {
                    row.put("failureClass", failure.javaClass.simpleName)
                    failures += platform.name
                }
            }
            val queueAfter = app.playback.queueState.value
            val unchanged = queueBefore.songs == queueAfter.songs && queueBefore.currentIndex == queueAfter.currentIndex
            report.put("queueUnchanged", unchanged).put("queueSize", queueAfter.songs.size)
            assertTrue(unchanged)
            assertTrue("Avatar verification failed for: ${failures.joinToString()}", failures.isEmpty())
            report.put("passed", true)
        } finally {
            phone.runOnIdle { app.playback.pause() }
            report.put("paused", !app.playback.state.value.playing && !app.playback.state.value.playWhenReady)
            File(app.filesDir, "batch5-avatars-device.json").writeText(report.toString(2))
        }
    }

    private fun inspectQqShape(app: MusxiApplication, row: JSONObject) {
        val session = requireNotNull(tech.musxi.qqloginpoc.SessionStore(app).load())
        val api = tech.musxi.qqloginpoc.QqMusicApi()
        val method = api.javaClass.getDeclaredMethod("cgi", String::class.java, String::class.java,
            JSONObject::class.java, tech.musxi.qqloginpoc.QqMusicApi.Session::class.java).apply { isAccessible = true }
        fun shape(value: JSONObject): JSONObject {
            val result = JSONObject()
            value.keys().asSequence().take(50).forEach { key ->
                if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
                    val field = value.opt(key)
                    result.put(key, when (field) {
                        is String -> if (field.startsWith("http")) "url:" + runCatching { URI(field).host }.getOrDefault("invalid") else "string"
                        is JSONObject -> "object"
                        is JSONArray -> "array"
                        is Number -> "number"
                        is Boolean -> "boolean"
                        else -> "null"
                    })
                }
            }
            return result
        }
        val credential = session.toJson().getJSONObject("credential")
        val euin = credential.optString("encryptUin")
        row.put("credentialHasEncryptedUin", euin.isNotEmpty())
        val data = method.invoke(api, "music.UserInfo.userInfoServer", "GetLoginUserInfo", JSONObject(), session) as JSONObject
        row.put("loginShape", shape(data))
        data.optJSONObject("info")?.let { row.put("loginInfoShape", shape(it)) }
        if (euin.isNotEmpty()) {
            val profile = try { method.invoke(api, "music.UnifiedHomepage.UnifiedHomepageSrv", "GetHomepageHeader",
                JSONObject().put("uin", euin).put("IsQueryTabDetail", 1), session) as JSONObject }
            catch (error: java.lang.reflect.InvocationTargetException) {
                row.put("profileError", (error.cause as? tech.musxi.qqloginpoc.QqMusicApi.ApiException)?.code ?: "unclassified")
                return
            }
            row.put("profileShape", shape(profile))
            profile.optJSONObject("Info")?.let { info ->
                row.put("infoShape", shape(info))
                info.optJSONObject("BaseInfo")?.let { row.put("baseShape", shape(it)) }
            }
        }
    }

    /** Existing product Activity, bounded main-thread calls; no ActivityScenario teardown. */
    internal class PhoneDriver {
        private val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        private var currentActivity: MainActivity? = null
        val activity get() = requireNotNull(currentActivity)

        init {
            instrumentation.runOnMainSync {
                instrumentation.targetContext.startActivity(android.content.Intent(
                    instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitUntil(10_000) {
                instrumentation.runOnMainSync {
                    currentActivity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                currentActivity != null
            }
        }

        fun runOnIdle(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun waitForIdle() = instrumentation.waitForIdleSync()
        fun waitUntil(timeout: Long, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Device condition timed out" }
                Thread.sleep(50)
            }
        }
        fun hasExactText(label: String): Boolean {
            fun found(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.text?.toString() == label) return true
                return (0 until node.childCount).any { found(node.getChild(it)) }
            }
            return found(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun clickText(label: String, exact: Boolean = false) {
            fun clickable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (if (exact) node.text?.toString() == label else node.text?.contains(label) == true) {
                    var parent: android.view.accessibility.AccessibilityNodeInfo? = node
                    repeat(6) {
                        if (parent?.isClickable == true) return parent
                        parent = parent?.parent
                    }
                }
                for (i in 0 until node.childCount) clickable(node.getChild(i))?.let { return it }
                return null
            }
            var target: android.view.accessibility.AccessibilityNodeInfo? = null
            waitUntil(10_000) { target = clickable(instrumentation.uiAutomation.rootInActiveWindow); target != null }
            // Compose accessibility actions may acknowledge without moving the page on this device.
            run {
                val bounds = android.graphics.Rect()
                requireNotNull(target).getBoundsInScreen(bounds)
                check(!bounds.isEmpty)
                val down = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            Thread.sleep(250)
        }
    }
}
