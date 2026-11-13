package tech.musxi.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.account.Platform

/** Test-only transport observation. Never exports sessions, headers, requests, ids or names. */
class KugouRenameDiagnosticDeviceTest {
    @Test fun observeRenameReplyWithoutPrivateData() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowPlatformWrites") == "true" && args.getString("allowPlaylistRename") == "true")
        val phone = BackendBatch5DeviceTest.PhoneDriver()
        val app = phone.activity.application as MusxiApplication
        val events = JSONArray()
        val report = JSONObject().put("started", System.currentTimeMillis())
        fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance))
        @Suppress("UNCHECKED_CAST")
        val drivers = (field(app.accounts, "drivers\$delegate") as Lazy<Map<Platform, Any>>).value
        val driver = requireNotNull(drivers.getValue(Platform.KUGOU))
        val api = (field(driver, "api\$delegate") as Lazy<*>).value!!
        val transportField = api.javaClass.getDeclaredField("transport").apply { isAccessible = true }
        val original = transportField.get(api)
        val type = transportField.type
        val execute = type.declaredMethods.single().apply { isAccessible = true }
        var temporaryName: String? = null
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            if (method.name != "execute") return@newProxyInstance method.invoke(original, *(arguments ?: emptyArray()))
            val request = requireNotNull(arguments?.firstOrNull())
            val endpoint = field(request, "endpoint") as String
            val edit = endpoint.endsWith("/modify_list")
            if (edit) {
                val cloud = field(request, "cloud")
                val raw = field(request, "wireBody") as ByteArray
                val decode = cloud.javaClass.getDeclaredMethod("decode", ByteArray::class.java).apply { isAccessible = true }
                val payload = JSONObject(decode.invoke(cloud, raw) as String)
                if (temporaryName == null) temporaryName = payload.getString("name")
            }
            val response = try { execute.invoke(original, request) } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
            if (response is JSONObject && (edit || endpoint.endsWith("/get_all_list"))) {
                val event = JSONObject().put("edit", edit).put("status", response.opt("status"))
                    .put("error_code", response.opt("error_code"))
                    .put("rootFields", JSONArray(response.keys().asSequence().toList()))
                val data = response.optJSONObject("data")
                if (data != null) {
                    event.put("dataFields", JSONArray(data.keys().asSequence().toList()))
                    for (name in listOf("total_ver", "errcode", "error_code", "status", "ver", "ret")) {
                        val value = data.opt(name)
                        if (value is Number || value is Boolean) event.put(name, value)
                    }
                    val rows = data.optJSONArray("info")
                    if (rows != null) event.put("rows", JSONArray(List(rows.length()) { index ->
                        val row = rows.optJSONObject(index) ?: JSONObject()
                        JSONObject().put("nameEqualsTemporary", row.optString("name") == temporaryName)
                            .put("isEdit", row.opt("is_edit")).put("isDefault", row.opt("is_def"))
                            .put("totalVersion", row.opt("total_ver")).put("sort", row.opt("sort"))
                    }))
                }
                synchronized(events) { events.put(event) }
            }
            response
        }
        transportField.set(api, proxy)
        try {
            PlaylistRenameDeviceTest().kugouOrdinaryPlaylistRename()
            report.put("status", "PASSED")
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("error", failure.javaClass.simpleName)
            throw failure
        } finally {
            transportField.set(api, original)
            report.put("finished", System.currentTimeMillis()).put("events", events)
            File(app.filesDir, "kugou-rename-protocol.json").writeText(report.toString(2))
        }
    }
}
