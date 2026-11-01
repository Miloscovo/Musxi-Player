package tech.musxi.accounts

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import tech.musxi.core.update.*

/** Main-thread command/state boundary. Background work contains only public Release metadata. */
class AppUpdateRepository internal constructor(
    context: Context, currentVersion: String, releaseBuild: Boolean,
    private val beforeInstall: ((Result<Unit>) -> Unit) -> Unit,
    private val checkHttp: UpdateHttpClient, private val downloadHttp: UpdateHttpClient,
    private val now: () -> Long,
) {
    constructor(context: Context, currentVersion: String, releaseBuild: Boolean = false,
        beforeInstall: ((Result<Unit>) -> Unit) -> Unit = { it(Result.success(Unit)) }) :
        this(context, currentVersion, releaseBuild, beforeInstall, UpdateHttpClient(), UpdateHttpClient(), System::currentTimeMillis)

    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("release-check-v1", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val downloads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
    private val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    private val development = !releaseBuild || app.packageName != "tech.musxi.player"
    private val current = UpdateVersion(currentVersion, versionCode)
    private val cache = File(app.cacheDir, "app-updates")
    private val mutable = MutableStateFlow(initialState())
    val state = mutable.asStateFlow()
    private var selected: SelectedRelease? = null
    private var cached: List<SelectedRelease>? = null
    private var nextCheckAt = 0L
    private var lastCheckFailure: UpdateFailure? = null
    private var checking = false
    private var started = false
    private var readyFile: File? = null
    private var readyRelease: SelectedRelease? = null
    private var cancellation: AtomicBoolean? = null
    private var downloadEpoch = 0L
    private val callbacks = mutableListOf<(UpdateCheckResult) -> Unit>()
    private val installer = UpdateInstaller(app, ::onInstallResult)

    init {
        cache.listFiles()?.forEach { it.delete() }
        runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) = refreshNetworkState()
                override fun onLost(network: android.net.Network) = refreshNetworkState()
            }) }
    }
    private fun initialState(): UpdateState {
        val previous = preferences.getString("installed-version", null)
        val previousCode = preferences.getLong("installed-code", -1)
        val updated = previous != null && previousCode >= 0 && versionCode > previousCode
        val edit = preferences.edit().putString("installed-version", current.versionName).putLong("installed-code", versionCode)
        if (updated) edit.putString("just-updated-from", previous).putBoolean("auto-check", true)
        edit.commit()
        return UpdateState(current, development, preferences.getBoolean("auto-check", true),
            preferences.getLong("last-success", 0).takeIf { it > 0 },
            justUpdatedFrom = if (development) null else if (updated) previous else preferences.getString("just-updated-from", null),
            meteredNetwork = isMetered(), installOutcome = null)
    }

    /** Application starts this once; development builds have no timer or startup request. */
    fun start() = onMain {
        if (!started && !development) {
            started = true
            main.postDelayed({ if (DailyUpdatePolicy.shouldCheck(true, state.value.autoCheck, state.value.lastSuccessAt, now()))
                checkInternal(true, null) }, 5_000)
        }
    }
    fun check() = onMain { checkInternal(true, null) }
    /** Compatibility: the observable state also carries development/latest metadata. */
    fun check(includePrerelease: Boolean = true, result: (UpdateCheckResult) -> Unit) =
        onMain { checkInternal(includePrerelease, result) }
    private fun checkInternal(includePrerelease: Boolean, callback: ((UpdateCheckResult) -> Unit)?) {
        callback?.let(callbacks::add)
        if (checking) return
        if (now() < nextCheckAt) { finishCheck(cached, lastCheckFailure, includePrerelease, false); return }
        checking = true
        mutable.value = state.value.copy(check = UpdateCheckState(UpdateCheckPhase.CHECKING), meteredNetwork = isMetered())
        worker.execute {
            val result = runCatching { fetchReleases() }
            main.post {
                checking = false
                val failure = result.exceptionOrNull()
                nextCheckAt = now() + if (failure is UpdateRateLimit) maxOf(30_000, failure.seconds * 1000) else 30_000
                cached = result.getOrNull(); lastCheckFailure = failure?.let(::reason)
                finishCheck(cached, lastCheckFailure, includePrerelease, failure == null)
            }
        }
    }
    private fun finishCheck(releases: List<SelectedRelease>?, failure: UpdateFailure?, includePrerelease: Boolean, fresh: Boolean) {
        if (failure != null || releases == null) {
            mutable.value = state.value.copy(check = UpdateCheckState(UpdateCheckPhase.FAILED, failure ?: UpdateFailure.NETWORK,
                nextCheckAt.takeIf { failure == UpdateFailure.RATE_LIMITED }))
        } else {
            val latest = releases.filter { includePrerelease || !it.prerelease }.maxByOrNull { SemanticVersion.parse(it.tag)!! }
            if (selected?.identity != latest?.identity && state.value.download.phase != UpdateDownloadPhase.NONE)
                discardDownload(UpdateFailure.RELEASE_CHANGED)
            selected = latest
            val success = if (fresh) now() else state.value.lastSuccessAt
            if (fresh) preferences.edit().putLong("last-success", success!!).apply()
            mutable.value = state.value.copy(check = UpdateCheckState(UpdateCheckPhase.SUCCEEDED), latest = latest?.publicInfo(), lastSuccessAt = success)
        }
        val legacy = when {
            failure == UpdateFailure.RATE_LIMITED -> UpdateCheckResult.RateLimited(nextCheckAt)
            failure != null || releases == null -> UpdateCheckResult.Failed
            development -> UpdateCheckResult.NoUpdate
            state.value.latest?.let { it.newer && !it.ignored } == true -> state.value.latest!!.let {
                UpdateCheckResult.UpdateAvailable(it.version, it.notes, it.releasePage)
            }
            else -> UpdateCheckResult.NoUpdate
        }
        val pending = callbacks.toList(); callbacks.clear(); pending.forEach { it(legacy) }
    }
    fun setAutoCheck(enabled: Boolean) = onMain {
        preferences.edit().putBoolean("auto-check", enabled).apply(); mutable.value = state.value.copy(autoCheck = enabled)
    }
    fun ignore(version: String) = onMain {
        if (selected?.tag != version && selected?.version != version) return@onMain
        preferences.edit().putString("ignored", selected!!.version).apply(); mutable.value = state.value.copy(latest = selected?.publicInfo())
    }
    fun clearIgnore() = onMain {
        preferences.edit().remove("ignored").apply(); mutable.value = state.value.copy(latest = selected?.publicInfo())
    }
    fun consumeJustUpdated() = onMain {
        preferences.edit().remove("just-updated-from").apply(); mutable.value = state.value.copy(justUpdatedFrom = null)
    }
    fun refreshNetworkState() = onMain { mutable.value = state.value.copy(meteredNetwork = isMetered()) }

    /** UI obtains metered-network consent before this explicit command. */
    fun startDownload(version: String) = onMain {
        val release = selected
        when {
            development -> return@onMain fail(UpdateFailure.DEVELOPMENT_BUILD)
            release == null || (version != release.tag && version != release.version) -> return@onMain fail(UpdateFailure.RELEASE_CHANGED)
            cancellation != null || state.value.download.phase == UpdateDownloadPhase.INSTALLING -> return@onMain
            !release.publicInfo().canDownload -> return@onMain fail(release.manifestFailure ?: UpdateFailure.DOWNLOAD_UNAVAILABLE)
        }
        discardDownload()
        val token = ++downloadEpoch
        val cancelled = AtomicBoolean(); cancellation = cancelled
        val manifest = release!!.manifest!!
        mutable.value = state.value.copy(download = UpdateDownloadState(UpdateDownloadPhase.DOWNLOADING, release.version, totalBytes = manifest.bytes),
            meteredNetwork = isMetered(), installOutcome = null)
        downloads.execute {
            var part: File? = null
            var completed: File? = null
            val result = runCatching {
                if (loadManifest(release, downloadHttp, cancelled) != manifest) throw UpdateException(UpdateFailure.RELEASE_CHANGED)
                if (!cache.isDirectory && !cache.mkdirs()) throw UpdateException(UpdateFailure.STORAGE)
                if (cache.usableSpace < manifest.bytes + 1_048_576) throw UpdateException(UpdateFailure.STORAGE)
                part = try { File.createTempFile("download-", ".part", cache) }
                    catch (_: java.io.IOException) { throw UpdateException(UpdateFailure.STORAGE) }
                FileOutputStream(part!!).use { output ->
                    var posted = 0L
                    downloadHttp.transfer(release.assets.single { it.name == manifest.assetName }.url,
                        manifest.bytes, manifest.sha256, output, cancelled) { received ->
                        if (received == manifest.bytes || received - posted >= 256 * 1024) {
                            posted = received
                            main.post { if (token == downloadEpoch && !cancelled.get()) mutable.value = state.value.copy(
                                download = state.value.download.copy(receivedBytes = received)) }
                        }
                    }
                    try { output.fd.sync() } catch (_: java.io.IOException) { throw UpdateException(UpdateFailure.STORAGE) }
                }
                main.post { if (token == downloadEpoch && !cancelled.get()) mutable.value = state.value.copy(
                    download = state.value.download.copy(phase = UpdateDownloadPhase.VERIFYING)) }
                verify(part!!, manifest)
                if (cancelled.get()) throw UpdateException(UpdateFailure.CANCELLED)
                completed = File(cache, "verified-$token.apk")
                if (!part!!.renameTo(completed!!)) throw UpdateException(UpdateFailure.STORAGE)
                completed!!
            }
            part?.delete()
            main.post {
                if (token != downloadEpoch || cancelled.get() || selected?.identity != release.identity) {
                    completed?.delete()
                    if (token == downloadEpoch) { cancellation = null; fail(if (cancelled.get()) UpdateFailure.CANCELLED else UpdateFailure.RELEASE_CHANGED) }
                } else {
                    cancellation = null
                    result.onSuccess { file ->
                        readyFile = file; readyRelease = release
                        mutable.value = state.value.copy(download = state.value.download.copy(phase = UpdateDownloadPhase.READY))
                    }.onFailure { completed?.delete(); fail(reason(it)) }
                }
            }
        }
    }
    fun cancelDownload() = onMain { if (state.value.download.phase != UpdateDownloadPhase.INSTALLING) discardDownload(UpdateFailure.CANCELLED) }
    fun install() = onMain {
        if (development) return@onMain fail(UpdateFailure.DEVELOPMENT_BUILD)
        val file = readyFile ?: return@onMain fail(UpdateFailure.NOT_READY)
        val release = readyRelease ?: return@onMain fail(UpdateFailure.NOT_READY)
        if (release.identity != selected?.identity) return@onMain discardDownload(UpdateFailure.RELEASE_CHANGED)
        if (state.value.download.phase == UpdateDownloadPhase.INSTALLING) return@onMain
        if (!app.packageManager.canRequestPackageInstalls()) {
            mutable.value = state.value.copy(download = state.value.download.copy(phase = UpdateDownloadPhase.NEEDS_PERMISSION, reason = UpdateFailure.PERMISSION_DENIED)); return@onMain
        }
        mutable.value = state.value.copy(download = state.value.download.copy(phase = UpdateDownloadPhase.INSTALLING, reason = null))
        beforeInstall { response -> onMain checkpoint@{
            if (response.isFailure) return@checkpoint fail(UpdateFailure.CHECKPOINT_FAILED)
            if (release.identity != selected?.identity) return@checkpoint discardDownload(UpdateFailure.RELEASE_CHANGED)
            downloads.execute {
                val result = runCatching { val manifest = release.manifest!!; verify(file, manifest); installer.install(file, manifest.bytes) }
                main.post { if (result.isFailure) discardDownload(reason(result.exceptionOrNull()!!)) }
            }
        } }
    }
    fun openInstallPermissionSettings() = onMain {
        if (development) return@onMain fail(UpdateFailure.DEVELOPMENT_BUILD)
        runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { fail(UpdateFailure.PERMISSION_DENIED) }
    }
    private fun onInstallResult(failure: UpdateFailure?) = onMain {
        readyFile?.delete(); readyFile = null; readyRelease = null
        mutable.value = state.value.copy(download = if (failure == null) UpdateDownloadState()
            else state.value.download.copy(phase = UpdateDownloadPhase.FAILED, reason = failure),
            installOutcome = if (failure == null) UpdateInstallOutcome.SUCCEEDED else if (failure == UpdateFailure.CANCELLED)
                UpdateInstallOutcome.CANCELLED else UpdateInstallOutcome.FAILED)
    }
    private fun verify(file: File, manifest: UpdateManifest) {
        if (file.length() != manifest.bytes) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
        file.inputStream().use { input -> copyUpdateBytes(input, object : java.io.OutputStream() { override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit }, manifest.bytes, manifest.sha256, AtomicBoolean()) {} }
        verifyApkUpdate(app, file, manifest, versionCode)
    }
    private fun discardDownload(failure: UpdateFailure? = null) {
        ++downloadEpoch; cancellation?.set(true); cancellation = null; downloadHttp.cancel()
        readyFile?.delete(); readyFile = null; readyRelease = null
        if (state.value.download.phase != UpdateDownloadPhase.NONE || failure != null)
            mutable.value = state.value.copy(download = if (failure == null) UpdateDownloadState()
                else state.value.download.copy(phase = UpdateDownloadPhase.FAILED, reason = failure))
    }
    private fun fail(failure: UpdateFailure) { mutable.value = state.value.copy(download = state.value.download.copy(phase = UpdateDownloadPhase.FAILED, reason = failure)) }
    private fun isMetered() = runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }
    private fun reason(error: Throwable): UpdateFailure = when (error) {
        is UpdateException -> error.reason
        is UpdateRateLimit -> UpdateFailure.RATE_LIMITED
        is java.net.SocketTimeoutException -> UpdateFailure.TIMEOUT
        is org.json.JSONException -> UpdateFailure.INVALID_MANIFEST
        is java.io.FileNotFoundException -> UpdateFailure.STORAGE
        else -> UpdateFailure.NETWORK
    }
    private fun fetchReleases(): List<SelectedRelease> {
        val rows = JSONArray(checkHttp.read(UpdateHttpClient.RELEASES_URL, 1_048_576).toString(Charsets.UTF_8))
        if (rows.length() > 30) throw UpdateException(UpdateFailure.INVALID_MANIFEST)
        val releases = (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i); val tag = row.getString("tag_name"); val page = row.getString("html_url")
            if (row.optBoolean("draft") || SemanticVersion.parse(tag) == null || !OfficialUpdateSource.isReleasePage(page, tag)) return@mapNotNull null
            val assets = row.optJSONArray("assets") ?: JSONArray()
            if (assets.length() > 100) return@mapNotNull null
            SelectedRelease(row.getLong("id"), tag, row.optString("body", "").take(128_000), page,
                row.optBoolean("prerelease"), row.optString("published_at").takeIf { it.isNotEmpty() && it != "null" },
                (0 until assets.length()).map { a -> assets.getJSONObject(a).let {
                    ReleaseAsset(it.getLong("id"), it.getString("name"), it.getLong("size"), it.getString("browser_download_url"), it.optString("state") == "uploaded")
                } })
        }
        val candidates = listOfNotNull(releases.maxByOrNull { SemanticVersion.parse(it.tag)!! },
            releases.filter { !it.prerelease }.maxByOrNull { SemanticVersion.parse(it.tag)!! }).distinct()
        candidates.forEach { latest ->
            if (latest.assets.any { it.name == MANIFEST_NAME }) runCatching { loadManifest(latest, checkHttp, AtomicBoolean()) }
                .onSuccess { latest.manifest = it }.onFailure { latest.manifestFailure = reason(it) }
        }
        return releases
    }
    private fun loadManifest(release: SelectedRelease, http: UpdateHttpClient, cancelled: AtomicBoolean): UpdateManifest {
        val asset = release.assets.filter { it.name == MANIFEST_NAME }.singleOrNull() ?: throw UpdateException(UpdateFailure.INVALID_MANIFEST)
        if (!asset.uploaded || asset.id <= 0 || asset.bytes !in 1..4096 || !OfficialUpdateSource.isAsset(asset.url, release.tag, MANIFEST_NAME))
            throw UpdateException(UpdateFailure.INVALID_MANIFEST)
        val bytes = http.read(asset.url, 4096, cancelled)
        if (bytes.size.toLong() != asset.bytes) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
        return UpdateManifestParser.parse(bytes, release.tag, release.assets)
    }
    private inner class SelectedRelease(val id: Long, val tag: String, val notes: String, val page: String,
        val prerelease: Boolean, val published: String?, val assets: List<ReleaseAsset>) {
        val version = tag.removePrefix("v").removePrefix("V")
        var manifest: UpdateManifest? = null
        var manifestFailure: UpdateFailure? = null
        val identity get() = listOf(id, tag, assets, manifest)
        fun publicInfo(): LatestUpdate {
            val newer = !development && SemanticVersion.parse(tag)!! > (SemanticVersion.parse(current.versionName) ?: SemanticVersion.parse("0.0.0")!!)
            return LatestUpdate(version, tag, prerelease, notes, published, page,
                manifest != null, manifest?.bytes, preferences.getString("ignored", null)?.let { it == version || it == tag } == true,
                newer, !development && newer && manifest != null && manifest!!.versionCode > versionCode && manifest!!.minSdk <= Build.VERSION.SDK_INT, manifestFailure)
        }
    }
    companion object { const val MANIFEST_NAME = "update-manifest.json" }
}
