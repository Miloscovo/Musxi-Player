package tech.musxi.accounts

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.neteaseloginpoc.NeteaseApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-owned login execution. Activities receive state, never platform credentials. */
class AccountRepository(context: Context) {
    private val app = context.applicationContext
    private val coordinator = AccountCoordinator()
    val states: StateFlow<Map<Platform, AccountState>> = coordinator.states
    private val gate = Any()
    private val queue = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val libraryCaches: Map<Platform, LibraryPageStore> = mapOf(
        Platform.NETEASE to FileLibraryPageStore(java.io.File(app.noBackupFilesDir, "library-pages-v1")),
        Platform.KUGOU to FileLibraryPageStore(java.io.File(app.noBackupFilesDir, "kugou-library-pages-v1"), Platform.KUGOU),
        Platform.QQ to FileLibraryPageStore(java.io.File(app.noBackupFilesDir, "qq-library-pages-v1"), Platform.QQ),
    )
    private val cacheQueue = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val temporaryImage = TemporaryLoginImage(app)
    private val drivers by lazy {
        mapOf(Platform.KUGOU to KugouDriver(app), Platform.QQ to QqDriver(app),
            Platform.NETEASE to NeteaseDriver(app))
    }
    private val sessions = mutableMapOf<Platform, Any>() // worker queue only
    private class Pending(val token: Long, val request: LoginRequest, val deadline: Long)
    private val pending = mutableMapOf<Platform, Pending>() // worker queue only
    private val pollTasks = mutableMapOf<Platform, Pair<Long, java.util.concurrent.ScheduledFuture<*>>>()
    @Volatile private var foreground = false

    init {
        val tokens = Platform.entries.associateWith { coordinator.restore(it, null) }
        queue.execute {
            runCatching { temporaryImage.clear() }
            // Publish every saved identity before any platform's blocking network verifier.
            val verifiers = tokens.mapNotNull { (platform, token) -> loadRestore(platform, token) }
            verifiers.forEach { queue.execute(it) }
        }
    }

    fun restore(platform: Platform) {
        val initial = synchronized(gate) {
            if (states.value.getValue(platform).phase in activePhases) return
            coordinator.restore(platform, null)
        }
        queue.execute { loadRestore(platform, initial)?.let { queue.execute(it) } }
    }

    private fun loadRestore(platform: Platform, initial: Long): (() -> Unit)? {
        var token = initial
        return try {
            val driver = drivers.getValue(platform)
            val saved = driver.load()
            if (!coordinator.isCurrent(platform, token)) return null
            if (saved == null) clearLibraryCache(platform)
            synchronized(gate) {
                if (!coordinator.isCurrent(platform, token)) return null
                if (saved == null) {
                    sessions.remove(platform); coordinator.disconnect(platform); return null
                }
                sessions[platform] = saved
                token = coordinator.restore(platform, profile(platform, driver.identity(saved)))
            }
            val existing = requireNotNull(saved)
            val verify: () -> Unit = verify@{
                if (!coordinator.isCurrent(platform, token)) return@verify
                try {
                    val verified = driver.verify(existing)
                    if (!coordinator.isCurrent(platform, token)) return@verify
                    if (driver.identity(existing).id != driver.identity(verified).id)
                        clearLibraryCache(platform)
                    synchronized(gate) {
                        if (!coordinator.isCurrent(platform, token)) return@verify
                        sessions[platform] = verified
                        coordinator.connected(platform, token, profile(platform, driver.identity(verified)))
                    }
                } catch (failure: Exception) { fail(platform, token, failure) }
            }
            verify
        } catch (failure: Exception) { fail(platform, token, failure); null }
    }

    /** Launch callback must be invoked by the currently visible Activity; URI stays out of state. */
    fun connectOfficial(platform: Platform, launch: (Intent) -> Unit) {
        require(platform != Platform.NETEASE)
        val token = begin(platform) ?: return
        queue.execute {
            try {
                val request = drivers.getValue(platform).create()
                synchronized(gate) {
                    if (!coordinator.isCurrent(platform, token)) return@execute
                    pending[platform] = Pending(token, request, SystemClock.elapsedRealtime() + ATTEMPT_MS)
                    coordinator.awaiting(platform, token)
                }
                main.post {
                    if (!coordinator.isCurrent(platform, token)) return@post
                    if (!foreground) { cancel(platform); return@post }
                    try { launch(requireNotNull(request.launch)) }
                    catch (_: Exception) { fail(platform, token, AccountError.OPEN_FAILED) }
                }
                schedulePoll(platform, token)
            } catch (failure: Exception) { fail(platform, token, failure) }
        }
    }

