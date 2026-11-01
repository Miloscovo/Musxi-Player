package tech.musxi.accounts

import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.account.MembershipLevel
import tech.musxi.core.library.MediaKey
import tech.musxi.core.playback.QualityOptions

/** Short-lived public options only; no signed URLs or account credentials are retained. */
internal class QualityMenuCache(
    private val now: () -> Long,
    private val ttlMillis: Long = 60_000,
    private val maxEntries: Int = 32,
) {
    private data class Entry(val at: Long, val options: QualityOptions)
    private var ownerGeneration: Long? = null
    private var ownerMembership: MembershipLevel? = null
    private val entries = linkedMapOf<MediaKey, Entry>()

    /** Serializes misses too, so simultaneous menus cannot duplicate authorization probes. */
    @Synchronized fun getOrLoad(generation: Long, key: MediaKey, current: () -> Boolean,
        membership: MembershipLevel = MembershipLevel.UNKNOWN,
        load: () -> QualityOptions): QualityOptions {
        if (!current()) throw LibraryPageFailure("account_changed")
        if (ownerGeneration != generation || ownerMembership != membership) {
            entries.clear(); ownerGeneration = generation; ownerMembership = membership
        }
        val time = now()
        entries[key]?.takeIf { time >= it.at && time - it.at < ttlMillis }?.let { return it.options }
        val loaded = load()
        if (!current()) throw LibraryPageFailure("account_changed")
        entries.remove(key)
        entries[key] = Entry(now(), loaded.copy(options = java.util.Collections.unmodifiableList(loaded.options.toList())))
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return entries.getValue(key).options
    }
}
