package tech.musxi.accounts

import android.content.Intent
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/** Real repository with independently blocked drivers; no credentials or platform requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccountExecutionIsolationTest {
    private lateinit var library: ScheduledExecutorService
    private lateinit var lanes: Map<Platform, ScheduledExecutorService>
    private lateinit var memberships: Map<Platform, ExecutorService>
    private lateinit var drivers: Map<Platform, Driver>
    private lateinit var repository: AccountRepository
    private val releases = mutableListOf<CountDownLatch>()

    private class Driver : PlatformDriver {
        val original = Any()
        @Volatile var saved: Any? = original
        val verifies = AtomicInteger()
        val saves = AtomicInteger()
        val polls = AtomicInteger()
        @Volatile var verification: (Any) -> Any = { it }
        @Volatile var onSave: () -> Unit = {}
        @Volatile var polling: () -> PollResult = { PollResult.Waiting }
        @Volatile var membershipRead: () -> Membership = { Membership() }
        override fun load(): Any? = saved
        override fun verify(session: Any): Any { verifies.incrementAndGet(); return verification(session) }
        override fun save(session: Any) { onSave(); saves.incrementAndGet(); saved = session }
        override fun clear() { saved = null }
        override fun identity(session: Any) = Identity("42", "Fixture")
        override fun membership(session: Any) = membershipRead()
        override fun create() = LoginRequest(Any(), Intent(Intent.ACTION_VIEW))
        override fun poll(request: LoginRequest): PollResult { polls.incrementAndGet(); return polling() }
    }

    @Before fun prepare() {
        library = Executors.newSingleThreadScheduledExecutor()
        lanes = Platform.entries.associateWith { Executors.newSingleThreadScheduledExecutor() }
        memberships = Platform.entries.associateWith { Executors.newSingleThreadExecutor() }
        drivers = Platform.entries.associateWith { Driver() }
    }

    private fun start(wait: Boolean = true) {
        repository = AccountRepository(RuntimeEnvironment.getApplication(), drivers, library, lanes, memberships,
            observeNetwork = false, browserLogout = { it(true) })
        if (wait) settle()
    }

    @After fun finish() {
        releases.forEach { it.countDown() }
        if (::repository.isInitialized) repository.setForeground(false)
        val executors = lanes.values + memberships.values + library
        executors.forEach { it.shutdownNow() }
        executors.forEach { it.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun workers(platform: Platform? = null) {
        repeat(3) {
            (if (platform == null) lanes.values else listOf(lanes.getValue(platform))).forEach {
                it.submit {}.get(3, TimeUnit.SECONDS)
            }
        }
    }
    private fun settle() { workers(); shadowOf(Looper.getMainLooper()).idle(); workers() }
    private fun state(platform: Platform) = repository.states.value.getValue(platform)
    private fun latch() = CountDownLatch(1).also { releases += it }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }

    @Test fun slowStartupVerificationDoesNotBlockOtherPlatformsOrIdentityPublication() {
        val entered = latch(); val release = latch()
        drivers.getValue(Platform.NETEASE).verification = { entered.countDown(); await(release); it }
        start(wait = false); await(entered)
        workers(Platform.QQ); workers(Platform.KUGOU)
        assertEquals(AccountPhase.CONNECTED, state(Platform.QQ).phase)
        assertEquals(AccountPhase.CONNECTED, state(Platform.KUGOU).phase)
        assertEquals(AccountPhase.REVALIDATING, state(Platform.NETEASE).phase)
        assertNotNull(state(Platform.NETEASE).profile)
        repository.disconnect(Platform.QQ); workers(Platform.QQ)
        assertEquals(AccountPhase.DISCONNECTED, state(Platform.QQ).phase)
        release.countDown(); settle()
        assertEquals(AccountPhase.CONNECTED, state(Platform.NETEASE).phase)
    }

    @Test fun occupiedLibraryLaneDoesNotBlockAccountRefresh() {
        start()
        val entered = latch(); val release = latch()
        library.execute { entered.countDown(); await(release) }; await(entered)
        var result: Result<Unit>? = null
        repository.refreshProfile(Platform.QQ) { result = it }; settle()
        assertTrue(result!!.isSuccess)
        assertEquals(2, drivers.getValue(Platform.QQ).verifies.get())
    }

    @Test fun overlappingProfileRefreshesShareOneVerificationAndDeliverEveryCallbackOnce() {
        start()
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.NETEASE)
        driver.verification = { entered.countDown(); await(release); it }
        val results = mutableListOf<Result<Unit>>()
        repository.refreshProfile(Platform.NETEASE) { results += it }; await(entered)
        repeat(9) { repository.refreshProfile(Platform.NETEASE) { results += it } }
        release.countDown(); settle()
        assertEquals(2, driver.verifies.get())
        assertEquals(10, results.size)
        assertTrue(results.all { it.isSuccess })
        repository.refreshProfile(Platform.NETEASE) { results += it }; settle()
        assertEquals(3, driver.verifies.get())
        assertEquals(11, results.size)
    }

    @Test fun profileTimeoutDeliversOnceAndLateSessionCannotBeSavedOrClearFailure() {
        start()
        val entered = latch(); val release = latch(); val replacement = Any()
        val driver = drivers.getValue(Platform.NETEASE)
        driver.verification = { entered.countDown(); await(release); replacement }
        val results = mutableListOf<Result<Unit>>()
        repository.refreshProfile(Platform.NETEASE) { results += it }; await(entered)
        shadowOf(Looper.getMainLooper()).idleFor(45, TimeUnit.SECONDS)
        assertEquals(1, results.size); assertTrue(results.single().isFailure)
        assertEquals(AccountPhase.CONNECTED, state(Platform.NETEASE).phase)
        assertEquals(AccountError.NETWORK, repository.verification.value.getValue(Platform.NETEASE).lastFailure)
        repeat(10) { repository.refreshProfile(Platform.NETEASE) {} }
        shadowOf(Looper.getMainLooper()).idle()
        release.countDown(); settle()
        assertEquals(2, driver.verifies.get())
        assertEquals(1, driver.saves.get())
        assertSame(driver.original, driver.saved)
        assertEquals(1, results.size)
        assertEquals(AccountError.NETWORK, repository.verification.value.getValue(Platform.NETEASE).lastFailure)
    }

    @Test fun startupDeadlineRetiresBlockedVerifierAndRejectsLateSuccess() {
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.NETEASE)
        driver.verification = { entered.countDown(); await(release); Any() }
        start(wait = false); await(entered)
        shadowOf(Looper.getMainLooper()).idleFor(45, TimeUnit.SECONDS)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state(Platform.NETEASE).phase)
        assertEquals(AccountError.NETWORK, state(Platform.NETEASE).error)
        release.countDown(); settle()
        assertEquals(0, driver.saves.get())
        assertSame(driver.original, driver.saved)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state(Platform.NETEASE).phase)
    }

    @Test fun logoutRetiresBlockedRefreshImmediatelyAndDoesNotResurrectPrivateStorage() {
        start()
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.NETEASE)
        driver.verification = { entered.countDown(); await(release); Any() }
        var result: Result<Unit>? = null
        repository.refreshProfile(Platform.NETEASE) { result = it }; await(entered)
        repository.disconnect(Platform.NETEASE)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result!!.isFailure)
        assertEquals(AccountPhase.PREPARING, state(Platform.NETEASE).phase)
        release.countDown(); settle()
        assertEquals(AccountPhase.DISCONNECTED, state(Platform.NETEASE).phase)
        assertNull(driver.saved)
        assertEquals(1, driver.saves.get())
        assertEquals(AccountVerification(), repository.verification.value.getValue(Platform.NETEASE))
    }

    @Test fun cancelledOldLoginCannotOverwriteAReplacementAttempt() {
        drivers.values.forEach { it.saved = null }
        start(); repository.setForeground(true)
        repository.connectOfficial(Platform.QQ) {}; settle()
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.QQ)
        driver.polling = { entered.countDown(); await(release); PollResult.Complete(Any()) }
        repository.checkNow(Platform.QQ); await(entered)
        repository.cancel(Platform.QQ)
        repository.connectOfficial(Platform.QQ) {}
        val replacementGeneration = state(Platform.QQ).generation
        release.countDown(); settle()
        assertEquals(replacementGeneration, state(Platform.QQ).generation)
        assertEquals(AccountPhase.WAITING_CONFIRMATION, state(Platform.QQ).phase)
        assertEquals(0, driver.saves.get())
        driver.polling = { PollResult.Waiting }
        repository.checkNow(Platform.QQ); settle()
        assertEquals(2, driver.polls.get())
    }

    @Test fun repeatedCheckNowWhilePollingDoesNotQueueDuplicatePolls() {
        drivers.values.forEach { it.saved = null }
        start(); repository.setForeground(true)
        repository.connectOfficial(Platform.QQ) {}; settle()
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.QQ)
        driver.polling = { entered.countDown(); await(release); PollResult.Waiting }
        repository.checkNow(Platform.QQ); await(entered)
        repeat(20) { repository.checkNow(Platform.QQ) }
        release.countDown(); settle()
        assertEquals(1, driver.polls.get())
    }

    @Test fun slowMembershipDoesNotBlockOtherPlatformMembershipOrAccountVerification() {
        start()
        memberships.values.forEach { it.submit {}.get(3, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
        val entered = latch(); val release = latch()
        drivers.getValue(Platform.NETEASE).membershipRead = { entered.countDown(); await(release); Membership() }
        repository.refreshMembership(Platform.NETEASE); await(entered)
        drivers.getValue(Platform.QQ).membershipRead = { Membership(MembershipLevel.YES, "Fixture") }
        repository.refreshMembership(Platform.QQ)
        memberships.getValue(Platform.QQ).submit {}.get(3, TimeUnit.SECONDS)
        repository.refreshProfile(Platform.QQ); workers(Platform.QQ)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MembershipLevel.YES, state(Platform.QQ).membership.level)
        assertEquals(2, drivers.getValue(Platform.QQ).verifies.get())
    }

    @Test fun slowPlatformStorageDoesNotHoldTheGlobalAccountGate() {
        start()
        val entered = latch(); val release = latch()
        drivers.getValue(Platform.NETEASE).onSave = { entered.countDown(); await(release) }
        repository.refreshProfile(Platform.NETEASE); await(entered)
        repository.disconnect(Platform.QQ); workers(Platform.QQ)
        assertEquals(AccountPhase.DISCONNECTED, state(Platform.QQ).phase)
        repository.refreshProfile(Platform.KUGOU); workers(Platform.KUGOU)
        assertEquals(2, drivers.getValue(Platform.KUGOU).verifies.get())
    }

    @Test fun overlappingRefreshWaitersAreBoundedWithoutSendingMoreRequests() {
        start()
        val entered = latch(); val release = latch()
        val driver = drivers.getValue(Platform.NETEASE)
        driver.verification = { entered.countDown(); await(release); it }
        val results = mutableListOf<Result<Unit>>()
        repository.refreshProfile(Platform.NETEASE) { results += it }; await(entered)
        repeat(69) { repository.refreshProfile(Platform.NETEASE) { results += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, results.size)
        assertTrue(results.all { it.isFailure })
        release.countDown(); settle()
        assertEquals(70, results.size)
        assertEquals(64, results.count { it.isSuccess })
        assertEquals(2, driver.verifies.get())
    }
}
