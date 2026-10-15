package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * A guest must not be able to leave a symbolic link in a writable preopen that points outside it.
 * Host code that follows links in that directory, such as `java.io.File.deleteRecursively` used to
 * clear a plugin cache, would otherwise read, write or delete host files outside the sandbox.
 */
class WasiSymlinkConfinementTest {
    enum class Host {
        PREVIEW2,
        PREVIEW3,
    }

    @TestFactory
    fun symlinkAtRefusesTargetsThatClimb(): List<DynamicTest> =
        forEachHost(::symlinkAtRefusesTargetsThatClimbOn)

    private fun symlinkAtRefusesTargetsThatClimbOn(host: Host) {
        withSandbox(host) { sandbox, outside, guest ->
            Files.createDirectories(sandbox.resolve("a/b"))

            for ((target, link) in
                listOf(
                    "../outside/secret.txt" to "leak",
                    ".." to "parent.cleanup",
                    "../../../../.." to "x",
                    // Lands inside the preopen today, but escapes once `a/b` is renamed to `b`.
                    "../../secret.txt" to "a/b/link",
                    "a/../../outside" to "normalized",
                    "..\\outside\\secret.txt" to "backslash",
                    "/etc/passwd" to "absolute",
                    "C:\\outside" to "drive",
                )
            ) {
                assertEquals("not-permitted", guest.symlinkError(target, link), "symlink-at $target -> $link")
                assertFalse(Files.exists(sandbox.resolve(link), LinkOption.NOFOLLOW_LINKS), link)
            }

            // A link to its own directory is confined, but `self/..` climbs one level more than
            // its lexical form suggests, so `..` after a link component is refused too.
            guest.symlink(".", "self")
            assertEquals("not-permitted", guest.symlinkError("self/../outside", "chain"))
            assertFalse(Files.exists(sandbox.resolve("chain"), LinkOption.NOFOLLOW_LINKS))
            guest.unlink("self")

            assertEveryLinkStaysInside(sandbox)
            assertEquals("secret", Files.readString(outside.resolve("secret.txt")))
        }
    }

    @TestFactory
    fun confinedLinksStayInsideWhenTheGuestMovesThem(): List<DynamicTest> =
        forEachHost(::confinedLinksStayInsideWhenTheGuestMovesThemOn)

    private fun confinedLinksStayInsideWhenTheGuestMovesThemOn(host: Host) {
        withSandbox(host) { sandbox, outside, guest ->
            Files.createDirectories(sandbox.resolve("a/b"))
            guest.symlink("data.txt", "a/b/down")
            guest.symlink("down", "a/b/chain")

            guest.rename("a/b", "b")
            guest.rename("b/down", "down")
            guest.rename("b/chain", "chain")

            assertEveryLinkStaysInside(sandbox)

            // The host clears the guest-writable directory and follows links while doing so.
            sandbox.toFile().deleteRecursively()
            assertEquals("secret", Files.readString(outside.resolve("secret.txt")))
        }
    }

    @TestFactory
    fun hostCleanupCannotReachOutsideAfterEscapeAttempts(): List<DynamicTest> =
        forEachHost(::hostCleanupCannotReachOutsideAfterEscapeAttemptsOn)

    private fun hostCleanupCannotReachOutsideAfterEscapeAttemptsOn(host: Host) {
        withSandbox(host) { sandbox, outside, guest ->
            Files.createDirectories(sandbox.resolve("a/b"))
            for ((target, link) in
                listOf(
                    "../outside" to "outside-link",
                    ".." to "parent-link",
                    "../../outside" to "a/b/outside-link",
                )
            ) {
                assertEquals("not-permitted", guest.symlinkError(target, link), "symlink-at $target -> $link")
            }
            guest.rename("a/b", "b")

            // The same cleanup as an embedding host that wipes a plugin cache.
            sandbox.toFile().deleteRecursively()

            assertEquals("secret", Files.readString(outside.resolve("secret.txt")))
        }
    }

