package tech.musxi.player.localaudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalAudioFailure

/**
 * Optional automatic cover art for one local media file.
 *
 * Artwork is never required for playback: extraction and storage failures are reported as `null`
 * so the caller can simply continue without a cover. Cancellation is the only failure that is
 * rethrown, because it means the surrounding import was abandoned.
 */
internal interface LocalAudioArtwork {
    /**
     * Takes the embedded picture, or a video keyframe when the file has a picture-less video track,
     * and returns a lease for the stored key. The caller must close the lease after the key has been
     * committed to (or dropped from) the library, so [prune] cannot delete a file that has not been
     * recorded yet. The returned lease is `null` when the file has no usable picture.
     */
    fun prepare(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit = {}): LocalArtworkLease?

    /**
     * Stores a user-picked still picture as artwork through the same content-addressed pipeline.
     * The document is only ever read as an image, never handed to the media retriever, so a cover
     * picture cannot be mistaken for an audio or video source. `null` means "no usable picture" or
     * "image import is unsupported"; a [SecurityException] means the one-shot read grant is gone.
     */
    fun prepareImage(uri: Uri, crop: LocalArtworkCrop? = null, checkCancelled: () -> Unit = {}): LocalArtworkLease? = null

    /** Absolute file for a stored key, or `null` when the key is invalid or the file is gone. */
    fun resolve(key: String): File?

    /**
     * Deletes managed artwork that is neither in [retained] nor protected by an open lease.
     * [retained] holds artwork keys, i.e. the values of `artworkKey`, not file names.
     */
    fun prune(retained: Set<String>)
}

