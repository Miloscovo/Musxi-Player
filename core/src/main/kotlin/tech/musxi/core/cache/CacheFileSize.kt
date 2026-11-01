package tech.musxi.core.cache

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object CacheFileSize {
    fun bytes(directory: File): Long {
        val root = directory.toPath()
        val rootAttributes = try {
            Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return 0L
        }
        if (rootAttributes.isSymbolicLink) return 0L
        if (!rootAttributes.isDirectory) {
            throw IllegalArgumentException("Cache unavailable")
        }

        var total = 0L
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) {
                        total = saturatedAdd(total, attributes.size())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                    throw exception
                }
            },
        )
        return total
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