    @TestFactory
    fun downwardSymlinkChainsRemainUsable(): List<DynamicTest> =
        forEachHost(::downwardSymlinkChainsRemainUsableOn)

    private fun downwardSymlinkChainsRemainUsableOn(host: Host) {
        withSandbox(host) { sandbox, _, guest ->
            Files.createDirectories(sandbox.resolve("sub"))
            Files.writeString(sandbox.resolve("sub/data.txt"), "data", StandardCharsets.UTF_8)

            guest.symlink("sub", "dir-link")
            guest.symlink("dir-link/data.txt", "file-link")
            guest.symlink("./sub//data.txt", "dotted-link")

            guest.open("file-link", pathFlags = listOf("symlink-follow"))
            assertEquals("dir-link/data.txt", guest.readlink("file-link"))
            assertEquals("data", Files.readString(sandbox.resolve("file-link")))
            assertEquals("data", Files.readString(sandbox.resolve("dotted-link")))
        }
    }

    @TestFactory
    fun openAtDoesNotCreateThroughADanglingLinkThatLeavesThePreopen(): List<DynamicTest> =
        forEachHost(::openAtDoesNotCreateThroughADanglingLinkThatLeavesThePreopenOn)

    private fun openAtDoesNotCreateThroughADanglingLinkThatLeavesThePreopenOn(host: Host) {
        withSandbox(host) { sandbox, outside, guest ->
            // A link the guest cannot create itself, for example one left by the host.
            createSymlinkOrSkip(sandbox.resolve("dangling"), Path.of("..", "outside", "created.txt"))

            for (pathFlags in listOf(emptyList(), listOf("symlink-follow"))) {
                guest.openError(
                    "dangling",
                    pathFlags = pathFlags,
                    openFlags = listOf("create"),
                    descriptorFlags = listOf("write"),
                )
            }

            assertFalse(Files.exists(outside.resolve("created.txt"), LinkOption.NOFOLLOW_LINKS))
        }
    }

    private fun forEachHost(test: (Host) -> Unit): List<DynamicTest> =
        Host.entries.map { host -> dynamicTest(host.name) { test(host) } }

    private fun withSandbox(
        host: Host,
        block: (sandbox: Path, outside: Path, guest: GuestFilesystem) -> Unit,
    ) {
        val root = Files.createTempDirectory("krwa-wasi-symlink-confinement")
        val sandbox = Files.createDirectories(root.resolve("sandbox"))
        val outside = Files.createDirectories(root.resolve("outside"))
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8)
            createSymlinkOrSkip(sandbox.resolve("probe"), Path.of("probe-target"))
            Files.delete(sandbox.resolve("probe"))

