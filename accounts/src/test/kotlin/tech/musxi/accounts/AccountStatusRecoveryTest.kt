package tech.musxi.accounts

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ExecutorService
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
import org.robolectric.shadows.ShadowNetworkInfo
import tech.musxi.core.account.*

/** Runs the real repository against private fake drivers; no network or real credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION") // Robolectric configures the default network through NetworkInfo.
class AccountStatusRecoveryTest {
    private lateinit var queue: ScheduledExecutorService
    private lateinit var membership: ExecutorService
    private lateinit var drivers: Map<Platform, Driver>
    private lateinit var repository: AccountRepository
    private var elapsed = 0L
    private var epoch = 10_000L

    private class Driver(val platform: Platform) : PlatformDriver {
        val session = Any()
        @Volatile var failure: Exception? = null
        @Volatile var saved: Any? = session
        @Volatile var verifies = 0
        @Volatile var onVerify: (() -> Unit)? = null
        override fun load(): Any? = saved
        override fun verify(session: Any): Any {
            verifies++
            onVerify?.invoke()
            failure?.let { throw it }
            return session
        }
        override fun save(session: Any) { saved = session }
        override fun clear() { saved = null }
        override fun identity(session: Any) = Identity("7", "Fixture")
        override fun membership(session: Any) = Membership()
        override fun create(): LoginRequest = error("No login request allowed")
        override fun poll(request: LoginRequest): PollResult = error("No polling allowed")
    }

    @Before fun prepare() {
        queue = Executors.newSingleThreadScheduledExecutor()
        membership = Executors.newSingleThreadExecutor()
        drivers = Platform.entries.associateWith { Driver(it) }
    }

    private fun start(observeNetwork: Boolean = false) {
        repository = AccountRepository(RuntimeEnvironment.getApplication(),
            drivers, queue, Platform.entries.associateWith { queue }, Platform.entries.associateWith { membership },
            { elapsed }, { epoch }, observeNetwork = observeNetwork, browserLogout = { it(true) })
        settle()
    }

    @After fun finish() {
        if (::repository.isInitialized) repository.setForeground(false)
        queue.shutdownNow(); membership.shutdownNow()
        queue.awaitTermination(3, TimeUnit.SECONDS); membership.awaitTermination(3, TimeUnit.SECONDS)
    }

    private fun workers() { repeat(5) { queue.submit {}.get(3, TimeUnit.SECONDS) } }
    private fun settle() { workers(); shadowOf(Looper.getMainLooper()).idle(); workers() }
    private fun state(platform: Platform = Platform.NETEASE) = repository.states.value.getValue(platform)

    @Test fun profileExpiryRetiresOnlyTheMatchingPlatformAndKeepsLocalIdentity() {
        start()
        Platform.entries.forEach { platform ->
            val before = state(platform)
            drivers.getValue(platform).failure = platformError(platform, "expired")
            var result: Result<Unit>? = null
            repository.refreshProfile(platform) { result = it }
            settle()
            assertTrue(result!!.isFailure)
            assertEquals(AccountPhase.REAUTH_REQUIRED, state(platform).phase)
            assertEquals(before.profile, state(platform).profile)
            assertTrue(state(platform).generation > before.generation)
            assertSame(drivers.getValue(platform).session, drivers.getValue(platform).saved)
            val diagnostic = repository.verification.value.getValue(platform)
            assertEquals(AccountError.EXPIRED, diagnostic.lastFailure)
            assertEquals(10_000L, diagnostic.lastVerifiedAtEpochMs)
        }
    }

    @Test fun profileNetworkAndPlatformFailuresDoNotExpireAConnectedAccount() {
        start()
        for (category in listOf("network", "risk", "rate", "permission", "copyright", "protocol")) {
            val before = state()
            drivers.getValue(Platform.NETEASE).failure = platformError(Platform.NETEASE, category)
            repository.refreshProfile(Platform.NETEASE)
            settle()
            assertEquals(before, state())
            assertEquals(if (category == "network") AccountError.NETWORK else AccountError.PLATFORM,
                repository.verification.value.getValue(Platform.NETEASE).lastFailure)
        }
        epoch = 20_000L
        drivers.getValue(Platform.NETEASE).failure = null
        repository.refreshProfile(Platform.NETEASE)
        settle()
        assertEquals(AccountVerification(lastVerifiedAtEpochMs = 20_000L),
            repository.verification.value.getValue(Platform.NETEASE))
    }

    @Test fun savedNetworkFailureRecoversOnceAfterForegroundCooldown() {
        drivers.getValue(Platform.NETEASE).failure = IOException("fixture-private-text")
        start()
        assertEquals(AccountPhase.RETRYABLE_ERROR, state().phase)
        assertNotNull(state().profile)
        repository.setForeground(true)
        elapsed = 59_999L
        shadowOf(Looper.getMainLooper()).idleFor(59_999, TimeUnit.MILLISECONDS)
        workers()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)
        drivers.getValue(Platform.NETEASE).failure = null
        elapsed = 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        settle()
        assertEquals(AccountPhase.CONNECTED, state().phase)
        assertEquals(2, drivers.getValue(Platform.NETEASE).verifies)
        assertNull(state().error)
    }

    @Test fun automaticNetworkFailureDoesNotRepeatWithoutANewForegroundOrNetworkEvent() {
        drivers.getValue(Platform.NETEASE).failure = IOException("offline")
        start(); repository.setForeground(true)
        elapsed = 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS); settle()
        assertEquals(2, drivers.getValue(Platform.NETEASE).verifies)
        elapsed += 600_000L
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES); settle()
        assertEquals(2, drivers.getValue(Platform.NETEASE).verifies)
        repository.setForeground(false); repository.setForeground(true); settle()
        assertEquals(3, drivers.getValue(Platform.NETEASE).verifies)
    }

    @Test fun validatedDefaultNetworkRecoveryRetriesOnceAndIgnoresDuplicateCallbacks() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = requireNotNull(manager.activeNetwork)
        shadowOf(manager).setNetworkCapabilities(network, NetworkCapabilities())
        drivers.getValue(Platform.NETEASE).failure = IOException("offline")
        start(observeNetwork = true)
        repository.setForeground(true)
        elapsed = 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS); settle()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)

        val capabilities = NetworkCapabilities().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        val callback = shadowOf(manager).networkCallbacks.single()
        drivers.getValue(Platform.NETEASE).failure = null
        repeat(3) { callback.onCapabilitiesChanged(network, capabilities) }
        settle()
        assertEquals(AccountPhase.CONNECTED, state().phase)
        assertEquals(2, drivers.getValue(Platform.NETEASE).verifies)
    }

    @Test fun queuedRecoveryRechecksCurrentNetworkBeforeClaimingItsAttempt() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = NetworkCapabilities().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        drivers.getValue(Platform.NETEASE).failure = IOException("offline")
        start(observeNetwork = true)
        repository.setForeground(true)
        val generation = state().generation
        // The callback has not arrived, but the active network is already unvalidated.
        shadowOf(manager).setNetworkCapabilities(network, NetworkCapabilities())
        elapsed = 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS); settle()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)
        assertEquals(generation, state().generation)

        val callback = shadowOf(manager).networkCallbacks.single()
        callback.onLost(network); settle()
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        drivers.getValue(Platform.NETEASE).failure = null
        callback.onCapabilitiesChanged(network, capabilities); settle()
        assertEquals(AccountPhase.CONNECTED, state().phase)
        assertEquals(2, drivers.getValue(Platform.NETEASE).verifies)
    }

    @Test fun leavingForegroundCancelsPendingRecoveryAndExplicitRetryKeepsProfile() {
        drivers.getValue(Platform.NETEASE).failure = IOException("offline")
        start(); repository.setForeground(true); repository.setForeground(false)
        elapsed = 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS); settle()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)
        val profile = state().profile
        drivers.getValue(Platform.NETEASE).failure = null
        repository.restore(Platform.NETEASE)
        assertEquals(profile, state().profile)
        settle()
        assertEquals(AccountPhase.CONNECTED, state().phase)
    }

    @Test fun recoveryAlreadyQueuedBeforeBackgroundDoesNotStartHttpVerification() {
        drivers.getValue(Platform.NETEASE).failure = IOException("offline")
        start(); repository.setForeground(true); workers()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        queue.execute { blocked.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        assertTrue(blocked.await(3, TimeUnit.SECONDS))
        try {
            elapsed = 60_000L
            shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
            assertEquals(AccountPhase.REVALIDATING, state().phase)
            repository.setForeground(false)
        } finally { release.countDown() }
        settle()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state().phase)
        assertNotNull(state().profile)
    }

    @Test fun foregroundAndNetworkEventsNeverLoginAnAccountWithoutSavedIdentity() {
        drivers.getValue(Platform.NETEASE).saved = null
        start(); repository.setForeground(true)
        elapsed = 300_000L
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES); settle()
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
        assertEquals(0, drivers.getValue(Platform.NETEASE).verifies)
        assertEquals(AccountVerification(), repository.verification.value.getValue(Platform.NETEASE))
    }

    @Test fun expiredAndRiskFailuresNeverTriggerAutomaticRecovery() {
        drivers.getValue(Platform.NETEASE).failure = platformError(Platform.NETEASE, "expired")
        drivers.getValue(Platform.QQ).failure = platformError(Platform.QQ, "risk")
        start(); repository.setForeground(true)
        elapsed = 300_000L
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES); settle()
        assertEquals(1, drivers.getValue(Platform.NETEASE).verifies)
        assertEquals(1, drivers.getValue(Platform.QQ).verifies)
        assertEquals(AccountPhase.REAUTH_REQUIRED, state().phase)
        assertEquals(AccountError.PLATFORM, state(Platform.QQ).error)
    }

    @Test fun logoutWhileProfileRequestCompletesRejectsLateResultAndClearsDiagnostics() {
        start()
        var result: Result<Unit>? = null
        drivers.getValue(Platform.NETEASE).onVerify = { repository.disconnect(Platform.NETEASE) }
        repository.refreshProfile(Platform.NETEASE) { result = it }
        settle()
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
        assertTrue(result!!.isFailure)
        assertEquals(AccountVerification(), repository.verification.value.getValue(Platform.NETEASE))
    }

    @Test fun queuedSuccessfulProfileCallbackCannotSucceedAfterLogout() {
        start()
        var result: Result<Unit>? = null
        repository.refreshProfile(Platform.NETEASE) { result = it }
        workers() // success is waiting on the main looper
        repository.disconnect(Platform.NETEASE)
        settle()
        assertTrue(result!!.isFailure)
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
    }

    private fun platformError(platform: Platform, category: String): Exception {
        val name = when (platform) {
            Platform.NETEASE -> "tech.musxi.neteaseloginpoc.NeteaseApi\$ApiException"
            Platform.QQ -> "tech.musxi.qqloginpoc.QqMusicApi\$ApiException"
            Platform.KUGOU -> "tech.musxi.loginpoc.KugouApi\$ApiException"
        }
        return Class.forName(name).getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(category, "fixture-private-text") as Exception
    }
}
