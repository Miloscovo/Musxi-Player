package tech.musxi.core.account

enum class Platform { KUGOU, QQ, NETEASE }

enum class AccountPhase {
    DISCONNECTED, PREPARING, WAITING_CONFIRMATION, VERIFYING, CONNECTED,
    REVALIDATING, RETRYABLE_ERROR, REAUTH_REQUIRED
}

enum class AccountError { NETWORK, EXPIRED, REJECTED, TIMEOUT, PLATFORM, STORAGE, OPEN_FAILED }

/** UI identity only. Credentials and login URLs must never enter account state. */
data class AccountProfile(val platform: Platform, val id: String, val displayName: String) {
    override fun toString(): String = "AccountProfile(platform=$platform, identity=<redacted>)"
}

data class AccountState(
    val platform: Platform,
    val phase: AccountPhase,
    val profile: AccountProfile? = null,
    val error: AccountError? = null,
    val generation: Long = 0
)
