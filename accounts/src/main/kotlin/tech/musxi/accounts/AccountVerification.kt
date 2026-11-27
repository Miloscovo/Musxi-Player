package tech.musxi.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.Platform

/** Process-local verification facts; no identity, credentials or platform response text. */
data class AccountVerification(
    val lastVerifiedAtEpochMs: Long? = null,
    val lastFailure: AccountError? = null,
    val lastFailureAtEpochMs: Long? = null,
)

/** The repository calls this only under its account gate after checking the generation. */
internal class AccountVerificationHistory(private val now: () -> Long) {
    private val mutable = MutableStateFlow(Platform.entries.associateWith { AccountVerification() })
    val states = mutable.asStateFlow()

    fun succeeded(platform: Platform) {
        mutable.value = mutable.value + (platform to AccountVerification(lastVerifiedAtEpochMs = now()))
    }

    fun failed(platform: Platform, error: AccountError) {
        val previous = mutable.value.getValue(platform)
        mutable.value = mutable.value + (platform to previous.copy(lastFailure = error, lastFailureAtEpochMs = now()))
    }

    fun clear(platform: Platform) {
        mutable.value = mutable.value + (platform to AccountVerification())
    }
}
