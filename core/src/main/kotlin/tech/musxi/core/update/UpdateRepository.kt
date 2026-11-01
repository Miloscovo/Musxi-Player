package tech.musxi.core.update

import java.math.BigInteger
import java.net.URI

/** Transport output. The platform adapter owns HTTP, headers, pagination, and JSON decoding. */
sealed interface ReleaseFetch {
    data class Success(val releases: List<GitHubRelease>) : ReleaseFetch

    /** [retryAfterSeconds] is GitHub's Retry-After value when the adapter received one. */
    data class RateLimited(val retryAfterSeconds: Long? = null) : ReleaseFetch

    data object Failed : ReleaseFetch
}

fun interface UpdateTransport {
    fun fetchReleases(): ReleaseFetch
}

fun interface UpdateClock {
    fun nowEpochMillis(): Long
}

data class GitHubRelease(
    val tagName: String,
    val notes: String,
    val releasePage: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
)

interface IgnoredVersionStore {
    fun load(): String?

    fun save(version: String)
}

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult

    data class UpdateAvailable(
        val version: String,
        val notes: String,
        val releasePage: String,
    ) : UpdateCheckResult

    data class RateLimited(val retryAtEpochMillis: Long) : UpdateCheckResult

    data object Failed : UpdateCheckResult
}

/**
 * Checks releases for qwt1011/Musxi-Player-Android without downloading or installing anything.
 *
 * A successful response is cached and checked again only after [throttleMillis]. Rate-limit
 * responses block requests for the longer of [throttleMillis] and GitHub's Retry-After suggestion
 * (or [defaultRateLimitBackoffMillis] when omitted). Calls are serialized so a burst cannot send
 * duplicate requests.
 */
