package tech.musxi.player.ui.account

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.musxi.accounts.AccountRepository
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform

/** What the trailing slot of an account row offers. One action, never a menu. */
enum class AccountAction { CONNECT, CANCEL, RETRY, RELOGIN, MANAGE, NONE }

/**
 * Presentation of one platform. Derived from [AccountState] only; the repository stays the
 * single owner of account truth and this type is never written back to it.
 */
data class AccountRowUi(
    val platform: Platform,
    val phase: AccountPhase,
    val pill: String,
    /** Verified identity once known; null means we must fall back to [detail]. */
    val name: String?,
    /** One plain sentence, already free of anything the pill already says. */
    val detail: String?,
    val action: AccountAction,
    val actionLabel: String?,
    /** True while the platform is waiting on the user in another app; cancelling is expected. */
    val userBlocked: Boolean,
)

/** Everything the account screen needs, derived from the repository's state map. */
data class AccountUiState(
    val rows: List<AccountRowUi> = emptyList(),
    /** Set once per user gesture that needs an Activity to launch a login intent. */
    val launch: ((Intent) -> Unit)? = null,
)

/**
 * Maps [AccountState] to the account screen. It subscribes to the process-owned
 * [AccountRepository.states] and never stores a second copy of account truth.
 */
class AccountViewModel(private val accounts: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accounts.states.collect { states ->
                _state.value = _state.value.copy(
                    rows = Platform.entries.map { states.getValue(it).toRowUi() },
                )
            }
        }
    }

    /**
     * Connect or recover. Official browsers (Kugou/QQ) need an Activity to launch the login
     * intent; Netease scans through the installed app instead.
     */
    fun connect(platform: Platform, launch: (Intent) -> Unit) {
        when (platform) {
            Platform.NETEASE -> accounts.connectNeteaseScanner(launch)
            Platform.KUGOU, Platform.QQ -> accounts.connectOfficial(platform, launch)
        }
    }

    /** Both the explicit cancel and the reverse of a stuck prepare go through the same path. */
    fun cancel(platform: Platform) = accounts.cancel(platform)

    /**
     * Recover a saved account after a retryable failure. `checkNow` only re-polls an attempt that
     * still exists on the worker queue, and a failure has already removed it, so recovery must
     * go back through `restore`.
     */
    fun retry(platform: Platform) = accounts.restore(platform)

    /** An expired session cannot be refreshed; it has to be connected again. */
    fun relogin(platform: Platform) = accounts.disconnect(platform)

    fun disconnect(platform: Platform) = accounts.disconnect(platform)

    fun manage(platform: Platform) = accounts.restore(platform)
}

/** Pure mapping so every branch is testable without a repository or a running Activity. */
internal fun AccountState.toRowUi(): AccountRowUi = when (phase) {
    AccountPhase.DISCONNECTED -> row(
        pill = "未连接",
        name = null,
        detail = "连接后同步歌单与收藏",
        action = AccountAction.CONNECT,
        actionLabel = "连接",
    )

    // Waiting on the user in the platform's own app: the only useful action is to give up.
    AccountPhase.PREPARING, AccountPhase.WAITING_CONFIRMATION -> row(
        pill = "等待确认",
        name = null,
        detail = platform.waitHint(),
        action = AccountAction.CANCEL,
        actionLabel = "取消",
        userBlocked = true,
    )

    // Verifying is short and has nothing to cancel safely; keep the row quiet.
    AccountPhase.VERIFYING -> row(
        pill = "验证中",
        name = profile?.displayName,
        detail = if (profile == null) "正在确认登录状态" else null,
        action = AccountAction.NONE,
    )

    AccountPhase.CONNECTED -> row(
        pill = "已连接",
        name = profile?.displayName,
        detail = null,
        action = AccountAction.MANAGE,
        actionLabel = "管理",
    )

    AccountPhase.REVALIDATING -> row(
        pill = "验证中",
        name = profile?.displayName,
        detail = null,
        action = AccountAction.NONE,
    )

    AccountPhase.RETRYABLE_ERROR -> row(
        pill = "网络异常",
        name = profile?.displayName,
        detail = error.sentence(),
        action = AccountAction.RETRY,
        actionLabel = "重试",
    )

    AccountPhase.REAUTH_REQUIRED -> row(
        pill = "已过期",
        name = null,
        detail = "重新登录以恢复同步",
        action = AccountAction.RELOGIN,
        actionLabel = "重新登录",
    )
}

private fun AccountState.row(
    pill: String,
    name: String?,
    detail: String?,
    action: AccountAction,
    actionLabel: String? = null,
    userBlocked: Boolean = false,
) = AccountRowUi(
    platform = platform,
    phase = phase,
    pill = pill,
    name = name,
    detail = detail,
    action = action,
    actionLabel = actionLabel,
    userBlocked = userBlocked,
)

private fun Platform.waitHint(): String = when (this) {
    Platform.KUGOU -> "在酷狗里确认后返回"
    Platform.QQ -> "在 QQ 音乐里确认后返回"
    Platform.NETEASE -> "在网易云音乐里扫码确认后返回"
}

/**
 * Which row is allowed the one solid button.
 *
 * Lower is more urgent. An expired session outranks a disconnected platform, because that user was
 * already signed in and lost it; a transient error outranks a deliberate disconnect. "Connected" is
 * last: its action is the least urgent thing on the page.
 *
 * Both the screen and the design sheet call this, so the sheet cannot show a different answer from
 * the one the app ships — which it did briefly, because the sheet hard-coded "first row".
 */
fun solidActionPlatform(rows: List<AccountRowUi>): Platform? =
    rows.minByOrNull { urgency(it.phase) }?.platform

private fun urgency(phase: AccountPhase): Int = when (phase) {
    AccountPhase.REAUTH_REQUIRED -> 0
    AccountPhase.RETRYABLE_ERROR -> 1
    AccountPhase.DISCONNECTED -> 2
    AccountPhase.WAITING_CONFIRMATION, AccountPhase.PREPARING -> 3
    AccountPhase.VERIFYING, AccountPhase.REVALIDATING -> 4
    AccountPhase.CONNECTED -> 5
}
/**
 * Says whether the failure is temporary or terminal. A retryable error must read as "try again",
 * never as "your login broke". Kept short: the pill already names the failure, and a long
 * sentence here wraps to a stranded last word.
 */
private fun AccountError?.sentence(): String = when (this) {
    AccountError.NETWORK -> "暂时无法验证"
    AccountError.TIMEOUT -> "确认超时，可重试"
    AccountError.REJECTED -> "平台拒绝了这次登录"
    AccountError.OPEN_FAILED -> "没能打开发起登录的应用"
    AccountError.PLATFORM -> "平台暂无响应"
    AccountError.EXPIRED -> "需要重新登录"
    AccountError.STORAGE -> "本机保存失败"
    null -> "暂时无法验证"
}