/** Keeps one artwork key alive until the caller has recorded it. [close] is idempotent. */
internal class LocalArtworkLease(val key: String, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Content-addressed artwork file layer under a dedicated `filesDir` directory, so covers survive
 * cache and account clean-up.
 *
 * Stored names are `<sha256 of the JPEG>.jpg`; content is identical for every item that shares a
 * cover, so one file backs all of them. A file becomes visible only through an fsync'd
 * `part-*.tmp` staging file moved with ATOMIC_MOVE, and [prune] never removes a key that a live
 * lease still protects. No source URI is stored or logged.
 */
internal class AndroidLocalAudioArtwork(
    directory: File,
    private val source: LocalArtworkFrameSource,
    private val images: LocalArtworkImageSource = LocalArtworkImageSource { null },
) : LocalAudioArtwork {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
        MediaMetadataArtworkFrameSource(context.applicationContext),
        ContentResolverLocalArtworkImages(context.applicationContext),
    )

    private val root: Path = directory.toPath().toAbsolutePath().normalize()
    private val expectedParent: Path? = root.parent?.toFile()?.canonicalFile?.toPath()
    /** Guards [pending], [staging] and every decision that deletes or publishes a managed file. */
    private val lock = Any()
    private val pending = HashMap<String, Int>()
    private val staging = HashSet<String>()

    init {
        require(expectedParent != null && root.nameCount >= 1) { "Artwork directory is not usable" }
    }

    override fun prepare(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit): LocalArtworkLease? {
        if (!ensureRoot()) return null
        try {
            checkCancelled()
            val picture = source.frameOf(uri, metadata, checkCancelled) ?: return null
            return store(picture, checkCancelled)
        } catch (error: CancellationException) {
            throw error
        } catch (_: OutOfMemoryError) {
            // A huge picture must degrade to "no cover", never take the import down with it.
            return null
        } catch (_: Exception) {
            // Artwork is optional; a broken provider, codec or disk must not stop playback.
            return null
        }
    }

    override fun prepareImage(uri: Uri, crop: LocalArtworkCrop?, checkCancelled: () -> Unit): LocalArtworkLease? {
        crop?.validate()
        if (!ensureRoot()) return null
        var input: Path? = null
        try {
            checkCancelled()
            // The same owned staging namespace protects a source spool from concurrent pruning.
            // It is never published, contains no URI, and is deleted even if decode is cancelled.
            input = createStaging()
            val picture = LocalArtworkImages.decodeImage({ images.open(uri) }, input.toFile(), crop, checkCancelled) ?: return null
            return store(picture, checkCancelled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: LocalAudioFailure) {
            throw error
        } catch (error: SecurityException) {
            // A revoked read grant is a permission problem the caller can report, not "no cover".
            throw error
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            // An unreadable or malformed selection is reported as "no cover" by the caller.
            return null
        } finally {
            input?.let { discardStaging(it) }
        }
    }

    /**
     * Scales, encodes and publishes an already-decoded picture. A lease that never reaches the
     * caller releases its pending key and staging file, so nothing leaks on failure.
     */
    private fun store(picture: Bitmap, checkCancelled: () -> Unit): LocalArtworkLease? {
        var temporary: Path? = null
        var heldKey: String? = null
        var delivered = false
        try {
            val encoded = encode(picture, checkCancelled)
            checkCancelled()
            val key = LocalArtworkImages.contentKey(encoded)
            temporary = createStaging()
            writeAndSync(temporary, encoded)
            checkCancelled()
            // The key is protected before the file can be seen, so a concurrent prune cannot delete
            // artwork that is about to be handed to the caller.
            holdKey(key)
            heldKey = key
            publish(temporary, key, encoded.size)
            delivered = true
            return LocalArtworkLease(key) { releaseKey(key) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            if (!delivered) heldKey?.let { releaseKey(it) }
            temporary?.let { discardStaging(it) }
        }
    }

    override fun resolve(key: String): File? {
        if (!KEY.matches(key)) return null
        synchronized(lock) {
            if (!rootUsable()) return null
            val candidate = root.resolve(key + SUFFIX)
            // Reject any path that left the managed directory even though the key itself is valid.
            if (candidate.parent != root || candidate.nameCount != root.nameCount + 1) return null
            if (!isManagedFile(candidate)) return null
            return candidate.toFile()
        }
    }

    override fun prune(retained: Set<String>) {
        synchronized(lock) {
            val entries = entries() ?: return
            for (path in entries) {
                val name = path.fileName?.toString() ?: continue
                when {
                    FILE_NAME.matches(name) -> {
                        val key = name.removeSuffix(SUFFIX)
                        if (key in retained || pending.containsKey(key)) continue
                        deleteOwnedEntry(path)
                    }
                    STAGING_NAME.matches(name) -> {
                        // An in-flight prepare owns its staging file even when it is not published yet.
                        if (name in staging) continue
                        deleteOwnedEntry(path)
                    }
                }
            }
        }
    }

    override fun toString(): String = "AndroidLocalAudioArtwork(redacted)"

    /** Scales and JPEG-encodes the extracted picture, recycling every bitmap it owns. */
    private fun encode(picture: Bitmap, checkCancelled: () -> Unit): ByteArray {
        val owned = ArrayList<Bitmap>(2).apply { add(picture) }
        try {
            val scaled = LocalArtworkImages.scaleToMaxEdge(picture, LocalArtworkImages.MAX_EDGE)
            if (scaled !== picture) owned += scaled
            checkCancelled()
            return LocalArtworkImages.encodeJpeg(scaled)
        } finally {
            owned.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun holdKey(key: String) {
        synchronized(lock) { pending[key] = (pending[key] ?: 0) + 1 }
    }

    private fun releaseKey(key: String) {
        synchronized(lock) {
            val count = pending[key] ?: return
            if (count <= 1) pending.remove(key) else pending[key] = count - 1
        }
    }

    private fun createStaging(): Path {
        synchronized(lock) {
            repeat(MAX_STAGING_ATTEMPTS) {
                val name = STAGING_PREFIX + UUID.randomUUID() + STAGING_SUFFIX
                val path = root.resolve(name)
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return@repeat
                staging += name
                return path
            }
        }
        throw IOException("No usable artwork staging name")
    }

    private fun discardStaging(path: Path) {
        synchronized(lock) { staging -= path.fileName.toString() }
        deleteOwnedEntry(path)
    }

    private fun writeAndSync(path: Path, bytes: ByteArray) {
        FileOutputStream(path.toFile()).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    /**
     * Publishes staging as `<key>.jpg`. An existing copy of the same key is reused rather than
     * replaced; a damaged file under that key is removed and rewritten, never trusted.
     */
    private fun publish(temporary: Path, key: String, bytes: Int) {
        val target = root.resolve(key + SUFFIX)
        synchronized(lock) {
            if (!rootUsable()) throw IOException("Artwork directory is not usable")
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                if (!isManagedFile(target)) throw IOException("Artwork target is not a managed file")
                if (Files.size(target) == bytes.toLong()) return
                if (!deleteQuietly(target)) throw IOException("Stale artwork could not be replaced")
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun ensureRoot(): Boolean {
        val parentValid = try {
            root.parent.toRealPath() == expectedParent
        } catch (_: IOException) {
            false
        }
        if (!parentValid) return false
        if (Files.isSymbolicLink(root)) return false
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.createDirectory(root) }
        return rootUsable()
    }

    private fun rootUsable(): Boolean {
        if (Files.isSymbolicLink(root)) return false
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            val real = root.toRealPath()
            val parent = expectedParent
            parent != null && real.parent == parent && real == parent.resolve(root.fileName.toString())
        } catch (_: IOException) {
            false
        }
    }

    private fun entries(): List<Path>? {
        if (!rootUsable()) return null
        return try {
            val children = ArrayList<Path>()
            Files.newDirectoryStream(root).use { stream -> for (entry in stream) children.add(entry) }
            children
        } catch (_: IOException) {
            null
        }
    }

    private fun isManagedFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    /** Deletes a file or link, never a directory, so a pruned name cannot remove a subtree. */
    private fun deleteOwnedEntry(path: Path) {
        val regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        if (!regular && !Files.isSymbolicLink(path)) return
        deleteQuietly(path)
    }

    private fun deleteQuietly(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val DIRECTORY_NAME = "local-artwork-v1"
        const val SUFFIX = ".jpg"
        const val STAGING_PREFIX = "part-"
        const val STAGING_SUFFIX = ".tmp"
        const val MAX_STAGING_ATTEMPTS = 4
        val KEY = Regex("[0-9a-f]{64}")
        val FILE_NAME = Regex("[0-9a-f]{64}\\.jpg")
        val STAGING_NAME = Regex("part-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
    }
}

/** Produces one already-bounded picture for a media file; the caller recycles the bitmap. */
internal fun interface LocalArtworkFrameSource {
    fun frameOf(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit): Bitmap?
}

/** Opens one read of a picked image document; the caller closes the stream. */
internal fun interface LocalArtworkImageSource {
    fun open(uri: Uri): InputStream?
}

/**
 * Reads a picked image through the calling app's temporary read grant. The stream lives for the
 * decode only: no permission is persisted, no URI is stored, and no media retriever is involved.
 */
internal class ContentResolverLocalArtworkImages(context: Context) : LocalArtworkImageSource {
    private val resolver = context.applicationContext.contentResolver

    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun toString(): String = "ContentResolverLocalArtworkImages(redacted)"
}

/** Platform retriever adapter: embedded picture first, then one keyframe for video files. */
internal class MediaMetadataArtworkFrameSource(context: Context) : LocalArtworkFrameSource {
    private val app = context.applicationContext

    override fun frameOf(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            checkCancelled()
            val embedded = retriever.embeddedPicture
            if (embedded != null && embedded.isNotEmpty() && embedded.size <= LocalArtworkImages.MAX_EMBEDDED_BYTES) {
                LocalArtworkImages.decodeBounded(embedded, LocalArtworkImages.MAX_DECODE_EDGE)?.let { return it }
            }
            checkCancelled()
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val durationMs = metadata.durationMs
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frameUs = artworkFrameTimeMs(durationMs) * 1000
            checkCancelled()
            // Without known dimensions there is no bounded request to make, so the cover is
            // skipped instead of decoding an unknown, possibly 8K, frame.
            val videoSize = videoDimensions(retriever) ?: return null
            val scaledApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            val request = LocalArtworkImages.frameRequest(videoSize.first, videoSize.second, scaledApi)
                ?: return null
            if (scaledApi) {
                return retriever.getScaledFrameAtTime(
                    frameUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    request.first,
                    request.second,
                )
            }
            val frame = retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scaled = LocalArtworkImages.scaleToMaxEdge(frame, LocalArtworkImages.MAX_EDGE)
            if (scaled !== frame) frame.recycle()
            return scaled
        } catch (error: CancellationException) {
            throw error
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Upright video dimensions, or `null` when the container does not report them. */
    private fun videoDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int>? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val upright = rotation == 90 || rotation == 270
        return if (upright) height to width else width to height
    }
}

/**
 * Frame position for a video cover: 10% into the file but never before the first second, and always
 * short of the end so a very short clip still yields a frame.
 */
internal fun artworkFrameTimeMs(durationMs: Long?): Long {
    if (durationMs == null || durationMs <= 0L) return DEFAULT_FRAME_TIME_MS
    val preferred = max(FIRST_FRAME_TIME_MS, durationMs / 10)
    val limit = max(0L, durationMs - FRAME_TAIL_GUARD_MS)
    return minOf(preferred, limit)
}

private const val FIRST_FRAME_TIME_MS = 1_000L
private const val DEFAULT_FRAME_TIME_MS = 1_000L
private const val FRAME_TAIL_GUARD_MS = 200L

/** Bitmap and content-key helpers, split out so they can be tested without a media codec. */
internal object LocalArtworkImages {
    /** Longest stored edge. */
    const val MAX_EDGE = 512
    /** Decoding budget before the final scale, so a huge embedded picture never hits full size. */
    const val MAX_DECODE_EDGE = 1024
    const val JPEG_QUALITY = 90
    /** An embedded picture larger than this is skipped instead of being decoded. */
    const val MAX_EMBEDDED_BYTES = 16 * 1024 * 1024
    /**
     * A user-picked picture is rejected before any pixel is allocated when it claims more pixels
     * than this, so a decompression bomb cannot exhaust the heap through one cover.
     */
    const val MAX_PICKED_PIXELS = 32_000_000L
    /** A picked picture whose longer edge exceeds this is skipped as unusable for a 512 px cover. */
    const val MAX_PICKED_EDGE = 16_384
    /** Compressed bytes read from a picked document; a non-image stream is cut off here. */
    const val MAX_PICKED_BYTES = 24 * 1024 * 1024
    /**
     * Budget for an unscaled video frame on API 26, where no scaled retriever exists: about 16 MB
     * at ARGB_8888. Larger frames are skipped so a 4K/8K clip cannot exhaust the heap.
     */
    const val MAX_FULL_FRAME_PIXELS = 4_000_000L

    /**
     * Frame size to request for a video cover, or `null` when the frame must be skipped.
     *
     * With the scaled API the request is the final cover size, so no oversized bitmap is ever
     * allocated. Without it (API 26) only frames inside [MAX_FULL_FRAME_PIXELS] are decoded.
     */
    fun frameRequest(width: Int, height: Int, scaledApi: Boolean): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        if (scaledApi) return fitWithin(width, height, MAX_EDGE)
        return if (width.toLong() * height <= MAX_FULL_FRAME_PIXELS) width to height else null
    }

    /** Smallest power-of-two sample factor that keeps both decoded edges within [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_DECODE_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }

    /** Size that keeps the aspect ratio with the longest edge at most [maxEdge]. */
    fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Decodes [bytes] with an in-sample bounds pass and returns a bitmap within [maxEdge]. */
    fun decodeBounded(bytes: ByteArray, maxEdge: Int = MAX_DECODE_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }) ?: return null
        val scaled = scaleToMaxEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Whether a picked picture's reported size is inside the cover budget. */
    fun pickedSizeAccepted(width: Int, height: Int, maxPixels: Long = MAX_PICKED_PIXELS,
                           maxLongEdge: Int = MAX_PICKED_EDGE): Boolean {
        if (width <= 0 || height <= 0) return false
        if (maxOf(width, height) > maxLongEdge) return false
        return width.toLong() * height <= maxPixels
    }

    /**
     * Spools at most 24 MiB through one provider open, then reads bounds/EXIF/pixels from that
     * immutable private file. The caller owns and cleans [sourceFile], including failed reads.
     * A crop uses sampled region decoding; the full image is never decoded to crop it later.
     * Oversized, non-image and unreadable input returns `null`; invalid crops throw INVALID_TARGET.
     */
    fun decodeImage(open: () -> InputStream?, sourceFile: File, crop: LocalArtworkCrop? = null,
                    checkCancelled: () -> Unit = {}, maxEdge: Int = MAX_DECODE_EDGE,
                    maxPixels: Long = MAX_PICKED_PIXELS, maxLongEdge: Int = MAX_PICKED_EDGE,
                    maxBytes: Int = MAX_PICKED_BYTES): Bitmap? {
        try {
            crop?.validate()
            checkCancelled()
            (openBounded(open, maxBytes) ?: return null).use { input ->
                FileOutputStream(sourceFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkCancelled()
                        var count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            val single = input.read()
                            if (single < 0) break
                            buffer[0] = single.toByte()
                            count = 1
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            checkCancelled()
            return LocalArtworkImageDecoder.decode(sourceFile, crop, checkCancelled, maxEdge, maxPixels, maxLongEdge)
        } catch (error: LocalAudioFailure) {
            throw error
        } catch (_: IOException) {
            // A truncated, unbounded or non-image stream is "no cover", not an import failure.
            return null
        }
    }

    /** Opens the picked document under a byte cap; `null` when the provider cannot be read. */
    private fun openBounded(open: () -> InputStream?, maxBytes: Int): InputStream? =
        open()?.let { BoundedReadStream(it, maxBytes) }

    /** Returns [bitmap] itself when it already fits, otherwise a new scaled copy. */
    fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (width, height) = fitWithin(bitmap.width, bitmap.height, maxEdge)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            throw IOException("Artwork JPEG encoding failed")
        }
        return output.toByteArray()
    }

    /** 64 lowercase hex characters of the SHA-256 of the stored bytes. */
    fun contentKey(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0xf]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Caps the bytes a decoder may pull from a picked document, so a provider that streams
     * unbounded data cannot decide the read size. The stream is closed by the caller.
     */
    internal class BoundedReadStream(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(bytes, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }

        override fun skip(byteCount: Long): Long = super.skip(byteCount).also { if (it > 0) account(it) }

        private fun account(bytes: Long) {
            total += bytes
            if (total > limit) throw IOException("Picked image exceeds the read budget")
        }
    }
}
