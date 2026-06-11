package uk.shusek.krwa.tools.wasm

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

class Files private constructor() {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun copyDirectory(source: Path, target: Path) {
            java.nio.file.Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val relative = source.relativize(file).toString().replace("\\", "/")
                        val path = target.resolve(relative)
                        java.nio.file.Files.copy(file, path, StandardCopyOption.REPLACE_EXISTING)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}
