package tech.musxi.accounts

import android.app.Activity
import android.view.ViewGroup
import tech.musxi.neteaseloginpoc.WebLoginHost
import tech.musxi.neteaseloginpoc.WebLoginWindow

/** High-level browser state exposed to UI without leaking any cookie or raw URL details. */
enum class NeteaseWebStage {
    PREPARING,
    LOADING,
    READY,
    COOKIE_UNAVAILABLE,
    CLEANUP_FAILED,
    NAVIGATION_BLOCKED,
    SSL_ERROR,
    LOAD_ERROR,
    COOKIES_MISSING,
}

data class NeteaseWebUiState(
    val stage: NeteaseWebStage = NeteaseWebStage.PREPARING,
    val host: String? = null,
    val progress: Int = 0,
    val canSubmit: Boolean = false,
    val canReload: Boolean = false,
    val canGoBack: Boolean = false,
)

/**
 * Activity-scoped browser session designed to be hosted inside Musxi's Compose UI.
 * Cookie values stay strictly inside `platform-netease` / `accounts` and never reach UI callbacks.
 */
class NeteaseWebLoginSession internal constructor(
    private val accounts: AccountRepository,
    private val token: Long,
    val reauthenticate: Boolean,
    private val environmentChecked: (Boolean) -> Unit,
    private val closed: () -> Unit,
) {
    private var host: WebLoginHost? = null
    private var finished = false
    private var submitted = false

    fun attach(activity: Activity, webSlot: ViewGroup, onState: (NeteaseWebUiState) -> Unit) {
        if (finished || host != null) return
        try {
            host = WebLoginHost(activity, webSlot, object : WebLoginHost.Listener {
                override fun stateChanged(
                    stage: WebLoginHost.Stage,
                    host: String?,
                    progress: Int,
                    canSubmit: Boolean,
                    canReload: Boolean,
                    canGoBack: Boolean,
                ) {
                    if (!finished) {
                        onState(
                            NeteaseWebUiState(
                                stage = stage.toUiStage(),
                                host = host,
                                progress = progress,
                                canSubmit = canSubmit,
                                canReload = canReload,
                                canGoBack = canGoBack,
                            ),
                        )
                    }
                }

                override fun ready(firstParty: Boolean, thirdParty: Boolean) {
                    environmentChecked(firstParty && thirdParty)
                }

                override fun cookies(header: String) {
                    if (finished) return
                    submitted = true
                    finished = true
                    this@NeteaseWebLoginSession.host = null
                    accounts.completeWebLogin(token, header)
                    closed()
                }

                override fun cancelled() {
                    if (finished) return
                    finished = true
                    this@NeteaseWebLoginSession.host = null
                    if (!submitted) accounts.cancelWebLogin(token)
                    closed()
                }
            }, { accounts.isWebLoginCurrent(token) })
        } catch (_: Exception) {
            close()
        }
    }

    fun submit() {
        if (!finished) host?.submit()
    }

    fun reload() {
        if (!finished) host?.reload()
    }

    fun goBack(): Boolean = if (finished) false else host?.goBack() == true

    fun close() {
        if (finished) return
        val attached = host
        if (attached != null) {
            // Let WebLoginHost.Listener.cancelled() perform the single teardown.
            attached.close()
        } else {
            finished = true
            accounts.cancelWebLogin(token)
            closed()
        }
    }
}

private fun WebLoginHost.Stage.toUiStage(): NeteaseWebStage = when (this) {
    WebLoginHost.Stage.PREPARING -> NeteaseWebStage.PREPARING
    WebLoginHost.Stage.LOADING -> NeteaseWebStage.LOADING
    WebLoginHost.Stage.READY -> NeteaseWebStage.READY
    WebLoginHost.Stage.COOKIE_UNAVAILABLE -> NeteaseWebStage.COOKIE_UNAVAILABLE
    WebLoginHost.Stage.CLEANUP_FAILED -> NeteaseWebStage.CLEANUP_FAILED
    WebLoginHost.Stage.NAVIGATION_BLOCKED -> NeteaseWebStage.NAVIGATION_BLOCKED
    WebLoginHost.Stage.SSL_ERROR -> NeteaseWebStage.SSL_ERROR
    WebLoginHost.Stage.LOAD_ERROR -> NeteaseWebStage.LOAD_ERROR
    WebLoginHost.Stage.COOKIES_MISSING -> NeteaseWebStage.COOKIES_MISSING
}

/** Activity-owned browser surface. Cookie values never cross into product UI callbacks. */
class NeteaseWebLogin private constructor() {
    private var window: WebLoginWindow? = null
    fun close() { window?.close(); window = null }

    companion object {
        fun open(activity: Activity, accounts: AccountRepository,
            environmentChecked: (Boolean) -> Unit = {}, closed: () -> Unit = {}): NeteaseWebLogin? =
            openWindow(activity, accounts, false, environmentChecked, closed)

        fun reauthenticate(activity: Activity, accounts: AccountRepository,
            environmentChecked: (Boolean) -> Unit = {}, closed: () -> Unit = {}): NeteaseWebLogin? =
            openWindow(activity, accounts, true, environmentChecked, closed)

        fun startSession(
            accounts: AccountRepository,
            reauthenticate: Boolean = false,
            environmentChecked: (Boolean) -> Unit = {},
            closed: () -> Unit = {},
        ): NeteaseWebLoginSession? {
            val token = accounts.beginWebLogin(reauthenticate) ?: return null
            return NeteaseWebLoginSession(accounts, token, reauthenticate, environmentChecked, closed)
        }

        private fun openWindow(activity: Activity, accounts: AccountRepository, reauthenticate: Boolean,
            environmentChecked: (Boolean) -> Unit, closed: () -> Unit): NeteaseWebLogin? {
            val token = accounts.beginWebLogin(reauthenticate) ?: return null
            val browser = NeteaseWebLogin()
            try {
                browser.window = WebLoginWindow(activity, object : WebLoginWindow.Listener {
                    override fun ready(firstParty: Boolean, thirdParty: Boolean) = environmentChecked(firstParty && thirdParty)
                    override fun cookies(header: String) {
                        browser.window = null
                        accounts.completeWebLogin(token, header)
                        closed()
                    }
                    override fun cancelled() {
                        browser.window = null
                        accounts.cancelWebLogin(token)
                        closed()
                    }
                }, { accounts.isWebLoginCurrent(token) })
            } catch (_: Exception) { accounts.cancelWebLogin(token); closed(); return null }
            return browser
        }
    }
}
