package tech.musxi.accounts

import android.app.Activity
import tech.musxi.neteaseloginpoc.WebLoginWindow

/** Activity-owned browser surface. Cookie values never cross into product UI callbacks. */
class NeteaseWebLogin private constructor() {
    private var window: WebLoginWindow? = null
    fun close() { window?.close(); window = null }

    companion object {
        fun open(activity: Activity, accounts: AccountRepository,
            environmentChecked: (Boolean) -> Unit = {}, closed: () -> Unit = {}): NeteaseWebLogin? {
            val token = accounts.beginWebLogin() ?: return null
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
                })
            } catch (_: Exception) { accounts.cancelWebLogin(token); closed(); return null }
            return browser
        }
    }
}
