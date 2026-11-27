package tech.musxi.core.localaudio

import java.util.Collections

enum class CuePreviewError {
    INVALID_ARGUMENT, LIMIT_REACHED, PERMISSION_REQUIRED, NOT_FOUND, READ_FAILED, TOO_LARGE,
    UNSUPPORTED_AUDIO, INVALID_FLAC, INVALID_CUE,
}

enum class CueAssociationStatus { MATCHED, MANUAL, MISSING, AMBIGUOUS, INVALID_AUDIO }
enum class CuePreviewIssueCode { PARSE_ERROR, MISSING_AUDIO, AMBIGUOUS_AUDIO, INVALID_AUDIO, INDEX_OUT_OF_RANGE }

/** Indices are scoped to one preview. Names are leaf display labels, never file capabilities. */
data class CueAudioCandidate(
    val index: Int,
    val name: String,
    val sampleRate: Int? = null,
    val totalSamples: Long? = null,
    val channels: Int? = null,
    val bitsPerSample: Int? = null,
    val error: CuePreviewError? = null,
) {
    val durationUs: Long? get() = if (usable) totalSamples!! * 1_000_000 / sampleRate!! else null
    internal val usable: Boolean get() = error == null && sampleRate != null && sampleRate in 1..655_350 &&
        totalSamples != null && totalSamples in 1..0xFFFFFFFFFL && channels in 1..8 && bitsPerSample in 4..32
}

data class CueFileAssociation(
    val fileIndex: Int,
    val name: String,
    val status: CueAssociationStatus,
    val candidateIndex: Int? = null,
)

data class CuePreviewTrack(
    val number: Int,
    val fileIndex: Int,
    val title: String,
    val performer: String?,
    val startFrame: Long?,
    val endFrameExclusive: Long?,
    val durationUs: Long?,
)

data class CuePreviewIssue(val code: CuePreviewIssueCode, val trackNumber: Int? = null, val fileIndex: Int? = null)

/** All collections are detached, unmodifiable snapshots. No URI, full path or parser input escapes. */
class CueImportPreview internal constructor(
    val title: String?,
    val performer: String?,
    val encoding: String,
    candidates: List<CueAudioCandidate>,
    files: List<CueFileAssociation>,
    tracks: List<CuePreviewTrack>,
    parseIssues: List<CueIssue>,
    issues: List<CuePreviewIssue>,
) {
    val candidates: List<CueAudioCandidate> = immutable(candidates)
    val files: List<CueFileAssociation> = immutable(files)
    val tracks: List<CuePreviewTrack> = immutable(tracks)
    val parseIssues: List<CueIssue> = immutable(parseIssues)
    val issues: List<CuePreviewIssue> = immutable(issues)
    val canProceed: Boolean get() = tracks.isNotEmpty() && parseIssues.isEmpty() && issues.isEmpty()
    override fun toString(): String = "CueImportPreview(tracks=${tracks.size}, canProceed=$canProceed)"
}

data class CuePreviewState(
    val revision: Long = 0,
    val running: Boolean = false,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val preview: CueImportPreview? = null,
    val error: CuePreviewError? = null,
)

/** Pure association and range checking. Bindings map CUE file index to selected candidate index. */
object CuePreviewPlanner {
    fun plan(sheet: CueSheet, candidates: List<CueAudioCandidate>, bindings: Map<Int, Int> = emptyMap()): CueImportPreview {
        require(candidates.size <= 100 && candidates.map { it.index }.toSet().size == candidates.size)
        require(candidates.all { it.index >= 0 })
        val byIndex = candidates.associateBy { it.index }
        require(bindings.keys.all { key -> sheet.files.any { it.index == key } } && bindings.values.all { it in byIndex })
        val publicCandidates = candidates.map { it.copy(name = label(it.name)) }
        val issues = mutableListOf<CuePreviewIssue>()
        val associations = sheet.files.map { file ->
            val selected = bindings[file.index]?.let(byIndex::get)
            val exact = candidates.filter { it.name == file.reference }
            val matches = if (exact.isNotEmpty()) exact else candidates.filter { it.name.equals(file.reference, ignoreCase = true) }
            val chosen = selected ?: matches.singleOrNull()
            val status = when {
                selected != null && !selected.usable -> CueAssociationStatus.INVALID_AUDIO
                selected != null -> CueAssociationStatus.MANUAL
                matches.isEmpty() -> CueAssociationStatus.MISSING
                matches.size > 1 -> CueAssociationStatus.AMBIGUOUS
                chosen?.usable != true -> CueAssociationStatus.INVALID_AUDIO
                else -> CueAssociationStatus.MATCHED
            }
            val issue = when (status) {
                CueAssociationStatus.MISSING -> CuePreviewIssueCode.MISSING_AUDIO
                CueAssociationStatus.AMBIGUOUS -> CuePreviewIssueCode.AMBIGUOUS_AUDIO
                CueAssociationStatus.INVALID_AUDIO -> CuePreviewIssueCode.INVALID_AUDIO
                else -> null
            }
            issue?.let { issues += CuePreviewIssue(it, fileIndex = file.index) }
            CueFileAssociation(file.index, label(file.reference), status, chosen?.index)
        }
        if (sheet.issues.isNotEmpty()) issues += CuePreviewIssue(CuePreviewIssueCode.PARSE_ERROR)
        val tracks = sheet.tracks.mapIndexed { index, track ->
            val audio = associations.firstOrNull { it.fileIndex == track.fileIndex }?.candidateIndex?.let(byIndex::get)
            val next = sheet.tracks.getOrNull(index + 1)?.takeIf { it.fileIndex == track.fileIndex }?.startFrame
            val start = track.startFrame
            val valid = audio?.usable == true && start != null && start >= 0 &&
                start <= LocalAudioLimits.MAX_POSITION_MS * 75 / 1_000 &&
                start * audio.sampleRate!! < audio.totalSamples!! * 75 &&
                (next == null || (next > start && next <= LocalAudioLimits.MAX_POSITION_MS * 75 / 1_000 &&
                    next * audio.sampleRate <= audio.totalSamples * 75))
            if (audio?.usable == true && !valid) issues += CuePreviewIssue(
                CuePreviewIssueCode.INDEX_OUT_OF_RANGE, track.number, track.fileIndex)
            val duration = if (valid) {
                if (next != null) (next - start!!) * 1_000_000 / 75
                else (audio!!.totalSamples!! * 75 - start!! * audio.sampleRate!!) * 1_000_000 / (audio.sampleRate * 75L)
            } else null
            CuePreviewTrack(track.number, track.fileIndex, text(track.title ?: "曲目 ${track.number}"),
                (track.performer ?: sheet.performer)?.let(::text), start, next, duration)
        }
        return CueImportPreview(sheet.title?.let(::text), sheet.performer?.let(::text), sheet.encoding,
            publicCandidates, associations, tracks, sheet.issues, issues)
    }

    private fun label(value: String): String = LocalAudioNames.clean(value.substringAfterLast('/').substringAfterLast('\\')) ?: "未命名"
    private fun text(value: String): String = LocalAudioNames.clean(value) ?: "未命名"
}

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