    /** One browser attempt at a time. Cookie data is forwarded directly into the platform module. */
    internal fun beginWebLogin(): Long? = begin(Platform.NETEASE)?.also {
        val token = it
        queue.execute { if (coordinator.isCurrent(Platform.NETEASE, token)) runCatching { temporaryImage.clear() } }
        coordinator.awaiting(Platform.NETEASE, it)
    }

    fun connectNeteaseScanner(launch: (Intent) -> Unit) {
        val platform = Platform.NETEASE
        val token = begin(platform) ?: return
        queue.execute {
            try {
                val request = drivers.getValue(platform).create()
                if (!coordinator.isCurrent(platform, token)) return@execute
                temporaryImage.save((request.opaque as NeteaseApi.Attempt).authorizationUrl, token)
                synchronized(gate) {
                    if (!coordinator.isCurrent(platform, token)) { temporaryImage.clear(token); return@execute }
                    pending[platform] = Pending(token, request, SystemClock.elapsedRealtime() + ATTEMPT_MS)
                    coordinator.awaiting(platform, token)
                }
                main.post {
                    if (!coordinator.isCurrent(platform, token)) return@post
                    if (!foreground) { cancel(platform); return@post }
                    try { launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("orpheus://nm/base/scan"))
                        .setPackage("com.netease.cloudmusic")) }
                    catch (_: Exception) { fail(platform, token, AccountError.OPEN_FAILED) }
                }
                schedulePoll(platform, token)
            } catch (failure: Exception) { fail(platform, token, failure) }
        }
    }

    internal fun completeWebLogin(token: Long, header: String) {
        if (!coordinator.verifying(Platform.NETEASE, token)) return
        queue.execute {
            try {
                val session = NeteaseApi.fromBrowserCookies(header)
                finish(Platform.NETEASE, token, session, null)
            } catch (failure: Exception) { fail(Platform.NETEASE, token, failure) }
        }
    }

    internal fun cancelWebLogin(token: Long) {
        synchronized(gate) {
            if (coordinator.isCurrent(Platform.NETEASE, token)) cancel(Platform.NETEASE)
        }
    }

    fun cancel(platform: Platform) {
        val cancelled = synchronized(gate) {
            val old = states.value.getValue(platform).generation
            coordinator.cancel(platform)
            old
        }
        queue.execute {
            if (pending[platform]?.token == cancelled) pending.remove(platform)
            if (pollTasks[platform]?.first == cancelled) pollTasks.remove(platform)?.second?.cancel(false)
            if (platform == Platform.NETEASE) runCatching { temporaryImage.clear(cancelled) }
        }
    }

    /** Report disconnected only once private storage cleanup has succeeded. */
    fun disconnect(platform: Platform) {
        val token = synchronized(gate) { coordinator.cancel(platform); coordinator.begin(platform) }
        queue.execute {
            try {
                if (!coordinator.isCurrent(platform, token)) return@execute
                clearLibraryCache(platform)
                synchronized(gate) {
                    if (!coordinator.isCurrent(platform, token)) return@execute
                    drivers.getValue(platform).clear()
                    if (platform == Platform.NETEASE) temporaryImage.clear()
                    sessions.remove(platform)
                    pending.remove(platform)
                    coordinator.disconnect(platform)
                }
            } catch (_: Exception) { fail(platform, token, AccountError.STORAGE) }
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) queue.execute {
            pending.toMap().forEach { (platform, attempt) -> poll(platform, attempt.token) }
        }
    }

    fun checkNow(platform: Platform) { queue.execute { pending[platform]?.let { poll(platform, it.token) } } }

    private fun begin(platform: Platform): Long? = synchronized(gate) {
        val state = states.value.getValue(platform)
        if (state.phase in activePhases || state.phase == AccountPhase.CONNECTED || state.profile != null) return null
        coordinator.begin(platform)
    }

    private fun schedulePoll(platform: Platform, token: Long) {
        pollTasks.remove(platform)?.second?.cancel(false)
        pollTasks[platform] = token to queue.schedule({ poll(platform, token) }, 2500, TimeUnit.MILLISECONDS)
    }

    private fun poll(platform: Platform, token: Long) {
        if (!coordinator.isCurrent(platform, token)) return
        if (pollTasks[platform]?.first == token) pollTasks.remove(platform)?.second?.cancel(false)
        val attempt = pending[platform]?.takeIf { it.token == token } ?: return
        if (SystemClock.elapsedRealtime() >= attempt.deadline) {
            pending.remove(platform)
            fail(platform, token, AccountError.TIMEOUT)
            return
        }
        if (!foreground) return
        try {
            when (val result = drivers.getValue(platform).poll(attempt.request)) {
                is PollResult.Complete -> {
                    if (coordinator.verifying(platform, token)) finish(platform, token, result.session, attempt.deadline)
                    pending.remove(platform)
                }
                PollResult.Waiting -> schedulePoll(platform, token)
                PollResult.Expired -> { pending.remove(platform); fail(platform, token, AccountError.TIMEOUT) }
                PollResult.Refused -> { pending.remove(platform); fail(platform, token, AccountError.REJECTED) }
            }
        } catch (failure: Exception) {
            // No indefinite automatic retries on security/protocol failures.
            pending.remove(platform)
            fail(platform, token, failure)
        }
    }

    private fun finish(platform: Platform, token: Long, session: Any, deadline: Long?) {
        if (!coordinator.isCurrent(platform, token)) return
        val driver = drivers.getValue(platform)
        val verified = driver.verify(session)
        synchronized(gate) {
            if (!coordinator.isCurrent(platform, token)) return
            if (deadline != null && SystemClock.elapsedRealtime() >= deadline) {
                fail(platform, token, AccountError.TIMEOUT); return
            }
            driver.save(verified)
            sessions[platform] = verified
            coordinator.connected(platform, token, profile(platform, driver.identity(verified)))
            if (platform == Platform.NETEASE) runCatching { temporaryImage.clear(token) }
        }
    }

    /** Library executes on the same queue as account changes, avoiding a cross-account response race. */
    internal fun <T> withNeteaseSession(work: (NeteaseApi, NeteaseApi.Session) -> T, result: (Result<T>) -> Unit) {
        val expected = states.value.getValue(Platform.NETEASE)
        queue.execute {
            val response = runCatching {
                val state = states.value.getValue(Platform.NETEASE)
                if (state.phase != AccountPhase.CONNECTED || state.generation != expected.generation) throw LibraryUnavailable()
                val session = sessions[Platform.NETEASE] as? NeteaseApi.Session ?: throw LibraryUnavailable()
                val value = work((drivers.getValue(Platform.NETEASE) as NeteaseDriver).api, session)
                val latest = states.value.getValue(Platform.NETEASE)
                if (latest.generation != expected.generation || latest.phase != AccountPhase.CONNECTED) throw LibraryUnavailable()
                value
            }
            val failure = response.exceptionOrNull()
            var expirationGeneration: Long? = null
            if (failure is NeteaseApi.ApiException && failure.code == "expired") {
                synchronized(gate) {
                    if (coordinator.invalidate(Platform.NETEASE, expected.generation))
                        expirationGeneration = states.value.getValue(Platform.NETEASE).generation
                }
            }
            main.post {
                val latest = states.value.getValue(Platform.NETEASE)
                if (expirationGeneration != null && latest.generation == expirationGeneration
                    && latest.phase == AccountPhase.REAUTH_REQUIRED) result(response)
                else if (latest.generation != expected.generation || latest.phase != AccountPhase.CONNECTED)
                    result(Result.failure(LibraryUnavailable()))
                else result(response)
            }
        }
    }

    internal fun isNeteaseGenerationConnected(generation: Long): Boolean {
        val state = states.value.getValue(Platform.NETEASE)
        return state.phase == AccountPhase.CONNECTED && state.generation == generation
    }

    internal fun <T> withQqSession(work: (tech.musxi.qqloginpoc.QqMusicApi, tech.musxi.qqloginpoc.QqMusicApi.Session) -> T, result: (Result<T>) -> Unit) {
        val expected = states.value.getValue(Platform.QQ)
        queue.execute {
            val response = runCatching {
                val state = states.value.getValue(Platform.QQ)
                if (state.phase != AccountPhase.CONNECTED || state.generation != expected.generation) throw LibraryUnavailable()
                val session = sessions[Platform.QQ] as? tech.musxi.qqloginpoc.QqMusicApi.Session ?: throw LibraryUnavailable()
                val value = work((drivers.getValue(Platform.QQ) as QqDriver).api, session)
                val latest = states.value.getValue(Platform.QQ)
                if (latest.generation != expected.generation || latest.phase != AccountPhase.CONNECTED) throw LibraryUnavailable()
                value
            }
            val failure = response.exceptionOrNull()
            var expirationGeneration: Long? = null
            if (failure is tech.musxi.qqloginpoc.QqMusicApi.ApiException && failure.code == "expired") {
                synchronized(gate) {
                    if (coordinator.invalidate(Platform.QQ, expected.generation))
                        expirationGeneration = states.value.getValue(Platform.QQ).generation
                }
            }
            main.post {
                val latest = states.value.getValue(Platform.QQ)
                if (expirationGeneration != null && latest.generation == expirationGeneration
                    && latest.phase == AccountPhase.REAUTH_REQUIRED) result(response)
                else if (latest.generation != expected.generation || latest.phase != AccountPhase.CONNECTED)
                    result(Result.failure(LibraryUnavailable()))
                else result(response)
            }
        }
    }

    internal fun isQqGenerationConnected(generation: Long): Boolean {
        val state = states.value.getValue(Platform.QQ)
        return state.phase == AccountPhase.CONNECTED && state.generation == generation
    }

    internal fun <T> withKugouSession(work: (tech.musxi.loginpoc.KugouApi, tech.musxi.loginpoc.KugouApi.Session) -> T,
        result: (Result<T>) -> Unit) {
        val expected = states.value.getValue(Platform.KUGOU).generation
        queue.execute {
            val response = runCatching {
                if (!isKugouGenerationConnected(expected)) throw LibraryUnavailable()
                val session = sessions[Platform.KUGOU] as? tech.musxi.loginpoc.KugouApi.Session ?: throw LibraryUnavailable()
                val value = work((drivers.getValue(Platform.KUGOU) as KugouDriver).api, session)
                if (!isKugouGenerationConnected(expected)) throw LibraryUnavailable()
                value
            }
            main.post {
                if (!isKugouGenerationConnected(expected)) result(Result.failure(LibraryUnavailable()))
                else result(response)
            }
        }
    }

    internal fun isKugouGenerationConnected(generation: Long): Boolean {
        val state = states.value.getValue(Platform.KUGOU)
        return state.phase == AccountPhase.CONNECTED && state.generation == generation
    }

    /** Independent disk executor permits cached display while restore is waiting on the network. */
    fun createLibraryBrowser(fetch: LibraryPageFetch, platform: Platform = Platform.NETEASE): LibraryBrowser {
        val store = libraryCaches.getValue(platform)
        val browser = LibraryBrowser({ libraryCacheIdentity(platform) }, { expected, action ->
            // All disk work and erasure share cacheQueue. Never hold the account/UI lock here.
            if (!expected.sameAttempt(libraryCacheIdentity(platform))) throw LibraryPageFailure("account_changed")
            action(store)
            if (!expected.sameAttempt(libraryCacheIdentity(platform))) throw LibraryPageFailure("account_changed")
        }, fetch, { task -> cacheQueue.execute(task) }, { task -> main.post(task) }, platform = platform)
        scope.launch {
            states.map { it.getValue(platform) }.distinctUntilChanged().collect { browser.accountChanged() }
        }
        return browser
    }

    /** Only the account worker waits; cacheQueue never waits for the account worker or UI. */
    private fun clearLibraryCache(platform: Platform) {
        val store = libraryCaches[platform] ?: return
        cacheQueue.submit { store.clear() }.get()
    }

    private fun libraryCacheIdentity(platform: Platform): LibraryCacheIdentity? {
        val state = states.value.getValue(platform)
        val profile = state.profile ?: return null
        if (state.phase !in setOf(AccountPhase.CONNECTED, AccountPhase.REVALIDATING,
                AccountPhase.RETRYABLE_ERROR, AccountPhase.REAUTH_REQUIRED)) return null
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest((platform.name + "\u0000" + profile.id).toByteArray(Charsets.UTF_8))
        val owner = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        return LibraryCacheIdentity(owner, state.generation, state.phase == AccountPhase.CONNECTED)
    }

    private class LibraryUnavailable : java.io.IOException()
    private fun profile(platform: Platform, identity: Identity) = AccountProfile(platform, identity.id,
        identity.name.filterNot { it.isISOControl() }.take(80))

    private fun fail(platform: Platform, token: Long, error: AccountError) {
        synchronized(gate) { coordinator.fail(platform, token, error) }
        queue.execute {
            if (pending[platform]?.token == token) pending.remove(platform)
            if (pollTasks[platform]?.first == token) pollTasks.remove(platform)?.second?.cancel(false)
            if (platform == Platform.NETEASE) runCatching { temporaryImage.clear(token) }
        }
    }
    private fun fail(platform: Platform, token: Long, error: Exception) = fail(platform, token,
        when (safeErrorCode(error)) {
            "network", "http", "timeout" -> AccountError.NETWORK
            "expired", "credentials" -> AccountError.EXPIRED
            "storage" -> AccountError.STORAGE
            else -> AccountError.PLATFORM
        })

    private companion object {
        const val ATTEMPT_MS = 180_000L
        val activePhases = setOf(AccountPhase.PREPARING, AccountPhase.WAITING_CONFIRMATION,
            AccountPhase.VERIFYING, AccountPhase.REVALIDATING)
    }
}
