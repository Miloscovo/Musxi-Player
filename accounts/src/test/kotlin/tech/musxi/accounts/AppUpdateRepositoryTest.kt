package tech.musxi.accounts

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.musxi.core.update.UpdateCheckPhase
import tech.musxi.core.update.UpdateCheckResult
import tech.musxi.core.update.UpdateDownloadPhase
import tech.musxi.core.update.UpdateFailure

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppUpdateRepositoryTest {
    private val repositories = mutableListOf<AppUpdateRepository>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).installPackage(
            PackageInfo().apply {
                packageName = OFFICIAL_PACKAGE
                versionName = "0.1.0"
                longVersionCode = 1L
            },
        )
        context = object : ContextWrapper(application) {
            override fun getPackageName(): String = OFFICIAL_PACKAGE
            override fun getApplicationContext(): Context = this
        }
        context.getSharedPreferences("release-check-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        repositories.forEach { repository ->
            listOf("worker", "downloads").forEach { field ->
                val executor = AppUpdateRepository::class.java
                    .getDeclaredField(field)
                    .apply { isAccessible = true }
                    .get(repository) as java.util.concurrent.ExecutorService
                executor.shutdownNow()
            }
        }
        context.getSharedPreferences("release-check-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `development start is offline and manual metadata remains unavailable for install`() {
        val check = FakeHttp(releaseJson, manifest)
        val download = FakeHttp("", manifest)
        val repository = repository(releaseBuild = false, check = check, download = download)

        repository.start()
        idleMainFor(6_000)
        assertEquals(0, check.calls.get())
        assertTrue(repository.state.value.developmentBuild)

        val result = AtomicReference<UpdateCheckResult>()
        repository.check { result.set(it) }
        awaitUntil { repository.state.value.check.phase == UpdateCheckPhase.SUCCEEDED && result.get() != null }

        assertSame(UpdateCheckResult.NoUpdate, result.get())
        assertNotNull(repository.state.value.latest)
        assertEquals("0.2.0", repository.state.value.latest!!.version)

        repository.startDownload(repository.state.value.latest!!.version)
        assertEquals(UpdateDownloadPhase.FAILED, repository.state.value.download.phase)
        assertEquals(UpdateFailure.DEVELOPMENT_BUILD, repository.state.value.download.reason)

        repository.install()
        assertEquals(UpdateFailure.DEVELOPMENT_BUILD, repository.state.value.download.reason)
        assertEquals(0, download.calls.get())
    }

    @Test
    fun `successful check persists time and 30 second cache does not reset it`() {
        var clock = 1_700_000_000_000L
        val check = FakeHttp(releaseJson, manifest)
        val repository = repository(releaseBuild = true, check = check, now = { clock })

        repository.check()
        awaitUntil { repository.state.value.check.phase == UpdateCheckPhase.SUCCEEDED }
        assertEquals(clock, repository.state.value.lastSuccessAt)
        assertEquals(clock, preferences().getLong("last-success", 0))
        assertEquals(2, check.calls.get())

        clock += 29_999L
        repository.check()
        idleMainFor(20)

        assertEquals(2, check.calls.get())
        assertEquals(clock - 29_999L, repository.state.value.lastSuccessAt)
        assertEquals(clock - 29_999L, preferences().getLong("last-success", 0))

        val recreated = repository(releaseBuild = true)
        assertEquals(clock - 29_999L, recreated.state.value.lastSuccessAt)
    }

    @Test fun `startup respects successful daily check and the persisted toggle`() {
        val clock = 1_700_000_000_000L
        preferences().edit().putLong("last-success", clock - 86_400_000L + 1).commit()
        val recent = FakeHttp(releaseJson, manifest)
        repository(true, check = recent, now = { clock }).start()
        idleMainFor(6_000)
        assertEquals(0, recent.calls.get())
        preferences().edit().remove("last-success").putBoolean("auto-check", false).commit()
        val disabled = FakeHttp(releaseJson, manifest)
        repository(true, check = disabled, now = { clock }).start()
        idleMainFor(6_000)
        assertEquals(0, disabled.calls.get())
    }

    @Test fun `upgraded version enables auto check and persists the notice until consumed`() {
        preferences().edit().putString("installed-version", "0.1.0").putLong("installed-code", 1)
            .putBoolean("auto-check", false).commit()
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = OFFICIAL_PACKAGE; versionName = "0.2.0"; longVersionCode = 2
        })
        val upgraded = repository(true, currentVersion = "0.2.0")
        assertEquals("0.1.0", upgraded.state.value.justUpdatedFrom)
        assertTrue(upgraded.state.value.autoCheck)
        assertEquals("0.1.0", repository(true, currentVersion = "0.2.0").state.value.justUpdatedFrom)
        upgraded.consumeJustUpdated()
        assertEquals(null, repository(true, currentVersion = "0.2.0").state.value.justUpdatedFrom)
    }

    @Test
    fun `legacy check callback is delivered on the main looper`() {
        val callbackThread = AtomicReference<Thread>()
        val result = AtomicReference<UpdateCheckResult>()
        val repository = repository(releaseBuild = true)

        repository.check { value ->
            callbackThread.set(Thread.currentThread())
            result.set(value)
        }
        awaitUntil { result.get() != null }

        assertSame(Looper.getMainLooper().thread, callbackThread.get())
        assertFalse(repository.state.value.developmentBuild)
        assertTrue(result.get() is UpdateCheckResult.UpdateAvailable)
    }

    @Test fun `failed download deletes its private partial file and stale version never downloads`() {
        val download = FakeHttp("", manifest)
        val repository = repository(true, download = download)
        repository.check()
        awaitUntil { repository.state.value.check.phase == UpdateCheckPhase.SUCCEEDED }
        repository.startDownload("0.1.5")
        assertEquals(UpdateFailure.RELEASE_CHANGED, repository.state.value.download.reason)
        assertEquals(0, download.transfers.get())
        repository.startDownload("0.2.0")
        awaitUntil { repository.state.value.download.phase == UpdateDownloadPhase.FAILED }
        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, repository.state.value.download.reason)
        assertEquals(1, download.transfers.get())
        assertTrue(java.io.File(context.cacheDir, "app-updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun `cancel interrupts an active download and removes its partial file`() {
        val download = FakeHttp("", manifest, blockDownload = true)
        val repository = repository(true, download = download)
        repository.check()
        awaitUntil { repository.state.value.check.phase == UpdateCheckPhase.SUCCEEDED }
        repository.startDownload("0.2.0")
        awaitUntil { download.transfers.get() == 1 }
        repository.cancelDownload()
        awaitUntil { java.io.File(context.cacheDir, "app-updates").listFiles().orEmpty().isEmpty() }
        assertEquals(UpdateFailure.CANCELLED, repository.state.value.download.reason)
    }

    private fun repository(
        releaseBuild: Boolean,
        check: UpdateHttpClient = FakeHttp(releaseJson, manifest),
        download: UpdateHttpClient = FakeHttp("", manifest),
        now: () -> Long = System::currentTimeMillis,
        currentVersion: String = "0.1.0",
    ): AppUpdateRepository = AppUpdateRepository(
        context = context,
        currentVersion = currentVersion,
        releaseBuild = releaseBuild,
        beforeInstall = { it(Result.success(Unit)) },
        checkHttp = check,
        downloadHttp = download,
        now = now,
    ).also(repositories::add)

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for update state")
            idleMainFor(10)
            Thread.sleep(1)
        }
    }

    private fun idleMainFor(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(milliseconds))
    }

    private fun preferences() =
        context.getSharedPreferences("release-check-v1", Context.MODE_PRIVATE)

    private class FakeHttp(
        private val releases: String,
        private val manifest: ByteArray = ByteArray(0),
        private val blockDownload: Boolean = false,
    ) : UpdateHttpClient() {
        val calls = AtomicInteger()
        val transfers = AtomicInteger()

        override fun transfer(url: String, limit: Long, expectedHash: String?, output: java.io.OutputStream,
            cancelled: AtomicBoolean, progress: (Long) -> Unit) {
            transfers.incrementAndGet()
            if (blockDownload) {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!cancelled.get() && System.nanoTime() < deadline) Thread.sleep(1)
            }
            copyUpdateBytes("testapk".byteInputStream(), output, limit, expectedHash, cancelled, progress)
        }

        override fun read(
            url: String,
            maxBytes: Long,
            cancelled: AtomicBoolean,
        ): ByteArray {
            calls.incrementAndGet()
            return if (url == UpdateHttpClient.RELEASES_URL) releases.toByteArray()
            else manifest
        }
    }

    private companion object {
        const val OFFICIAL_PACKAGE = "tech.musxi.player"
        const val releasePage =
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0"
        const val manifestUrl =
            "https://github.com/qwt1011/Musxi-Player-Android/releases/download/v0.2.0/update-manifest.json"
        const val apkUrl =
            "https://github.com/qwt1011/Musxi-Player-Android/releases/download/v0.2.0/Musxi-Player-0.2.0.apk"

        val manifest =
            ("{\"schemaVersion\":1,\"packageName\":\"tech.musxi.player\"," +
                "\"versionName\":\"0.2.0\",\"versionCode\":2,\"minSdk\":26," +
                "\"assetName\":\"Musxi-Player-0.2.0.apk\",\"bytes\":7," +
                "\"sha256\":\"${"a".repeat(64)}\"," +
                "\"signerSha256\":\"${"b".repeat(64)}\"}")
                .toByteArray(Charsets.UTF_8)

        val releaseJson =
            """[
              {
                "id": 7,
                "tag_name": "v0.2.0",
                "html_url": "$releasePage",
                "body": "Release notes",
                "prerelease": false,
                "draft": false,
                "published_at": "2026-11-01T00:00:00Z",
                "assets": [
                  {
                    "id": 8,
                    "name": "update-manifest.json",
                    "size": ${manifest.size},
                    "browser_download_url": "$manifestUrl",
                    "state": "uploaded"
                  },
                  {
                    "id": 9,
                    "name": "Musxi-Player-0.2.0.apk",
                    "size": 7,
                    "browser_download_url": "$apkUrl",
                    "state": "uploaded"
                  }
                ]
              }
            ]""".trimIndent()
    }
}
