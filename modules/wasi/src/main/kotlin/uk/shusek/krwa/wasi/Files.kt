package uk.shusek.krwa.wasi

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class Files private constructor() {
    companion object {
        /** Copy directory recursively, including POSIX file permissions. */
        @JvmStatic
        @Throws(IOException::class)
        fun copyDirectory(source: Path, target: Path) {
            java.nio.file.Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    @Throws(IOException::class)
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (java.nio.file.Files.isSymbolicLink(dir)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }

                        val directory = target.resolve(source.relativize(dir).toString())

                        if (directory.toString() != "/") {
                            var attributes = emptyArray<FileAttribute<*>>()
                            val attributeView =
                                java.nio.file.Files.getFileAttributeView(
                                    dir,
                                    PosixFileAttributeView::class.java,
                                )
                            if (attributeView != null) {
                                val permissions = attributeView.readAttributes().permissions()
                                attributes =
                                    arrayOf(PosixFilePermissions.asFileAttribute(permissions))
                            }

                            java.nio.file.Files.createDirectory(directory, *attributes)
                        }

                        return FileVisitResult.CONTINUE
                    }

                    @Throws(IOException::class)
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val relative = source.relativize(file).toString().replace("\\", "/")
                        val path = target.resolve(relative)
                        java.nio.file.Files.copy(file, path, StandardCopyOption.COPY_ATTRIBUTES)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}
