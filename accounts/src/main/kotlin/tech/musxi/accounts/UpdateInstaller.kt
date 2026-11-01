package tech.musxi.accounts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import tech.musxi.core.update.*

@Suppress("DEPRECATION")
internal fun verifyApkUpdate(context: Context, file: File, manifest: UpdateManifest, currentCode: Long) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw UpdateException(UpdateFailure.PACKAGE_MISMATCH)
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) } }.toSet()
    }
    val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    ApkUpdatePolicy.verify(manifest, context.packageName, currentCode, archive.packageName, archiveCode,
        archive.versionName, signers(archive), signers(installed), Build.VERSION.SDK_INT)
    if (archive.applicationInfo?.minSdkVersion != manifest.minSdk) throw UpdateException(UpdateFailure.INVALID_MANIFEST)
}

/** Session result is addressed to an unexported receiver and bound to a random one-use token. */
internal class UpdateInstaller(private val context: Context, callback: (UpdateFailure?) -> Unit) {
    init { UpdateInstallReceiver.callback = callback }
    fun install(file: File, bytes: Long) {
        if (!context.packageManager.canRequestPackageInstalls()) throw UpdateException(UpdateFailure.PERMISSION_DENIED)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = try { installer.createSession(params) }
            catch (_: SecurityException) { throw UpdateException(UpdateFailure.PERMISSION_DENIED) }
            catch (_: java.io.IOException) { throw UpdateException(UpdateFailure.STORAGE) }
        val preferences = context.getSharedPreferences("update-install", Context.MODE_PRIVATE)
        val token = UUID.randomUUID().toString()
        try {
            if (!preferences.edit().putString("token", token).putInt("session", sessionId).commit()) throw UpdateException(UpdateFailure.STORAGE)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, bytes).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_RESULT")
                    .putExtra("token", token)
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            preferences.edit().clear().apply()
            if (error is UpdateException) throw error
            throw UpdateException(when (error) {
                is SecurityException -> UpdateFailure.PERMISSION_DENIED
                is java.io.IOException -> UpdateFailure.STORAGE
                else -> UpdateFailure.INSTALL_FAILED
            })
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_RESULT") return
        val preferences = context.getSharedPreferences("update-install", Context.MODE_PRIVATE)
        val token = preferences.getString("token", null) ?: return
        if (intent.getStringExtra("token") != token || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != preferences.getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null && runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
            runCatching { context.packageManager.packageInstaller.abandonSession(preferences.getInt("session", -1)) }
        }
        preferences.edit().clear().apply()
        val failure = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateFailure.CANCELLED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFailure.PERMISSION_DENIED
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.STORAGE
            else -> UpdateFailure.INSTALL_FAILED
        }
        callback?.invoke(failure)
    }
    companion object { internal var callback: ((UpdateFailure?) -> Unit)? = null }
}
