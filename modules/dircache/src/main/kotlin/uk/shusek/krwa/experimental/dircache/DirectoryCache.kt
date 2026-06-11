package uk.shusek.krwa.experimental.dircache

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern
import uk.shusek.krwa.compiler.Cache
import uk.shusek.krwa.experimental.dircache.internal.PathUtils

/** Disk-backed sharded file cache. */
open class DirectoryCache(baseDir: Path) : Cache {
    private val baseDir: Path = Objects.requireNonNull(baseDir, "baseDir")
    private val tmpRoot: Path = baseDir.resolve(".tmp")

    /**
     * Return the cached data for the given key if it exists, else null. Does not create anything.
     */
    @Throws(IOException::class)
    override fun get(key: String): ByteArray? {
        return try {
            val target = toFilePath(key)
            if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
        } catch (_: IOException) {
            // If we can't read it, then treat it like it not being in the cache.
            null
        }
    }

    /**
     * Atomically publish data into the cache location for the key. If another thread/process
     * already published for this key then this is a no-op.
     *
     * @param key "algo:digest"
     * @param data the data to cache
     */
    @Throws(IOException::class)
    override fun putIfAbsent(key: String, data: ByteArray) {
        Objects.requireNonNull(data, "data")

        val finalPath = toFilePath(key)

        // Early return if file already exists (the "if absent" check)
        if (Files.isRegularFile(finalPath)) {
            return
        }

        val parent = finalPath.parent ?: throw IOException("Cannot determine parent for $finalPath")

        Files.createDirectories(tmpRoot)
        val tmpFile = Files.createTempFile(tmpRoot, "f-", ".tmp")
        try {
            // Write the data to temp file
            Files.write(tmpFile, data)

            // Ensure parent exists before atomic move.
            Files.createDirectories(parent)

            // Move it - but check again before moving (double-check pattern)
            // Another thread might have created it between our check and now
            if (!Files.isRegularFile(finalPath)) {
                Files.move(tmpFile, finalPath, ATOMIC_MOVE)
            }
            // If file exists now, we'll just delete the temp file in finally
        } catch (e: FileSystemException) {
            // Did another process beat us to creating the cache entry?
            if (Files.isRegularFile(finalPath)) {
                return
            }
            throw e
        } catch (e: UncheckedIOException) {
            throw e.cause ?: e
        } finally {
            PathUtils.recursiveDelete(tmpFile)
        }
    }

    /*
     * baseDir / algo / first 2 chars of digest / remainder of digest.jar
     * Validates the digest.
     */
    protected open fun toFilePath(key: String): Path {
        Objects.requireNonNull(key, "key")
        val colon = key.indexOf(':')
        if (colon <= 0 || colon == key.length - 1) {
            throw IllegalArgumentException("Key must be in form '<algo>:<hex>'")
        }
        val algo = key.substring(0, colon).lowercase(Locale.ROOT)
        var digest = key.substring(colon + 1)

        if (!ALLOWED_DIGEST_CHARS_REGEX.matcher(digest).matches()) {
            throw IllegalArgumentException("Digest must match $ALLOWED_DIGEST_CHARS")
        }

        // Digest will be base64 chars, convert so they are safe to use for file names.
        digest = digest.replace('+', '-').replace('/', '_').replace("=", "")

        if (digest.length < 2) {
            throw IllegalArgumentException(
                "Digest must be at least 2 hex chars (got ${digest.length})"
            )
        }

        val lower = digest.lowercase(Locale.ROOT)
        val shard = lower.substring(0, 2)
        val remainder = lower.substring(2)

        val algoDir = baseDir.resolve(algo)
        val shardDir = algoDir.resolve(shard)
        val basePath = if (remainder.isEmpty()) shardDir else shardDir.resolve(remainder)
        return basePath.resolveSibling("${basePath.fileName}.jar")
    }

    private companion object {
        private const val ALLOWED_DIGEST_CHARS = "^[A-Za-z0-9+_\\-/]+=$"
        private val ALLOWED_DIGEST_CHARS_REGEX = Pattern.compile(ALLOWED_DIGEST_CHARS)
    }
}
