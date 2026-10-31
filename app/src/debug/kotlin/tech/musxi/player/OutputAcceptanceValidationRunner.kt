package tech.musxi.player

import org.json.JSONObject
import tech.musxi.playback.OutputDevice
import tech.musxi.playback.OutputFailure
import tech.musxi.playback.OutputSelectionException

/**
 * Fixed debug acceptance probe for the real playback-service output path.
 * Reports contain only categorical/type/count data; device names and IDs never leave this file.
 */
internal object OutputAcceptanceValidationRunner {
    private const val REPORT_NAME = "output-acceptance-validation.json"
    private const val MISSING_OUTPUT_ID = "musxi.debug.output-acceptance.missing"

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("output_acceptance", "output_snapshot")) return false
        return if (step == "output_snapshot") writeSnapshot(app) else runAcceptance(app)
    }

    private fun runAcceptance(app: MusxiApplication): Boolean {
        val before = capture(app)
        val report = baseReport("output_acceptance", before)
        if (!before.connected) {
            report.put("status", "BLOCKED")
            report.put("reason", "PLAYBACK_DISCONNECTED")
            report.put("conclusion", "BLOCKED: playback service is disconnected")
            return writeReport(app, report)
        }

        val selected = before.devices.filter { it.selected }
        if (selected.isEmpty()) {
            report.put("status", "BLOCKED")
            report.put("reason", "NO_SELECTED_OUTPUT")
            report.put("conclusion", "BLOCKED: no selected output is available")
            return writeReport(app, report)
        }

        val target = selected.first()
        report.put("stage", "SELECT_CURRENT_OUTPUT")
        report.put("selectedDeviceType", target.type.name)
        report.put("selectedDeviceCount", selected.size)
        if (!writeReport(app, report)) return false

        return try {
            app.playback.selectOutput(target.id) { result ->
                if (result.isSuccess) {
                    report.put("selectedCurrentResult", "SUCCESS")
                    report.put("selectedCurrentFailure", JSONObject.NULL)
                    report.put("stage", "INVALID_ID_LOOKUP")
                    writeReport(app, report)
                    requestMissingOutput(app, report, before, target)
                } else {
                    val category = failureCategory(result)
                    report.put("selectedCurrentResult", "FAILURE")
                    report.put("selectedCurrentFailure", category)
                    finish(
                        app,
                        report,
                        before,
                        target,
                        if (category == OutputFailure.DISCONNECTED.name) "BLOCKED" else "FAILURE",
                        reason = "CURRENT_OUTPUT_SELECTION_$category",
                    )
                }
            }
            true
        } catch (_: Exception) {
            report.put("selectedCurrentResult", "TRANSPORT_THROW")
            report.put("selectedCurrentFailure", "SYNC_IO_EXCEPTION")
            finish(
                app,
                report,
                before,
                target,
                status = "FAILURE",
                reason = "CURRENT_OUTPUT_SELECTION_SYNC_IO_EXCEPTION",
            )
            true
        }
    }

    private fun requestMissingOutput(
        app: MusxiApplication,
        report: JSONObject,
        before: Snapshot,
        target: OutputDevice,
    ) {
        try {
            app.playback.selectOutput(MISSING_OUTPUT_ID) { result ->
                val category = failureCategory(result)
                report.put("invalidResult", if (result.isSuccess) "UNEXPECTED_SUCCESS" else "FAILURE")
                report.put("invalidFailure", category)
                val blocked = category == OutputFailure.DISCONNECTED.name
                finish(
                    app,
                    report,
                    before,
                    target,
                    if (blocked) "BLOCKED" else if (category == OutputFailure.NOT_FOUND.name) "PASS" else "FAILURE",
                    reason = if (category == OutputFailure.NOT_FOUND.name) {
                        "INVALID_OUTPUT_REJECTED"
                    } else {
                        "INVALID_OUTPUT_$category"
                    },
                )
            }
        } catch (_: Exception) {
            report.put("invalidResult", "TRANSPORT_THROW")
            report.put("invalidFailure", "SYNC_IO_EXCEPTION")
            finish(
                app,
                report,
                before,
                target,
                status = "FAILURE",
                reason = "INVALID_OUTPUT_SYNC_IO_EXCEPTION",
            )
        }
    }

    private fun finish(
        app: MusxiApplication,
        report: JSONObject,
        before: Snapshot,
        target: OutputDevice,
        status: String,
        reason: String,
    ) {
        val after = capture(app)
        val invalidNotFound = report.optString("invalidFailure") == OutputFailure.NOT_FOUND.name
        val selectedCurrentSucceeded = report.optString("selectedCurrentResult") == "SUCCESS"
        val playingUnchanged = before.playing == after.playing
        val playWhenReadyUnchanged = before.playWhenReady == after.playWhenReady
        val selectedTargetStillSelected = after.devices.any { it.id == target.id && it.selected }
        val selectedTypesUnchanged = before.selectedTypes == after.selectedTypes
        val connected = after.connected
        val passed = status == "PASS" && invalidNotFound && selectedCurrentSucceeded &&
            playingUnchanged && playWhenReadyUnchanged && selectedTargetStillSelected &&
            selectedTypesUnchanged && connected

        report.put("after", after.toJson())
        report.put("invalidNotFound", invalidNotFound)
        report.put("selectedTargetStillSelected", selectedTargetStillSelected)
        report.put("selectedTypesUnchanged", selectedTypesUnchanged)
        report.put("playingUnchanged", playingUnchanged)
        report.put("playWhenReadyUnchanged", playWhenReadyUnchanged)
        report.put("positionDeltaMs", after.positionMs - before.positionMs)
        report.put("stage", "COMPLETE")

        val failedChecks = buildList {
            if (!selectedCurrentSucceeded) add("CURRENT_SELECTION")
            if (!invalidNotFound) add("INVALID_OUTPUT_NOT_FOUND")
            if (!selectedTargetStillSelected) add("SELECTED_OUTPUT_LOST")
            if (!selectedTypesUnchanged) add("SELECTED_TYPES_CHANGED")
            if (!playingUnchanged) add("PLAYING_CHANGED")
            if (!playWhenReadyUnchanged) add("PLAY_WHEN_READY_CHANGED")
            if (!connected) add("PLAYBACK_DISCONNECTED")
        }
        val finalStatus = if (connected) {
            if (passed) "PASS" else "FAILURE"
        } else {
            "BLOCKED"
        }
        val conclusion = when {
            passed -> "PASS: current output retained; invalid ID returned NOT_FOUND; playback intent unchanged"
            finalStatus == "BLOCKED" -> "BLOCKED: playback disconnected before acceptance completed"
            else -> "FAILURE: ${failedChecks.ifEmpty { listOf(reason) }.joinToString(",")}"
        }
        report.put("status", finalStatus)
        report.put("reason", reason)
        report.put("conclusion", conclusion)
        writeReport(app, report)
    }

    private fun writeSnapshot(app: MusxiApplication): Boolean {
        val snapshot = capture(app)
        val report = baseReport("output_snapshot", snapshot).apply {
            put("status", "READ_ONLY")
            put("stage", "COMPLETE")
            put("reason", "SNAPSHOT_ONLY")
            put("conclusion", "READ_ONLY: no output selection command was sent")
        }
        return writeReport(app, report)
    }

    private fun capture(app: MusxiApplication): Snapshot {
        val state = app.playback.state.value
        val devices = app.playback.availableOutputs()
        return Snapshot(
            connected = state.connected,
            playing = state.playing,
            playWhenReady = state.playWhenReady,
            positionMs = state.positionMs,
            devices = devices,
            inferred = app.playback.outputs.value.inferred,
        )
    }

    private fun baseReport(step: String, before: Snapshot) = JSONObject().apply {
        put("schemaVersion", 1)
        put("step", step)
        put("status", "RUNNING")
        put("stage", "PRECHECK")
        put("conclusion", "PENDING")
        put("before", before.toJson())
    }

    private fun failureCategory(result: Result<Unit>): String {
        val error = result.exceptionOrNull()
        return when (error) {
            null -> "NONE"
            is OutputSelectionException -> error.category.name
            else -> "OTHER"
        }
    }

    private fun writeReport(app: MusxiApplication, report: JSONObject): Boolean =
        runCatching {
            report.put("timestamp", System.currentTimeMillis())
            java.io.File(app.filesDir, REPORT_NAME).writeText(report.toString())
        }.isSuccess

    private data class Snapshot(
        val connected: Boolean,
        val playing: Boolean,
        val playWhenReady: Boolean,
        val positionMs: Long,
        val devices: List<OutputDevice>,
        val inferred: Boolean,
    ) {
        val selectedTypes: List<String> = devices.filter { it.selected }.map { it.type.name }.sorted()

        fun toJson() = JSONObject().apply {
            put("connected", connected)
            put("playing", playing)
            put("playWhenReady", playWhenReady)
            put("positionMs", positionMs)
            put("outputCount", devices.size)
            put("selectedCount", devices.count { it.selected })
            put("selectedTypes", selectedTypes.joinToString(","))
            put("inferred", inferred)
        }
    }
}
