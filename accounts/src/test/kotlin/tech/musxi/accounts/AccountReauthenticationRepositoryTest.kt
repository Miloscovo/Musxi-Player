package tech.musxi.accounts

import android.content.Intent
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tech.musxi.core.account.*
import tech.musxi.core.library.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccountReauthenticationRepositoryTest {
    private class Session(val id: String)
    private class Driver : PlatformDriver {
        val original = Session("7")
        var saved: Session? = original
        var candidate = Session("7")
        var verification: (Session) -> Session = { it }
        var saves = 0
        var clears = 0
        var saveFailure: Exception? = null
        override fun load(): Any? = saved
        override fun verify(session: Any): Any = verification(session as Session)
        override fun save(session: Any) { saveFailure?.let { throw it }; saves++; saved = session as Session }
        override fun clear() { clears++; saved = null }
        override fun identity(session: Any) = Identity((session as Session).id, "Fixture")
        override fun membership(session: Any) = Membership()
        override fun create() = LoginRequest(Any(), Intent(Intent.ACTION_VIEW))
        override fun poll(request: LoginRequest) = PollResult.Complete(candidate)
        override fun importBrowserSession(header: String): Any = candidate
    }
    private val queue = Executors.newSingleThreadScheduledExecutor()
    private val membership = Executors.newSingleThreadExecutor()
    private lateinit var repository: AccountRepository
    private lateinit var drivers: Map<Platform, Driver>
    private var browserClear: ((Boolean) -> Unit)? = null
    private val releases = mutableListOf<CountDownLatch>()
    private var webToken: Long? = null
    private val officialReturns = mutableListOf<Pair<Platform, Long>>()

    @Before fun start() {
        drivers = Platform.entries.associateWith { Driver() }
        repository = AccountRepository(RuntimeEnvironment.getApplication(), drivers, queue,
            Platform.entries.associateWith { queue }, Platform.entries.associateWith { membership },
            observeNetwork = false, browserLogout = { browserClear = it },
            officialLoginCompleted = { platform, token -> officialReturns += platform to token })
        settle(); repository.setForeground(true); settle()
    }
    @After fun finish() {
        releases.forEach { it.countDown() }
        repository.setForeground(false)
        queue.shutdownNow(); membership.shutdownNow()
        queue.awaitTermination(3, TimeUnit.SECONDS); membership.awaitTermination(3, TimeUnit.SECONDS)
    }
    private fun workers() { repeat(4) { queue.submit {}.get(3, TimeUnit.SECONDS) } }
    private fun settle() { workers(); shadowOf(Looper.getMainLooper()).idle(); workers() }
    private fun state(platform: Platform = Platform.QQ) = repository.states.value.getValue(platform)
    private fun launch(platform: Platform = Platform.QQ) {
        if (platform == Platform.NETEASE) webToken = requireNotNull(repository.beginWebLogin(reauthenticate = true))
        else repository.reauthenticateOfficial(platform) {}
        settle()
        assertEquals(AccountPhase.WAITING_CONFIRMATION, state(platform).phase)
    }
    private fun complete(platform: Platform) {
        if (platform == Platform.NETEASE) repository.completeWebLogin(requireNotNull(webToken), "fixture")
        else repository.checkNow(platform)
    }
    private fun expire(platform: Platform) {
        drivers.getValue(platform).verification = { throw platformError(platform, "expired") }
        repository.refreshProfile(platform); settle()
        assertEquals(AccountPhase.REAUTH_REQUIRED, state(platform).phase)
        drivers.getValue(platform).verification = { it }
    }

    @Test fun kugouBackgroundAuthorizationReturnsOnceOnlyAfterCandidateSaved() {
        launch(Platform.KUGOU)
        val token = state(Platform.KUGOU).generation
        repository.setForeground(false)
        complete(Platform.KUGOU); settle()
        assertEquals(AccountPhase.CONNECTED, state(Platform.KUGOU).phase)
        assertSame(drivers.getValue(Platform.KUGOU).candidate, drivers.getValue(Platform.KUGOU).saved)
        assertEquals(listOf(Platform.KUGOU to token), officialReturns)
        complete(Platform.KUGOU); settle()
        assertEquals(1, officialReturns.size)
    }

    @Test fun cancelledKugouAttemptNeverReturnsOrReplacesOriginal() {
        launch(Platform.KUGOU)
        repository.setForeground(false)
        repository.cancel(Platform.KUGOU); settle()
        complete(Platform.KUGOU); settle()
        assertTrue(officialReturns.isEmpty())
        assertSame(drivers.getValue(Platform.KUGOU).original, drivers.getValue(Platform.KUGOU).saved)
    }

    @Test fun failedKugouVerificationNeverReturnsAndQqStillPausesInBackground() {
        launch(Platform.KUGOU)
        drivers.getValue(Platform.KUGOU).verification = { throw IOException("fixture") }
        repository.setForeground(false)
        complete(Platform.KUGOU); settle()
        assertTrue(officialReturns.isEmpty())
        assertSame(drivers.getValue(Platform.KUGOU).original, drivers.getValue(Platform.KUGOU).saved)
        repository.setForeground(true); settle()
        launch(Platform.QQ)
        repository.setForeground(false)
        complete(Platform.QQ); settle()
        assertEquals(AccountPhase.WAITING_CONFIRMATION, state().phase)
    }

    @Test fun sameUserCandidateReplacesOnlyAfterVerifiedAndKeepsOwnerMetadata() {
        Platform.entries.forEach { platform ->
            val owner = repository.playbackIdentity(platform)!!.owner
            val cache = ContentMetadataCache(RuntimeEnvironment.getApplication().noBackupFilesDir).libraryPages.getValue(platform)
            val key = if (platform == Platform.QQ) "10_1" else "10"
            val cachedPage = SavedLibraryPage(Page(listOf(PlaylistSummary(MediaKey(platform, key), "Fixture", null, 0)), null, 1), 1L)
            cache.writePlaylists(owner, 0, cachedPage)
            val previous = state(platform)
            launch(platform)
            assertSame(drivers.getValue(platform).original, drivers.getValue(platform).saved)
            complete(platform); settle()
            assertEquals(AccountPhase.CONNECTED, state(platform).phase)
            assertTrue(state(platform).generation > previous.generation)
            assertSame(drivers.getValue(platform).candidate, drivers.getValue(platform).saved)
            assertEquals(owner, repository.playbackIdentity(platform)!!.owner)
            assertEquals(cachedPage, cache.readPlaylists(owner, 0))
            assertEquals(0, drivers.getValue(platform).clears)
        }
    }

    @Test fun rejectedCandidatePreservesExpiredIdentityCredentialsAndCache() {
        Platform.entries.forEach { platform ->
            expire(platform)
            val before = state(platform)
            val driver = drivers.getValue(platform)
            driver.verification = { throw IOException("fixture") }
            launch(platform); complete(platform); settle()
            assertEquals(AccountPhase.REAUTH_REQUIRED, state(platform).phase)
            assertEquals(AccountError.EXPIRED, state(platform).error)
            assertEquals(before.profile, state(platform).profile)
            assertSame(driver.original, driver.saved)
            assertEquals(0, driver.clears)
        }
    }

    @Test fun candidateStorageFailureRollsBackConnectedIdentityWithoutClearingSession() {
        Platform.entries.forEach { platform ->
            launch(platform)
            drivers.getValue(platform).saveFailure = platformError(platform, "storage")
            complete(platform); settle()
            assertEquals(AccountPhase.CONNECTED, state(platform).phase)
            assertSame(drivers.getValue(platform).original, drivers.getValue(platform).saved)
            assertEquals(AccountError.STORAGE, repository.verification.value.getValue(platform).lastFailure)
        }
    }

    @Test fun verifiedDifferentUserIsIsolatedFromPreviousOwner() {
        Platform.entries.forEach { platform ->
            val owner = repository.playbackIdentity(platform)!!.owner
            drivers.getValue(platform).candidate = Session("8")
            launch(platform); complete(platform); settle()
            assertEquals("8", state(platform).profile!!.id)
            assertNotEquals(owner, repository.playbackIdentity(platform)!!.owner)
            assertEquals(0, drivers.getValue(platform).clears)
        }
    }

    @Test fun cancellationDuringCandidateVerificationRejectsItsLateSession() {
        Platform.entries.forEach { platform ->
            launch(platform)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also { releases += it }
            val driver = drivers.getValue(platform)
            driver.verification = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); it }
            complete(platform)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repository.cancel(platform)
            assertEquals(AccountPhase.CONNECTED, state(platform).phase)
            release.countDown(); settle()
            assertSame(driver.original, driver.saved)
            assertEquals(1, driver.saves)
        }
    }

    @Test fun candidateIdentityMismatchIsNeverSaved() {
        Platform.entries.forEach { platform ->
            launch(platform)
            drivers.getValue(platform).verification = { Session("99") }
            complete(platform); settle()
            assertEquals("7", state(platform).profile!!.id)
            assertSame(drivers.getValue(platform).original, drivers.getValue(platform).saved)
        }
    }

    @Test fun reauthenticationDeadlineRestoresExpiredStateAndKeepsSavedCredentials() {
        Platform.entries.forEach { platform ->
            expire(platform); launch(platform)
            shadowOf(Looper.getMainLooper()).idleFor(180, TimeUnit.SECONDS); settle()
            assertEquals(AccountPhase.REAUTH_REQUIRED, state(platform).phase)
            assertEquals(AccountError.EXPIRED, state(platform).error)
            assertSame(drivers.getValue(platform).original, drivers.getValue(platform).saved)
        }
    }

    @Test fun neteaseWebReauthenticationCancellationKeepsExpiredIdentity() {
        expire(Platform.NETEASE)
        val token = requireNotNull(repository.beginWebLogin(reauthenticate = true))
        assertTrue(repository.isWebLoginCurrent(token))
        repository.cancelWebLogin(token); settle()
        assertEquals(AccountPhase.REAUTH_REQUIRED, state(Platform.NETEASE).phase)
        assertFalse(repository.isWebLoginCurrent(token))
        assertSame(drivers.getValue(Platform.NETEASE).original, drivers.getValue(Platform.NETEASE).saved)
    }

    @Test fun unverifiedNeteaseBrowserCandidateAcquiresIdentityOnlyThroughVerification() {
        val driver = drivers.getValue(Platform.NETEASE)
        driver.candidate = Session("")
        driver.verification = { Session("7") }
        launch(Platform.NETEASE)
        complete(Platform.NETEASE); settle()
        assertEquals(AccountPhase.CONNECTED, state(Platform.NETEASE).phase)
        assertEquals("7", requireNotNull(driver.saved).id)
        assertEquals(0, driver.clears)
    }

    @Test fun explicitNeteaseLogoutWaitsForBrowserCleanupAndDoesNotReopenThroughCancel() {
        repository.disconnect(Platform.NETEASE); workers()
        assertNull(drivers.getValue(Platform.NETEASE).saved)
        assertEquals(AccountPhase.PREPARING, state(Platform.NETEASE).phase)
        repository.cancel(Platform.NETEASE)
        assertEquals(AccountPhase.PREPARING, state(Platform.NETEASE).phase)
        requireNotNull(browserClear).invoke(true); settle()
        assertEquals(AccountPhase.DISCONNECTED, state(Platform.NETEASE).phase)
    }

    @Test fun browserCleanupFailureNeverRestoresADeletedNativeSessionAndCanBeRetried() {
        repository.disconnect(Platform.NETEASE); workers()
        requireNotNull(browserClear).invoke(false); settle()
        assertNull(drivers.getValue(Platform.NETEASE).saved)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state(Platform.NETEASE).phase)
        assertEquals(AccountError.STORAGE, state(Platform.NETEASE).error)
        repository.disconnect(Platform.NETEASE); workers()
        requireNotNull(browserClear).invoke(true); settle()
        assertEquals(AccountPhase.DISCONNECTED, state(Platform.NETEASE).phase)
    }

    private fun platformError(platform: Platform, category: String): Exception {
        val name = when (platform) {
            Platform.QQ -> "tech.musxi.qqloginpoc.QqMusicApi\$ApiException"
            Platform.NETEASE -> "tech.musxi.neteaseloginpoc.NeteaseApi\$ApiException"
            Platform.KUGOU -> "tech.musxi.loginpoc.KugouApi\$ApiException"
        }
        return Class.forName(name).getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(category, "fixture") as Exception
    }
}
