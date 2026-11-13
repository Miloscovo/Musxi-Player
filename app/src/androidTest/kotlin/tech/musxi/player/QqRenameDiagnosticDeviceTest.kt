package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist

/** Test-only schema observation. Never exports credentials, names, ids or raw responses. */
class QqRenameDiagnosticDeviceTest {
    @Test fun readAndValidateRenameMetadata() = observe(false)
    @Test fun observeAuthorizedRenameRoundTrip() = observe(true)

    private fun observe(writable: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        if (writable) assumeTrue(args.getString("allowPlatformWrites") == "true" &&
            args.getString("allowPlaylistRename") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val report = JSONObject().put("started", System.currentTimeMillis()).put("writeAuthorized", writable)
        val events = JSONArray()
        fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance))
        @Suppress("UNCHECKED_CAST")
        val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
        val api = field(drivers.getValue(Platform.QQ), "api")
        val transportField = api.javaClass.getDeclaredField("transport").apply { isAccessible = true }
        val original = transportField.get(api)
        val execute = transportField.type.declaredMethods.single().apply { isAccessible = true }
        var edits = 0
        val proxy = Proxy.newProxyInstance(transportField.type.classLoader, arrayOf(transportField.type)) { _, method, arguments ->
            if (method.name != "execute") return@newProxyInstance method.invoke(original, *(arguments ?: emptyArray()))
            val request = requireNotNull(arguments?.firstOrNull())
            val rawBody = request.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(request) as? String
            val operation = rawBody?.let { JSONObject(it).optJSONObject("req_0") }
            val name = operation?.optString("method")
            if (name == "EditPlaylist") {
                edits++
                check(writable) { "Read-only metadata check refused a write" }
            }
            val response = try { execute.invoke(original, request) } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
            if (name == "uniform_get_Dissinfo" || name == "EditPlaylist") {
                val event = JSONObject().put("edit", name == "EditPlaylist")
                val bytes = field(response, "body") as ByteArray
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                val item = root.optJSONObject("req_0")
                for ((key, value) in listOf("rootCode" to root.opt("code"), "operationCode" to item?.opt("code")))
                    if (value is Number) event.put(key, value)
                val data = item?.optJSONObject("data")
                val info = data?.optJSONObject("dirinfo")
                event.put("hasData", data != null).put("hasDirinfo", info != null)
                if (data != null) event.put("dataFields", JSONArray(data.keys().asSequence().filter {
                    it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                }.take(100).toList()))
                if (info != null) {
                    val fields = JSONObject()
                    for (key in listOf("id", "tid", "dissid", "dirid", "dirId", "title", "desc", "picurl", "picurl2", "tag", "dir_show")) {
                        val value = info.opt(key)
                        fields.put(key, when (value) {
                            null -> "missing"
                            JSONObject.NULL -> "null"
                            is String -> if (value.isEmpty()) "empty_string" else "string"
                            is Number -> "number"
                            is JSONArray -> "array"
                            is JSONObject -> "object"
                            is Boolean -> "boolean"
                            else -> "other"
                        })
                    }
                    event.put("fields", fields)
                }
                synchronized(events) { events.put(event) }
            }
            response
        }
        transportField.set(api, proxy)
        try {
            phone.waitUntil(60_000) { app.accounts.states.value.getValue(Platform.QQ).phase == AccountPhase.CONNECTED }
            if (writable) {
                PlaylistRenameDeviceTest().qqOrdinaryPlaylistRename()
            } else {
                fun <T> await(work: ((Result<T>) -> Unit) -> Unit): T {
                    val complete = CompletableFuture<Result<T>>()
                    phone.runOnIdle { work { complete.complete(it) } }
                    return complete.get(120, TimeUnit.SECONDS).getOrThrow()
                }
                val targets = await<List<EditablePlaylist>> { app.playlists.listWritable(Platform.QQ, it) }
                val target = targets.firstOrNull { !it.favorite && it.playlist.title.isNotBlank() &&
                    it.playlist.title.trim() == it.playlist.title && it.playlist.title.length <= 20 &&
                    it.playlist.title.none(Char::isISOControl) }
                check(target != null) { "No restorable ordinary QQ playlist" }
                await<Unit> { app.playlists.renamePlaylist(target, target.playlist.title, it) }
                check(edits == 0) { "Read-only metadata preflight attempted a write" }
            }
            report.put("status", "PASSED")
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("errorClass", failure.javaClass.simpleName)
            throw failure
        } finally {
            transportField.set(api, original)
            report.put("finished", System.currentTimeMillis()).put("writeRequests", edits).put("events", events)
            val suffix = if (writable) "roundtrip" else "read"
            File(app.filesDir, "qq-rename-protocol-$suffix.json").writeText(report.toString(2))
        }
    }
}