class UpdateRepository(
    currentVersion: String,
    private val transport: UpdateTransport,
    private val clock: UpdateClock,
    private val ignoredVersionStore: IgnoredVersionStore,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val defaultRateLimitBackoffMillis: Long = DEFAULT_RATE_LIMIT_BACKOFF_MILLIS,
) {
    private val current = requireNotNull(SemanticVersion.parse(currentVersion)) {
        "Unsupported current version: $currentVersion"
    }
    private val lock = Any()
    private var cachedReleases: List<GitHubRelease>? = null
    private var nextRequestAtEpochMillis = Long.MIN_VALUE
    private var lastResult: UpdateCheckResult = UpdateCheckResult.Failed

    init {
        require(throttleMillis >= 0L)
        require(defaultRateLimitBackoffMillis >= 0L)
    }

    fun check(includePrerelease: Boolean): UpdateCheckResult = synchronized(lock) {
        val now = clock.nowEpochMillis()
        if (now < nextRequestAtEpochMillis) {
            cachedReleases?.let { return@synchronized evaluate(it, includePrerelease) }
            return@synchronized when (val previous = lastResult) {
                is UpdateCheckResult.RateLimited ->
                    previous.copy(retryAtEpochMillis = nextRequestAtEpochMillis)

                else -> previous
            }
        }

        val fetch = try {
            transport.fetchReleases()
        } catch (_: Exception) {
            ReleaseFetch.Failed
        }

        val result = when (fetch) {
            is ReleaseFetch.Success -> {
                val snapshot = fetch.releases.toList()
                cachedReleases = snapshot
                nextRequestAtEpochMillis = safeAdd(now, throttleMillis)
                evaluate(snapshot, includePrerelease)
            }

            is ReleaseFetch.RateLimited -> {
                cachedReleases = null
                val suggested = fetch.retryAfterSeconds
                    ?.takeIf { it > 0L }
                    ?.let(::secondsToMillis)
                    ?: defaultRateLimitBackoffMillis
                nextRequestAtEpochMillis = maxOf(
                    safeAdd(now, throttleMillis),
                    safeAdd(now, suggested),
                )
                UpdateCheckResult.RateLimited(nextRequestAtEpochMillis)
            }

            ReleaseFetch.Failed -> {
                cachedReleases = null
                nextRequestAtEpochMillis = safeAdd(now, throttleMillis)
                UpdateCheckResult.Failed
            }
        }
        lastResult = result
        result
    }

    /** Saves the tag locally. Call with [UpdateCheckResult.UpdateAvailable.version]. */
    fun ignore(version: String) {
        requireNotNull(SemanticVersion.parse(version)) { "Unsupported release version: $version" }
        ignoredVersionStore.save(version)
    }

    private fun evaluate(
        releases: List<GitHubRelease>,
        includePrerelease: Boolean,
    ): UpdateCheckResult {
        val latest = releases.asSequence()
            .filter { !it.draft && (includePrerelease || !it.prerelease) }
            .mapNotNull { release ->
                val version = SemanticVersion.parse(release.tagName) ?: return@mapNotNull null
                if (!isRepositoryReleasePage(release.releasePage)) return@mapNotNull null
                ParsedRelease(release, version)
            }
            .maxByOrNull { it.version }
            ?: return UpdateCheckResult.NoUpdate

        if (latest.version <= current) return UpdateCheckResult.NoUpdate

        val ignored = try {
            ignoredVersionStore.load()?.let(SemanticVersion::parse)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed
        }
        if (ignored != null && ignored == latest.version) return UpdateCheckResult.NoUpdate

        return UpdateCheckResult.UpdateAvailable(
            version = latest.release.tagName,
            notes = latest.release.notes,
            releasePage = latest.release.releasePage,
        )
    }

    private data class ParsedRelease(
        val release: GitHubRelease,
        val version: SemanticVersion,
    )

    private companion object {
        const val DEFAULT_THROTTLE_MILLIS = 30_000L
        const val DEFAULT_RATE_LIMIT_BACKOFF_MILLIS = 60_000L
        const val REPOSITORY_RELEASE_PREFIX =
            "/qwt1011/Musxi-Player-Android/releases/tag/"

        fun isRepositoryReleasePage(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (!uri.host.equals("github.com", ignoreCase = true)) return false
            if (uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
            if (uri.query != null || uri.fragment != null) return false
            val path = uri.path ?: return false
            if (!path.startsWith(REPOSITORY_RELEASE_PREFIX, ignoreCase = true)) return false
            val tag = path.substring(REPOSITORY_RELEASE_PREFIX.length)
            return tag.isNotBlank() &&
                tag != "." &&
                tag != ".." &&
                !tag.contains('\\')
        }

        fun safeAdd(base: Long, increment: Long): Long {
            if (increment <= 0L) return base
            return if (base > Long.MAX_VALUE - increment) Long.MAX_VALUE else base + increment
        }

        fun secondsToMillis(seconds: Long): Long {
            val multiplier = 1_000L
            return if (seconds > Long.MAX_VALUE / multiplier) Long.MAX_VALUE
            else seconds * multiplier
        }
    }
}

data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let {
            if (it != 0) return it
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        val common = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until common) {
            val comparison = compareIdentifier(prerelease[index], other.prerelease[index])
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)
        return when {
            leftNumeric && rightNumeric ->
                BigInteger(left).compareTo(BigInteger(right))

            leftNumeric -> -1
            rightNumeric -> 1
            else -> left.compareTo(right)
        }
    }

    companion object {
        private val TAG = Regex(
            """^[vV]?(\d+)\.(\d+)(?:\.(\d+))?""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""" +
                """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        fun parse(value: String): SemanticVersion? {
            val match = TAG.matchEntire(value) ?: return null
            return SemanticVersion(
                major = BigInteger(match.groupValues[1]),
                minor = BigInteger(match.groupValues[2]),
                patch = match.groupValues[3].takeIf(String::isNotEmpty)
                    ?.let(::BigInteger)
                    ?: BigInteger.ZERO,
                prerelease = match.groupValues[4]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                    .orEmpty(),
            )
        }
    }
}
