package tech.musxi.core.wallpaper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32

interface WallpaperStore {
    fun read(): SavedWallpaper
    fun stage(normalizedImage: File): WallpaperAsset
    fun write(saved: SavedWallpaper)
    fun resolve(asset: WallpaperAsset): File
    fun prune(keep: Set<WallpaperAsset>)
}

/**
 * Immutable images and a small atomic manifest form one local setting. Publish the new manifest
 * before collecting the old image, so interrupted imports/applications retain a committed image.
 * The caller supplies a dedicated private files directory, never an image-cache directory.
 */
class FileWallpaperStore(directory: File, slot: WallpaperSlot = WallpaperSlot.PLAYER) : WallpaperStore {
    private val manifestName = slot.manifestName
    private val root = directory.absoluteFile.toPath().normalize().toFile()
    private val parent = root.parentFile.canonicalFile

    @Synchronized
    override fun read(): SavedWallpaper {
        ensureRoot()
        val manifest = child(manifestName)
        if (!manifest.exists()) return SavedWallpaper()
        regular(manifest)
        if (manifest.length() !in 20..MAX_MANIFEST_BYTES) unavailable()
        return try {
            val bytes = manifest.readBytes()
            val payload = bytes.copyOf(bytes.size - 8)
            val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
            if (CRC32().apply { update(payload) }.value != checksum) unavailable()
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) unavailable()
                val version = input.readInt()
                if (version != VERSION_LEGACY && version != VERSION) unavailable()
                val asset = if (input.readBoolean()) WallpaperAsset(
                    input.readUTF(),
                    WallpaperImageInfo(WallpaperFormat.valueOf(input.readUTF()), input.readInt(), input.readInt()),
                    input.readLong(),
                ) else null
                val enabled = input.readBoolean()
                val scope = WallpaperScope.valueOf(input.readUTF())
                val strength = input.readInt()
                // Legacy manifests predate framing: they read as centered and unzoomed, not corrupt.
                val frame = if (version == VERSION) {
                    WallpaperFrame(input.readFloat(), input.readFloat(), input.readFloat())
                } else WallpaperFrame()
                val preferences = WallpaperPreferences(enabled, scope, strength, frame)
                try { preferences.validate() } catch (_: WallpaperFailure) { unavailable() }
                if (input.available() != 0) unavailable()
                SavedWallpaper(asset, preferences).also { asset?.let(::resolve) }
            }
        } catch (error: WallpaperFailure) {
            throw error
        } catch (_: Exception) {
            unavailable()
        }
    }

    @Synchronized
    override fun stage(normalizedImage: File): WallpaperAsset {
        ensureRoot()
        regular(normalizedImage)
        if (normalizedImage.length() !in 1..WallpaperLimits.MAX_DISPLAY_BYTES) {
            throw WallpaperFailure(WallpaperError.IMAGE_TOO_LARGE)
        }
        val temporary = File.createTempFile(".wallpaper-", ".tmp", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            normalizedImage.inputStream().use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > WallpaperLimits.MAX_DISPLAY_BYTES) throw WallpaperFailure(WallpaperError.IMAGE_TOO_LARGE)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val info = WallpaperImageInspector.inspect(temporary)
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val asset = try { WallpaperAsset(id, info, total) }
            catch (_: IllegalArgumentException) { throw WallpaperFailure(WallpaperError.IMAGE_TOO_LARGE) }
            val target = child(asset.fileName)
            if (target.exists()) regular(target)
            atomicReplace(temporary, target)
            return asset
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun write(saved: SavedWallpaper) {
        saved.preferences.validate()
        ensureRoot()
        saved.asset?.let(::resolve)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeBoolean(saved.asset != null)
                saved.asset?.let {
                    output.writeUTF(it.id)
                    output.writeUTF(it.info.format.name)
                    output.writeInt(it.info.width)
                    output.writeInt(it.info.height)
                    output.writeLong(it.bytes)
                }
                output.writeBoolean(saved.preferences.enabled)
                output.writeUTF(saved.preferences.scope.name)
                output.writeInt(saved.preferences.strength)
                output.writeFloat(saved.preferences.frame.centerX)
                output.writeFloat(saved.preferences.frame.centerY)
                output.writeFloat(saved.preferences.frame.zoom)
            }
        }.toByteArray()
        val temporary = File.createTempFile(".wallpaper-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.write(payload)
                output.writeLong(CRC32().apply { update(payload) }.value)
                output.flush()
                file.fd.sync()
            }
            val target = child(manifestName)
            if (target.exists()) regular(target)
            atomicReplace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun resolve(asset: WallpaperAsset): File {
        ensureRoot()
        val file = child(asset.fileName)
        regular(file)
        if (file.length() != asset.bytes || WallpaperImageInspector.inspect(file) != asset.info) unavailable()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != asset.id) unavailable()
        return file
    }

    @Synchronized
    override fun prune(keep: Set<WallpaperAsset>) {
        ensureRoot()
        val retained = keep.map { it.fileName }.toSet()
        root.listFiles()?.forEach { file ->
            if ((MANAGED.matches(file.name) || TEMPORARY.matches(file.name)) && file.name !in retained &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                if (!file.delete()) throw IOException("Wallpaper cleanup failed")
            }
        }
    }

    private fun ensureRoot() {
        if (root.parentFile.canonicalFile != parent || Files.isSymbolicLink(root.toPath())) unavailable()
        if (!root.exists() && !root.mkdirs()) throw IOException("Wallpaper storage is unavailable")
        if (!root.isDirectory || root.canonicalFile != File(parent, root.name)) unavailable()
    }

    private fun child(name: String): File = File(root, name).also {
        if (it.canonicalFile.parentFile != root.canonicalFile || Files.isSymbolicLink(it.toPath())) unavailable()
    }

    private fun regular(file: File) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) unavailable()
    }

    private fun atomicReplace(source: File, target: File) {
        // Unsupported atomic replacement is a storage failure; never remove the old manifest first.
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unavailable(): Nothing = throw WallpaperFailure(WallpaperError.SAVED_IMAGE_UNAVAILABLE)

    private companion object {
        const val MAGIC = 0x4d585750
        const val VERSION_LEGACY = 1
        const val VERSION = 2
        const val MAX_MANIFEST_BYTES = 1024L
        val MANAGED = Regex("[0-9a-f]{64}\\.(jpg|png|webp)")
        val TEMPORARY = Regex("\\.wallpaper-[A-Za-z0-9-]+\\.tmp")
    }
}