            val imports = CapturingHostImports()
            val wasi: AutoCloseable =
                when (host) {
                    Host.PREVIEW2 ->
                        WasiPreview2.builder()
                            .withPreopenedDirectory("/", sandbox.toString())
                            .build()
                            .also { it.install(imports) }
                    Host.PREVIEW3 ->
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", sandbox.toString())
                            .build()
                            .also { it.install(imports) }
                }
            try {
                @Suppress("UNCHECKED_CAST")
                val directories = imports.call("preopens", "get-directories") as List<List<Any?>>
                block(sandbox, outside, GuestFilesystem(imports, handle(directories.single()[0])))
            } finally {
                wasi.close()
            }
        } finally {
            deleteWithoutFollowingLinks(root)
        }
    }

    private class GuestFilesystem(
        private val imports: CapturingHostImports,
        private val base: Long,
    ) {
        fun symlink(target: String, link: String) {
            expectOk(
                imports.call("types", "[method]descriptor.symlink-at", base, target, link),
                "symlink-at $target -> $link",
            )
        }

        fun symlinkError(target: String, link: String): Any? =
            expectErr(
                imports.call("types", "[method]descriptor.symlink-at", base, target, link),
                "symlink-at $target -> $link",
            )

        fun rename(from: String, to: String) {
            expectOk(
                imports.call("types", "[method]descriptor.rename-at", base, from, base, to),
                "rename-at $from -> $to",
            )
        }

        fun unlink(path: String) {
            expectOk(
                imports.call("types", "[method]descriptor.unlink-file-at", base, path),
                "unlink-file-at $path",
            )
        }

        fun readlink(path: String): Any? =
            expectOk(
                imports.call("types", "[method]descriptor.readlink-at", base, path),
                "readlink-at $path",
            )

        fun open(
            path: String,
            pathFlags: List<String> = emptyList(),
            openFlags: List<String> = emptyList(),
            descriptorFlags: List<String> = listOf("read"),
        ): Any? =
            expectOk(
                imports.call(
                    "types",
                    "[method]descriptor.open-at",
                    base,
                    pathFlags,
                    path,
                    openFlags,
                    descriptorFlags,
                ),
                "open-at $path",
            )

        fun openError(
            path: String,
            pathFlags: List<String> = emptyList(),
            openFlags: List<String> = emptyList(),
            descriptorFlags: List<String> = listOf("read"),
        ): Any? =
            expectErr(
                imports.call(
                    "types",
                    "[method]descriptor.open-at",
                    base,
                    pathFlags,
                    path,
                    openFlags,
                    descriptorFlags,
                ),
                "open-at $path",
            )
    }

    private class CapturingHostImports : WasiHostImportBuilder {
        private val handlers = LinkedHashMap<String, HostHandler>()

        override fun withHostImport(
            interfaceName: String?,
            functionName: String?,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[key(interfaceName, functionName)] = handler
            return this
        }

        override fun withHostImport(
            qualifiedName: String,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[qualifiedName] = handler
            return this
        }

        fun call(
            interfaceName: String,
            functionName: String,
            vararg arguments: Any?,
        ): Any? {
            val key = key(interfaceName, functionName)
            val handler = handlers[key] ?: error("missing host import $key")
            return handler.apply(arguments.asList())
        }

        private fun key(
            interfaceName: String?,
            functionName: String?,
        ): String = "$interfaceName::$functionName"
    }

    private companion object {
        fun expectOk(
            result: Any?,
            operation: String,
        ): Any? =
            when (result) {
                is WitResult.Ok<*, *> -> result.value()
                is WitResult.Err<*, *> ->
                    throw AssertionError("expected $operation to succeed, got error ${result.value()}")
                else -> throw AssertionError("expected $operation result, got $result")
            }

        fun expectErr(
            result: Any?,
            operation: String,
        ): Any? =
            when (result) {
                is WitResult.Ok<*, *> ->
                    throw AssertionError("expected $operation to fail, got ${result.value()}")
                is WitResult.Err<*, *> -> result.value()
                else -> throw AssertionError("expected $operation result, got $result")
            }

        fun handle(value: Any?): Long =
            when (value) {
                is Number -> value.toLong()
                is WitResource<*> -> value.handle()
                else -> throw AssertionError("expected resource handle, got $value")
            }

        /** Every link below [root] must lexically resolve below [root] from its own directory. */
        fun assertEveryLinkStaysInside(root: Path) {
            Files.walk(root).use { paths ->
                paths
                    .filter { path -> Files.isSymbolicLink(path) }
                    .forEach { link ->
                        val target = Files.readSymbolicLink(link)
                        val resolved = link.parent.resolve(target).normalize()
                        assertTrue(
                            !target.isAbsolute &&
                                target.none { component -> component.toString() == ".." } &&
                                resolved.startsWith(root),
                            "$link -> $target leaves $root",
                        )
                    }
            }
        }

        fun createSymlinkOrSkip(
            link: Path,
            target: Path,
        ) {
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "host filesystem does not support symbolic links")
            } catch (_: FileSystemException) {
                assumeTrue(false, "host filesystem does not allow creating symbolic links")
            }
        }

        /** Test cleanup must not follow a link that a regression may have left behind. */
        fun deleteWithoutFollowingLinks(root: Path) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return
            }
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }
}
