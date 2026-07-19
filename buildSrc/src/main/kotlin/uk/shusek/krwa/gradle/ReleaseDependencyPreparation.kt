package uk.shusek.krwa.gradle

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.Project

fun Project.prepareVerifiedReleaseDownload(
    description: String,
    url: String,
    target: File,
    expectedSha256: String,
) {
    if (target.isFile && releaseDependencySha256(target) == expectedSha256) {
        return
    }
    if (gradle.startParameter.isOffline) {
        throw GradleException(
            "$description is missing or failed SHA-256 verification at " +
                "${target.invariantSeparatorsPath}. Run " +
                "'./gradlew --no-daemon prepareReleaseDependencies' while online, then retry " +
                "releaseGate with --offline.",
        )
    }

    target.parentFile.mkdirs()
    val temporary = target.resolveSibling("${target.name}.download.tmp")
    try {
        Files.deleteIfExists(temporary.toPath())
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = RELEASE_DEPENDENCY_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = RELEASE_DEPENDENCY_READ_TIMEOUT_MILLIS
        try {
            connection.getInputStream().use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }

        val actualSha256 = releaseDependencySha256(temporary)
        if (actualSha256 != expectedSha256) {
            throw GradleException(
                "$description SHA-256 mismatch: expected $expectedSha256, got $actualSha256",
            )
        }
        moveReleaseDependencyAtomically(temporary, target)
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

fun releaseDependencySha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun moveReleaseDependencyAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

private const val RELEASE_DEPENDENCY_CONNECT_TIMEOUT_MILLIS: Int = 30_000
private const val RELEASE_DEPENDENCY_READ_TIMEOUT_MILLIS: Int = 5 * 60_000
