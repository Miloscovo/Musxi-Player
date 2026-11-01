package tech.musxi.core.account

enum class Platform { KUGOU, QQ, NETEASE }

enum class AccountPhase {
    DISCONNECTED, PREPARING, WAITING_CONFIRMATION, VERIFYING, CONNECTED,
    REVALIDATING, RETRYABLE_ERROR, REAUTH_REQUIRED
}

enum class AccountError { NETWORK, EXPIRED, REJECTED, TIMEOUT, PLATFORM, STORAGE, OPEN_FAILED }

enum class MembershipLevel { YES, NO, UNKNOWN }
data class Membership(val level: MembershipLevel = MembershipLevel.UNKNOWN,
    val name: String? = null, val expiresAtEpochMs: Long? = null)

/**
 * UI identity only. Credentials and login URLs must never enter account state. [avatarUrl] is the
 * platform's picture of the account (https, avatar-policy host), null until a platform
 * supplies it; the UI then draws the name's first character.
 */
data class AccountProfile(
    val platform: Platform,
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null,
) {
    override fun toString(): String = "AccountProfile(platform=$platform, identity=<redacted>)"
}

data class AccountState(
    val platform: Platform,
    val phase: AccountPhase,
    val profile: AccountProfile? = null,
    val error: AccountError? = null,
    val generation: Long = 0,
    val membership: Membership = Membership(),
)
