package uk.shusek.krwa.experimental.dircache.internal

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object PathUtils {
    @JvmStatic
    @Throws(IOException::class)
    fun recursiveDelete(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        Files.delete(file)
                    } catch (_: IOException) {
                        // Ignore.
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.delete(dir)
                    } catch (_: IOException) {
                        // Ignore.
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
