package tech.musxi.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import tech.musxi.core.localaudio.DsfFileInfo
import tech.musxi.core.localaudio.DsfFileParser
import tech.musxi.core.localaudio.DsfParseFailure
import tech.musxi.core.localaudio.DsfPlaybackSupport
import tech.musxi.core.localaudio.LocalAudioLimits
import java.io.IOException

/** Experimental local DSF -> raw PCM bridge. The service factory keeps this disabled by default. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class DsfPcmExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var info: DsfFileInfo? = null
    private var decoder: DsdPcmDecoder? = null
    private var block = ByteArray(0)
    private var pcm = ByteArray(0)
    private var pcmSize = 0
    private var packetFirstFrame = 0L
    private var blockSample = 0L
    private var rawFrame = 0L
    private var firstFrame = 0L
    private var requestedTimeUs = 0L
    private var ended = false

    override fun sniff(input: ExtractorInput): Boolean {
        val bytes = ByteArray(4)
        return try {
            input.peekFully(bytes, 0, bytes.size, true) && bytes.contentEquals(byteArrayOf(68, 83, 68, 32))
        } catch (_: java.io.EOFException) { false }
        finally { input.resetPeekPosition() }
    }

    override fun init(output: ExtractorOutput) { this.output = output }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        if (info == null) {
            if (input.position != 0L) { seekPosition.position = 0; return Extractor.RESULT_SEEK }
            val bytes = ByteArray(DsfFileParser.HEADER_SIZE)
            input.readFully(bytes, 0, bytes.size)
            val parsed = try { DsfFileParser.parse(bytes, input.length.takeIf { it >= 0 }) }
                catch (_: DsfParseFailure) { throw IOException("Invalid DSF container") }
            if (parsed.fileSize > LocalAudioLimits.MAX_FILE_BYTES || parsed.durationMs > LocalAudioLimits.MAX_POSITION_MS)
                throw IOException("DSF exceeds local limits")
            if (!DsfPlaybackSupport.supports(parsed))
                throw IOException("Unsupported experimental DSD format")
            info = parsed
            decoder = DsdPcmDecoder(parsed.sampleRateHz, parsed.channelCount, parsed.leastSignificantBitFirst)
            block = ByteArray(parsed.blockSizePerChannel * parsed.channelCount)
            // At most one decoded block plus the small filter tail, never proportional to file size.
            pcm = ByteArray((32768 / decoder!!.decimation + decoder!!.latencyFrames + 1) * parsed.channelCount * 4)
            track = output.track(0, C.TRACK_TYPE_AUDIO)
            track.format(Format.Builder().setSampleMimeType("audio/raw").setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setSampleRate(OUTPUT_RATE).setChannelCount(parsed.channelCount).setMaxInputSize(pcm.size).build())
            output.endTracks()
            output.seekMap(DsfPcmSeekMap(parsed))
            configureSeek(requestedTimeUs)
        }
        val source = info!!
        if (ended) return Extractor.RESULT_END_OF_INPUT
        val expected = source.dataOffset + blockSample / SAMPLES_PER_BLOCK * block.size
        if (input.position != expected) { seekPosition.position = expected; return Extractor.RESULT_SEEK }
        pcmSize = 0
        if (blockSample < source.sampleCount) {
            // Even the last group is padded in DSF. Decode only the declared valid sample count.
            input.readFully(block, 0, block.size)
            val valid = minOf(SAMPLES_PER_BLOCK, source.sampleCount - blockSample).toInt()
            decoder!!.decodeBlock(block, valid) { frame ->
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                appendFrame(frame)
            }
            blockSample += SAMPLES_PER_BLOCK
        } else {
            decoder!!.drain(::appendFrame)
            ended = true
        }
        if (pcmSize > 0) {
            track.sampleData(ParsableByteArray(pcm, pcmSize), pcmSize)
            track.sampleMetadata(frameTimeUs(packetFirstFrame), C.BUFFER_FLAG_KEY_FRAME, pcmSize, 0, null)
        }
        return if (ended) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun appendFrame(frame: FloatArray) {
        val converted = rawFrame++ - decoder!!.latencyFrames
        val source = info!!
        if (converted < firstFrame || converted >= source.sampleCount / decoder!!.decimation) return
        if (pcmSize == 0) packetFirstFrame = converted
        for (sample in frame) {
            if (!sample.isFinite()) throw IOException("Invalid DSD PCM sample")
            val bits = sample.toRawBits()
            if (pcmSize + 4 > pcm.size) throw IOException("DSD PCM buffer exceeded")
            repeat(4) { byte -> pcm[pcmSize++] = (bits ushr (byte * 8)).toByte() }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        requestedTimeUs = timeUs.coerceAtLeast(0)
        if (info != null) configureSeek(requestedTimeUs)
    }

    private fun configureSeek(timeUs: Long) {
        val plan = DsfPcmSeekMap(info!!).plan(timeUs)
        blockSample = plan.warmSample
        firstFrame = plan.firstFrame
        rawFrame = blockSample / decoder!!.decimation
        decoder!!.reset()
        pcmSize = 0
        ended = false
    }

    override fun release() {
        info = null; decoder = null; block = ByteArray(0); pcm = ByteArray(0); pcmSize = 0; ended = true
    }

    internal companion object {
        const val OUTPUT_RATE = DsfPlaybackSupport.PCM_RATE_HZ
        const val SAMPLES_PER_BLOCK = 32_768L
        fun frameTimeUs(frame: Long): Long = frame / OUTPUT_RATE * 1_000_000 + frame % OUTPUT_RATE * 1_000_000 / OUTPUT_RATE
    }
}

internal data class DsfPcmSeekPlan(val firstFrame: Long, val warmSample: Long, val offset: Long)

/** Seek timestamps stay in source time. Read one prior group to warm every supported FIR stage. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class DsfPcmSeekMap(private val source: DsfFileInfo) : SeekMap {
    init {
        require(DsfPlaybackSupport.supports(source))
        require(source.fileSize <= LocalAudioLimits.MAX_FILE_BYTES && source.durationMs <= LocalAudioLimits.MAX_POSITION_MS)
    }
    private val ratio = source.sampleRateHz / DsfPcmExtractor.OUTPUT_RATE
    private val totalFrames = source.sampleCount / ratio
    override fun isSeekable() = true
    override fun getDurationUs() = DsfPcmExtractor.frameTimeUs(totalFrames)
    fun plan(timeUs: Long): DsfPcmSeekPlan {
        val boundedUs = timeUs.coerceIn(0, getDurationUs())
        val frame = if (timeUs >= getDurationUs()) totalFrames else
            (boundedUs / 1_000_000 * DsfPcmExtractor.OUTPUT_RATE +
                boundedUs % 1_000_000 * DsfPcmExtractor.OUTPUT_RATE / 1_000_000).coerceAtMost(totalFrames)
        val group = frame * ratio / DsfPcmExtractor.SAMPLES_PER_BLOCK
        val warmGroup = (group - 1).coerceAtLeast(0)
        return DsfPcmSeekPlan(frame, warmGroup * DsfPcmExtractor.SAMPLES_PER_BLOCK,
            source.dataOffset + warmGroup * source.blockSizePerChannel * source.channelCount)
    }
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val plan = plan(timeUs)
        return SeekMap.SeekPoints(SeekPoint(DsfPcmExtractor.frameTimeUs(plan.firstFrame), plan.offset))
    }
}
