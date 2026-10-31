package tech.musxi.player

import org.json.JSONObject

/** Fixed, read-only probe; no song/device identifiers, stream requests, or playback commands. */
internal object PlaybackToolsValidationRunner {
    fun run(app: MusxiApplication, step: String): Boolean {
        if (step != "tools_snapshot") return false
        val state = app.playback.state.value
        val outputs = app.playback.availableOutputs()
        val report = JSONObject().apply {
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("timestamp", System.currentTimeMillis())
            put("connected", state.connected)
            put("playing", state.playing)
            put("playWhenReady", state.playWhenReady)
            put("queueSize", state.queueSize)
            put("qualityName", state.qualityName ?: JSONObject.NULL)
            put("outputCount", outputs.size)
            put("selectedTypes", outputs.filter { it.selected }.map { it.type.name }.joinToString(","))
            put("inferred", app.playback.outputs.value.inferred)
            put("directChoiceCount", outputs.count { it.canSelect })
        }
        java.io.File(app.filesDir, "playback-tools-validation.json").writeText(report.toString())
        return true
    }
}
