package uk.shusek.krwa.component

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import okio.FileSystem

class WasiPreview3IosStreamingTest {
    @Test
    fun preview3HttpStreamCanBeWrittenToPreopenedFileOnIos() {
        val fileSystem = FileSystem.SYSTEM
        val root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(
                "krwa-ios-http-to-file-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("index.bin")
        val source =
            GeneratedRawSource(
                byteCount = LargeCatalogPayloadBytes,
                maxChunkSize = LargeResponseReadChunkSize,
            )
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(200, emptyMap(), source)
            }

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(httpToFileProbePackage())
                    .withModule(httpToFileProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withNetworking()
                            .withPreopenedDirectory("/", root.toString())
                            .withHttpClient(client)
                            .build()
                    )
                    .build()

            val written = plugin.call("api.run")
            assertEquals(LargeCatalogPayloadBytes.toLong(), written)
            if (fileSystem.metadataOrNull(output) == null) {
                fail("expected output file $output, root entries=${fileSystem.list(root)}")
            }
            val bytes = fileSystem.read(output) { readByteArray() }

            assertEquals(LargeCatalogPayloadBytes, bytes.size)
            assertEquals(
                expectedReadCalls(LargeCatalogPayloadBytes, LargeResponseReadChunkSize),
                source.reads,
            )
            assertGeneratedBytes(bytes, LargeResponseReadChunkSize)
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun preview3HttpStreamCanBeScannedByteByByteAfterHeaderWriteOnIos() {
        val fileSystem = FileSystem.SYSTEM
        val root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(
                "krwa-ios-http-scan-to-file-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("scan.tmp")
        val source =
            GeneratedRawSource(
                byteCount = LargeCatalogPayloadBytes,
                maxChunkSize = LargeResponseReadChunkSize,
            )
        val client =
            object : WasiHttpClient {
                override fun send(request: WasiHttpRequest): WasiHttpResponse =
                    WasiHttpResponse(200, emptyMap(), source)
            }

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(httpToFileProbePackage())
                    .withModule(httpScanToFileProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withNetworking()
                            .withPreopenedDirectory("/", root.toString())
                            .withHttpClient(client)
                            .build()
                    )
                    .build()

            val scanned = plugin.call("api.run")
            assertEquals(LargeCatalogPayloadBytes.toLong(), scanned)
            assertEquals("header\ndone\n", fileSystem.read(output) { readByteArray().decodeToString() })
            assertEquals(
                expectedReadCalls(LargeCatalogPayloadBytes, LargeResponseReadChunkSize),
                source.reads,
            )
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun preview3TempRunFileCanBeReadBackAndRenamedOnIos() {
        val fileSystem = FileSystem.SYSTEM
        val root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(
                "krwa-ios-ivdb-file-cycle-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("ivdb-index-v1.json")
        val temp = root.resolve("ivdb-index-v1.json.tmp")
        val run = root.resolve("ivdb-index-v1.json.tmp.run-0")

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(filesystemProbePackage())
                    .withModule(ivdbFileCycleProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", root.toString())
                            .build()
                    )
                    .build()

            val written = plugin.call("api.run")
            assertEquals(ExpectedIvdbCycleBytes.encodeToByteArray().size.toLong(), written)
            assertEquals(ExpectedIvdbCycleBytes, fileSystem.read(output) { readByteArray().decodeToString() })
            if (fileSystem.metadataOrNull(temp) != null) {
                fail("temporary cache file still exists: $temp")
            }
            if (fileSystem.metadataOrNull(run) != null) {
                fail("temporary run file still exists: $run")
            }
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun preview3LargeTempRunFilesCanBeReadBackAndRenamedOnIos() {
        val fileSystem = FileSystem.SYSTEM
        val root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(
                "krwa-ios-ivdb-large-file-cycle-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("cache.json")
        val temp = root.resolve("cache.tmp")
        val runs = (0 until LargeIvdbCycleRunCount).map { index -> root.resolve("run-$index.bin") }

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(filesystemProbePackage())
                    .withModule(ivdbLargeFileCycleProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", root.toString())
                            .build()
                    )
                    .build()

            val checksum = plugin.call("api.run")
            assertEquals(ExpectedLargeIvdbCycleChecksum.toLong(), checksum)
            assertEquals("header\nmerged\n", fileSystem.read(output) { readByteArray().decodeToString() })
            if (fileSystem.metadataOrNull(temp) != null) {
                fail("temporary cache file still exists: $temp")
            }
            for (run in runs) {
                if (fileSystem.metadataOrNull(run) != null) {
                    fail("temporary run file still exists: $run")
                }
            }
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }
}

private class GeneratedRawSource(
    private val byteCount: Int,
    private val maxChunkSize: Int,
) : RawSource {
    private val chunk = ByteArray(maxChunkSize) { index -> index.toByte() }
    private var offset = 0
    var reads: Int = 0
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        reads += 1
        if (offset >= this.byteCount) {
            return -1L
        }
        val count = minOf(byteCount.toInt(), maxChunkSize, this.byteCount - offset)
        sink.write(chunk, 0, count)
        offset += count
        return count.toLong()
    }

    override fun close() {}
}

private fun httpToFileProbePackage(): WitPackage {
    val version = WasiPreview3.DEFAULT_VERSION
    return WitPackage.parse(
        """
        package example:wasi3-http-to-file;

        world plugin {
          import wasi:http/types@$version;
          import wasi:http/client@$version;
          import wasi:filesystem/types@$version;
          import wasi:filesystem/preopens@$version;
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:http@$version {
          interface client {
            use types.{request, response, error-code};
            send: async func(request: request) -> result<response, error-code>;
          }

          interface types {
            variant error-code {
              HTTP-request-denied,
              HTTP-request-URI-invalid,
              HTTP-request-method-invalid,
              internal-error(option<string>),
            }

            variant header-error {
              invalid-syntax,
              forbidden,
              immutable,
            }

            resource fields {
              constructor();
            }

            type headers = fields;
            type trailers = fields;

            resource request-options;

            resource request {
              new: static func(
                headers: headers,
                contents: option<stream<u8>>,
                trailers: future<result<option<trailers>, error-code>>,
                options: option<request-options>,
              ) -> tuple<request, future<result<_, error-code>>>;
              set-authority: func(authority: option<string>) -> result;
              set-path-with-query: func(path-with-query: option<string>) -> result;
            }

            resource response {
              consume-body: static func(
                this: response,
                res: future<result<_, error-code>>,
              ) -> tuple<stream<u8>, future<result<option<trailers>, error-code>>>;
            }
          }
        }

        package wasi:filesystem@$version {
          interface types {
            type filesize = u64;

            flags descriptor-flags {
              read,
              write,
              file-integrity-sync,
              data-integrity-sync,
              requested-write-sync,
              mutate-directory,
            }

            flags path-flags {
              symlink-follow,
            }

            flags open-flags {
              create,
              directory,
              exclusive,
              truncate,
            }

            variant error-code {
              access,
              bad-descriptor,
              exist,
              io,
              is-directory,
              loop,
              no-entry,
              not-directory,
              not-empty,
              unsupported,
              not-permitted,
              read-only,
              other(option<string>),
            }

            resource descriptor {
              open-at: async func(
                path-flags: path-flags,
                path: string,
                open-flags: open-flags,
                %flags: descriptor-flags,
              ) -> result<descriptor, error-code>;
              write: func(buffer: list<u8>, offset: filesize) -> result<filesize, error-code>;
            }
          }

          interface preopens {
            use types.{descriptor};
            get-directories: func() -> list<tuple<descriptor, string>>;
          }
        }
        """
            .trimIndent()
    )
}

private fun httpToFileProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABOwlgAAF/YAd/f39/f39/AGAEf39/fwF/YAF/AX9gAn9/AX9gA39/fwBgA39/fwF/YAF/AGAFf39/fn8AAvMFCyN3YXNpOmh0dHAvdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0xNRNbY29uc3RydWN0b3JdZmllbGRzAAAjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUTW3N0YXRpY11yZXF1ZXN0Lm5ldwABI3dhc2k6aHR0cC90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1HVttZXRob2RdcmVxdWVzdC5zZXQtYXV0aG9yaXR5AAIjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUjW21ldGhvZF1yZXF1ZXN0LnNldC1wYXRoLXdpdGgtcXVlcnkAAiR3YXNpOmh0dHAvY2xpZW50QDAuMy4wLXJjLTIwMjYtMDMtMTUEc2VuZAADJHdhc2k6aHR0cC9jbGllbnRAMC4zLjAtcmMtMjAyNi0wMy0xNSBbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdc2VuZAAEI3dhc2k6aHR0cC90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1HVtzdGF0aWNdcmVzcG9uc2UuY29uc3VtZS1ib2R5AAUjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTU5W2FzeW5jLWxvd2VyXVtzdHJlYW0tcmVhZC0xXVtzdGF0aWNdcmVzcG9uc2UuY29uc3VtZS1ib2R5AAYsd2FzaTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wLXJjLTIwMjYtMDMtMTUPZ2V0LWRpcmVjdG9yaWVzAAcpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUnW2FzeW5jLWxvd2VyXVttZXRob2RdZGVzY3JpcHRvci5vcGVuLWF0AAQpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUYW21ldGhvZF1kZXNjcmlwdG9yLndyaXRlAAgDAwICAAUDAQAIBggBfwFB4KcSCwcsAwZtZW1vcnkCABVjYW5vbmljYWxfYWJpX3JlYWxsb2MACwdhcGkucnVuAAwKmgMCIAEBfyMAIAJBAWtqIAJBAWtBf3NxIQQgBCADaiQAIAQL9gIBCn8QAEEAQQBBAEEAQQBB4AAQAUHgACgCACEAIABBAUEQQQ8QAkEARwRAQd8HDwsgAEEBQcAAQQYQA0EARwRAQeAHDwsgABAEIQIgAkGAARAFQQBHBEBB4QcPC0GAAS0AAEEARwRAQeIHDwtBhAEoAgAhASABQQBBoAEQBkGgASgCACEDQcABEAhBxAEoAgBBAUcEQEHjBw8LQcABKAIAKAIAIQRB0AEgBDYCAEHUAUEAOgAAQdgBQfABNgIAQdwBQQk2AgBB4AFBCToAAEHhAUECOgAAQdABQYACEAkhBiAGQQBHBEBB5AcPC0GAAi0AAEEARwRAQeUHDwtBhAIoAgAhBQNAIANBgCBBgIAQEAchBiAGQX9GBEAMAQsgBkEPcSEHIAZBBHYhCCAHQQBGIAhBAEtxBEAgBUGAICAIIAmtQaACEApBoAItAABBAEcEQEHmBw8LQagCKQMAIAitUgRAQecHDwsgCSAIaiEJDAELCyAJCwswAwBBEAsPZXhhbXBsZS5pbnZhbGlkAEHAAAsGL2xhcmdlAEHwAQsJaW5kZXguYmluAK8CBG5hbWUBkAEMAApmaWVsZHNfbmV3AQtyZXF1ZXN0X25ldwINc2V0X2F1dGhvcml0eQMIc2V0X3BhdGgEBHNlbmQFEHNlbmRfZnV0dXJlX3JlYWQGEHJlc3BvbnNlX2NvbnN1bWUHC3N0cmVhbV9yZWFkCA9nZXRfZGlyZWN0b3JpZXMJB29wZW5fYXQKBXdyaXRlDANydW4CiwENAAABAAIAAwAEAAUABgAHAAgACQAKAAsFAANvbGQBCG9sZF9zaXplAgVhbGlnbgMIbmV3X3NpemUEA3B0cgwKAAdyZXF1ZXN0AQhyZXNwb25zZQIGZnV0dXJlAwZzdHJlYW0EBGJhc2UFBGZpbGUGBnN0YXR1cwcEa2luZAgFY291bnQJBXRvdGFsBwcBAARoZWFw
        """
            .trimIndent()
    )

private fun httpScanToFileProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABOwlgAAF/YAd/f39/f39/AGAEf39/fwF/YAF/AX9gAn9/AX9gA39/fwBgA39/fwF/YAF/AGAFf39/fn8AAvMFCyN3YXNpOmh0dHAvdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0xNRNbY29uc3RydWN0b3JdZmllbGRzAAAjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUTW3N0YXRpY11yZXF1ZXN0Lm5ldwABI3dhc2k6aHR0cC90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1HVttZXRob2RdcmVxdWVzdC5zZXQtYXV0aG9yaXR5AAIjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUjW21ldGhvZF1yZXF1ZXN0LnNldC1wYXRoLXdpdGgtcXVlcnkAAiR3YXNpOmh0dHAvY2xpZW50QDAuMy4wLXJjLTIwMjYtMDMtMTUEc2VuZAADJHdhc2k6aHR0cC9jbGllbnRAMC4zLjAtcmMtMjAyNi0wMy0xNSBbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdc2VuZAAEI3dhc2k6aHR0cC90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1HVtzdGF0aWNdcmVzcG9uc2UuY29uc3VtZS1ib2R5AAUjd2FzaTpodHRwL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTU5W2FzeW5jLWxvd2VyXVtzdHJlYW0tcmVhZC0xXVtzdGF0aWNdcmVzcG9uc2UuY29uc3VtZS1ib2R5AAYsd2FzaTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wLXJjLTIwMjYtMDMtMTUPZ2V0LWRpcmVjdG9yaWVzAAcpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUnW2FzeW5jLWxvd2VyXVttZXRob2RdZGVzY3JpcHRvci5vcGVuLWF0AAQpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUYW21ldGhvZF1kZXNjcmlwdG9yLndyaXRlAAgDBAMCAgAFAwEACAYNAn8BQeCnEgt/AUEACwcsAwZtZW1vcnkCABVjYW5vbmljYWxfYWJpX3JlYWxsb2MACwdhcGkucnVuAA0K5gMDIAEBfyMAIAJBAWtqIAJBAWtBf3NxIQQgBCADaiQAIAQLMQAgACABIAIgA61BwAIQCkHAAi0AAEEARwRAQQEPC0HIAikDACACrVIEQEECDwtBAAuQAwELfxAAQQBBAEEAQQBBAEHgABABQeAAKAIAIQAgAEEBQRBBDxACQQBHBEBB3gcPCyAAQQFBwABBBhADQQBHBEBB3wcPCyAAEAQhAiACQYABEAVBAEcEQEHgBw8LQYABLQAAQQBHBEBB4QcPC0GEASgCACEBIAFBAEGgARAGQaABKAIAIQNBwAEQCEHEASgCAEEBRwRAQeIHDwtBwAEoAgAoAgAhBEHQASAENgIAQdQBQQA6AABB2AFB8AE2AgBB3AFBCDYCAEHgAUEJOgAAQeEBQQI6AABB0AFB4AIQCSEGIAZBAEcEQEHjBw8LQeACLQAAQQBHBEBB5AcPC0HkAigCACEFIAVBkAJBB0EAEAwEQEHlBw8LA0AgA0GAIEGAgBAQByEGIAZBf0YEQAwBCyAGQQ9xIQcgBkEEdiEIQQAhCgNAIAogCEkEQCMBQYAgIApqLQAAaiQBIApBAWohCgwBCwsgCSAIaiEJIAdBAEYgCEEAS3EEQAwBCwsgBUGgAkEFQQcQDARAQeYHDwsgCQsLRwUAQRALD2V4YW1wbGUuaW52YWxpZABBwAALBi9sYXJnZQBB8AELCHNjYW4udG1wAEGQAgsHaGVhZGVyCgBBoAILBWRvbmUKAOMCBG5hbWUBnwENAApmaWVsZHNfbmV3AQtyZXF1ZXN0X25ldwINc2V0X2F1dGhvcml0eQMIc2V0X3BhdGgEBHNlbmQFEHNlbmRfZnV0dXJlX3JlYWQGEHJlc3BvbnNlX2NvbnN1bWUHC3N0cmVhbV9yZWFkCA9nZXRfZGlyZWN0b3JpZXMJB29wZW5fYXQKBXdyaXRlDA13cml0ZV9jaGVja2VkDQNydW4CpgEOAAABAAIAAwAEAAUABgAHAAgACQAKAAsFAANvbGQBCG9sZF9zaXplAgVhbGlnbgMIbmV3X3NpemUEA3B0cgwEAAJmZAEDcHRyAgNsZW4DBm9mZnNldA0LAAdyZXF1ZXN0AQhyZXNwb25zZQIGZnV0dXJlAwZzdHJlYW0EBGJhc2UFBGZpbGUGBnN0YXR1cwcEa2luZAgFY291bnQJBXRvdGFsCgFpBxECAARoZWFwAQhjaGVja3N1bQ==
        """
            .trimIndent()
    )

private fun filesystemProbePackage(): WitPackage {
    val version = WasiPreview3.DEFAULT_VERSION
    return WitPackage.parse(
        """
        package example:wasi3-filesystem-cycle;

        world plugin {
          import wasi:filesystem/types@$version;
          import wasi:filesystem/preopens@$version;
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:filesystem@$version {
          interface types {
            type filesize = u64;

            flags descriptor-flags {
              read,
              write,
              file-integrity-sync,
              data-integrity-sync,
              requested-write-sync,
              mutate-directory,
            }

            flags path-flags {
              symlink-follow,
            }

            flags open-flags {
              create,
              directory,
              exclusive,
              truncate,
            }

            variant error-code {
              access,
              bad-descriptor,
              exist,
              io,
              is-directory,
              loop,
              no-entry,
              not-directory,
              not-empty,
              unsupported,
              not-permitted,
              read-only,
              other(option<string>),
            }

            resource descriptor {
              open-at: async func(
                path-flags: path-flags,
                path: string,
                open-flags: open-flags,
                %flags: descriptor-flags,
              ) -> result<descriptor, error-code>;
              write: func(buffer: list<u8>, offset: filesize) -> result<filesize, error-code>;
              read-via-stream: func(offset: filesize) -> tuple<stream<u8>, future<result<_, error-code>>>;
              rename-at: func(old-path: string, new-descriptor: descriptor, new-path: string) -> result<_, error-code>;
              unlink-file-at: func(path: string) -> result<_, error-code>;
            }
          }

          interface preopens {
            use types.{descriptor};
            get-directories: func() -> list<tuple<descriptor, string>>;
          }
        }
        """
            .trimIndent()
    )
}

private fun ivdbFileCycleProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABSwtgAX8AYAJ/fwF/YAV/f39+fwBgA39+fwBgA39/fwF/YAd/f39/f39/AGAEf39/fwBgBH9/f38Bf2AFf39/f38Bf2ABfwF/YAABfwLwBAgsd2FzaTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wLXJjLTIwMjYtMDMtMTUPZ2V0LWRpcmVjdG9yaWVzAAApd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUnW2FzeW5jLWxvd2VyXVttZXRob2RdZGVzY3JpcHRvci5vcGVuLWF0AAEpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUYW21ldGhvZF1kZXNjcmlwdG9yLndyaXRlAAIpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUiW21ldGhvZF1kZXNjcmlwdG9yLnJlYWQtdmlhLXN0cmVhbQADKXdhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1Plthc3luYy1sb3dlcl1bc3RyZWFtLXJlYWQtMF1bbWV0aG9kXWRlc2NyaXB0b3IucmVhZC12aWEtc3RyZWFtAAQpd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUcW21ldGhvZF1kZXNjcmlwdG9yLnJlbmFtZS1hdAAFKXdhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1IVttZXRob2RdZGVzY3JpcHRvci51bmxpbmstZmlsZS1hdAAGKXdhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMC1yYy0yMDI2LTAzLTE1GVtyZXNvdXJjZS1kcm9wXWRlc2NyaXB0b3IAAAMGBQcIBwkKBQMBAAEGDAJ/AUGAEAt/AUEACwcsAwZtZW1vcnkCABVjYW5vbmljYWxfYWJpX3JlYWxsb2MACAdhcGkucnVuAAwKuAYFIAEBfyMAIAJBAWtqIAJBAWtBf3NxIQQgBCADaiQAIAQLXgEBf0HgAyAANgIAQeQDQQA6AABB6AMgATYCAEHsAyACNgIAQfADIAM6AABB8QMgBDoAAEHgA0GABBABIQUgBUEARwRAQQAPC0GABC0AAEEARwRAQQAPC0GEBCgCAAsxACAAIAEgAiADrUGgBBACQaAELQAAQQBHBEBBAQ8LQagEKQMAIAKtUgRAQQIPC0EAC34BBn9BACQBIABCAEHABBADQcAEKAIAIQEDQCABQYAFQREQBCECIAJBf0YEQAwBCyACQQ9xIQMgAkEEdiEEQQAhBgNAIAYgBEkEQCMBQYAFIAZqLQAAaiQBIAZBAWohBgwBCwsgBSAEaiEFIANBAEYgBEEAS3EEQAwBCwsgBQuEBAEGf0HABRAAQcQFKAIAQQFHBEBB3gcPC0HABSgCACgCACEAIABBEEEWQQlBAhAJIQEgAUUEQEHfBw8LIABB4ABBHEEJQQMQCSECIAJFBEBB4AcPCyABQaABQTggBBAKIQUgBQRAQeEHDwsgBEE4aiEEIAFBgAJBASAEEAohBSAFBEBB4gcPCyAEQQFqIQRBgAZByAA2AgAgAkGABkEEQQAQCiEFIAUEQEHjBw8LIAJBoAJByABBBBAKIQUgBQRAQeQHDwtBgAZByAA2AgAgAkGABkEEQcwAEAohBSAFBEBB5QcPCyACQYADQcgAQdAAEAohBSAFBEBB5gcPCyACEAcgAEHgAEEcQQBBARAJIQMgA0UEQEHnBw8LIAMQC0GYAUcEQEHoBw8LIwFBhtwARwRAQekHDwsgAxAHIAFBoAJByAAgBBAKIQUgBQRAQeoHDwsgBEHIAGohBCABQYECQQEgBBAKIQUgBQRAQesHDwsgBEEBaiEEIAFBgANByAAgBBAKIQUgBQRAQewHDwsgBEHIAGohBCABQYICQQEgBBAKIQUgBQRAQe0HDwsgBEEBaiEEIAEQByAAQRBBFiAAQcAAQRJBoAYQBUGgBi0AAEEARwRAQe4HDwsgAEHgAEEcQaAGEAZBoAYtAABBAEcEQEHvBw8LIARBywFHBEBB8AcPCyAECwvFAgkAQRALFml2ZGItaW5kZXgtdjEuanNvbi50bXAAQcAACxJpdmRiLWluZGV4LXYxLmpzb24AQeAACxxpdmRiLWluZGV4LXYxLmpzb24udG1wLnJ1bi0wAEGgAQs4c3V2aW8taXZkYi1pbmRleC12MiBwdWJsaWNPbmx5PWZhbHNlIGNyZWF0ZWRBdEVwb2NoTXM9MQoAQYACCwFbAEGBAgsBLABBggILAV0AQaACC0h7InBhcnRuZXJWaWRlb0lkIjoiYSIsInB1Ymxpc2hlZEF0IjoiMjAyNi0wMS0wMiIsInZpZGVvQWNjZXNzIjoiUHVibGljIn0AQYADC0h7InBhcnRuZXJWaWRlb0lkIjoiYiIsInB1Ymxpc2hlZEF0IjoiMjAyNS0wMS0wMiIsInZpZGVvQWNjZXNzIjoiUHVibGljIn0AowMEbmFtZQGaAQwAD2dldF9kaXJlY3RvcmllcwEHb3Blbl9hdAIFd3JpdGUDD3JlYWRfdmlhX3N0cmVhbQQLc3RyZWFtX3JlYWQFCXJlbmFtZV9hdAYOdW5saW5rX2ZpbGVfYXQHD2Ryb3BfZGVzY3JpcHRvcgkJb3Blbl9maWxlCg13cml0ZV9jaGVja2VkCw1yZWFkX2NoZWNrc3VtDANydW4C6wENAAABAAIAAwAEAAUABgAHAAgFAANvbGQBCG9sZF9zaXplAgVhbGlnbgMIbmV3X3NpemUEA3B0cgkGAARiYXNlAQhwYXRoX3B0cgIIcGF0aF9sZW4DCm9wZW5fZmxhZ3MEEGRlc2NyaXB0b3JfZmxhZ3MFBnN0YXR1cwoEAAJmZAEDcHRyAgNsZW4DBm9mZnNldAsHAAJmZAEGc3RyZWFtAgZzdGF0dXMDBGtpbmQEBWNvdW50BQV0b3RhbAYBaQwGAARiYXNlAQRtYWluAgNydW4DB3JlYWRfZmQEBm9mZnNldAUGc3RhdHVzBxECAARoZWFwAQhjaGVja3N1bQ==
        """
            .trimIndent()
    )

private fun ivdbLargeFileCycleProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABYA5gAX8AYAZ/f39/f38Bf2ACf38Bf2AFf39/fn8AYAN/fn8AYAN/f38Bf2AHf39/
        f39/fwBgBH9/f38AYAR/f39/AX9gBX9/f39/AX9gAn9/AGABfwF/YAN/f38AYAABfwKxBgosd2Fz
        aTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wLXJjLTIwMjYtMDMtMTUPZ2V0LWRpcmVjdG9yaWVz
        AAApd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wLXJjLTIwMjYtMDMtMTUaW21ldGhvZF1kZXNj
        cmlwdG9yLm9wZW4tYXQAASl3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0x
        NTZbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdW21ldGhvZF1kZXNjcmlwdG9yLm9wZW4tYXQA
        Ail3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0xNRhbbWV0aG9kXWRlc2Ny
        aXB0b3Iud3JpdGUAAyl3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0xNSJb
        bWV0aG9kXWRlc2NyaXB0b3IucmVhZC12aWEtc3RyZWFtAAQpd2FzaTpmaWxlc3lzdGVtL3R5cGVz
        QDAuMy4wLXJjLTIwMjYtMDMtMTU+W2FzeW5jLWxvd2VyXVtzdHJlYW0tcmVhZC0wXVttZXRob2Rd
        ZGVzY3JpcHRvci5yZWFkLXZpYS1zdHJlYW0ABSl3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAt
        cmMtMjAyNi0wMy0xNT5bYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTFdW21ldGhvZF1kZXNjcmlw
        dG9yLnJlYWQtdmlhLXN0cmVhbQACKXdhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMC1yYy0yMDI2
        LTAzLTE1HFttZXRob2RdZGVzY3JpcHRvci5yZW5hbWUtYXQABil3YXNpOmZpbGVzeXN0ZW0vdHlw
        ZXNAMC4zLjAtcmMtMjAyNi0wMy0xNSFbbWV0aG9kXWRlc2NyaXB0b3IudW5saW5rLWZpbGUtYXQA
        Byl3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAtcmMtMjAyNi0wMy0xNRlbcmVzb3VyY2UtZHJv
        cF1kZXNjcmlwdG9yAAADCgkICQcKBwsFDA0FAwEAAgYIAX8BQYDAAAsHLAMGbWVtb3J5AgAVY2Fu
        b25pY2FsX2FiaV9yZWFsbG9jAAoHYXBpLnJ1bgASCu4GCSABAX8jACACQQFraiACQQFrQX9zcSEE
        IAQgA2okACAECzkBAn8gAEEAIAEgAiADIAQQASEFIAVBgAQQAiEGIAZBAEcEQAALQYAELQAAQQBH
        BEAAC0GEBCgCAAsrACAAIAEgAiADrUGgBBADQaAELQAAQQBHBEAAC0GoBCkDACACrVIEQAALCzAB
        AX9BACECAkADQCACQYAgTw0BQYAgIAJqIAAgAWogAmo6AAAgAkEBaiECDAALCwtGAQJ/IAAgASAC
        QQlBAhALIQRBACEFAkADQCAFQYABTw0BIAMgBRANIARBgCBBgCAgBUEMdBAMIAVBAWohBQwACwsg
        BBAJCzIBAn9BACEBQQAhAgJAA0AgASAATw0BIAJBgCAgAWotAABqIQIgAUEBaiEBDAALCyACC54B
        AQd/IAAgASACQQBBARALIQMgA0IAQcAEEARBwAQoAgAhBEHEBCgCACEFIAVB4AQQBiEGIAZBAEcE
        QAALQeAELQAAQQBHBEAAC0EAIQhBACEJAkADQCAIQYCAIE8NASAEQYAgQYAgEAUhBiAGQQR2IQcg
        B0UEQAALIAkgBxAPaiEJIAggB2ohCAwACwsgCEGAgCBHBEAACyADEAkgCQsaACAAIAEgAkGABRAI
        QYAFLQAAQQBHBEAACwv+AgEDf0GACBAAQYQIKAIAQQFJBEAAC0GACCgCACgCACEAIABBEEEJQQlB
        AhALIQEgAUGAAkEHQQAQDCAAQTBBCUEAEA4gAEHAAEEJQREQDiAAQdAAQQlBIhAOIABB4ABBCUEz
        EA4gAEHwAEEJQcQAEA4gAEGAAUEJQdUAEA4gAEGQAUEJQeYAEA4gAEGgAUEJQfcAEA5BACECIAIg
        AEEwQQkQEGohAiACIABBwABBCRAQaiECIAIgAEHQAEEJEBBqIQIgAiAAQeAAQQkQEGohAiACIABB
        8ABBCRAQaiECIAIgAEGAAUEJEBBqIQIgAiAAQZABQQkQEGohAiACIABBoAFBCRAQaiECIAFBkAJB
        B0EHEAwgARAJIABBEEEJIABBIEEKQYAFEAdBgAUtAABBAEcEQAALIABBMEEJEBEgAEHAAEEJEBEg
        AEHQAEEJEBEgAEHgAEEJEBEgAEHwAEEJEBEgAEGAAUEJEBEgAEGQAUEJEBEgAEGgAUEJEBEgAgsL
        rwEMAEEQCwljYWNoZS50bXAAQSALCmNhY2hlLmpzb24AQTALCXJ1bi0wLmJpbgBBwAALCXJ1bi0x
        LmJpbgBB0AALCXJ1bi0yLmJpbgBB4AALCXJ1bi0zLmJpbgBB8AALCXJ1bi00LmJpbgBBgAELCXJ1
        bi01LmJpbgBBkAELCXJ1bi02LmJpbgBBoAELCXJ1bi03LmJpbgBBgAILB2hlYWRlcgoAQZACCwdt
        ZXJnZWQKAKcFBG5hbWUB8AESAA9nZXRfZGlyZWN0b3JpZXMBB29wZW5fYXQCE29wZW5fYXRfZnV0
        dXJlX3JlYWQDBXdyaXRlBAtyZWFkX3N0cmVhbQULc3RyZWFtX3JlYWQGC2Z1dHVyZV9yZWFkBwly
        ZW5hbWVfYXQIDnVubGlua19maWxlX2F0CQ9kcm9wX2Rlc2NyaXB0b3ILCW9wZW5fZmlsZQwNd3Jp
        dGVfY2hlY2tlZA0LZmlsbF9idWZmZXIOCXdyaXRlX3J1bg8Kc3VtX2J1ZmZlchARcmVhZF9ydW5f
        Y2hlY2tzdW0RDnVubGlua19jaGVja2VkEgNydW4C6AIJCgUAA29sZAEIb2xkX3NpemUCBWFsaWdu
        AwhuZXdfc2l6ZQQDcHRyCwcABGJhc2UBCHBhdGhfcHRyAghwYXRoX2xlbgMKb3Blbl9mbGFncwQQ
        ZGVzY3JpcHRvcl9mbGFncwUGZnV0dXJlBgtvcGVuX3N0YXR1cwwEAAJmZAEDcHRyAgNsZW4DBm9m
        ZnNldA0DAARzZWVkAQVjaHVuawIBaQ4GAARiYXNlAQhwYXRoX3B0cgIIcGF0aF9sZW4DBHNlZWQE
        AmZkBQVjaHVuaw8DAAVjb3VudAEBaQIDc3VtEAoABGJhc2UBCHBhdGhfcHRyAghwYXRoX2xlbgMC
        ZmQEBnN0cmVhbQUGZnV0dXJlBgtyZWFkX3N0YXR1cwcFY291bnQIBXRvdGFsCQhjaGVja3N1bRED
        AARiYXNlAQhwYXRoX3B0cgIIcGF0aF9sZW4SAwAEYmFzZQEEdGVtcAIIY2hlY2tzdW0DOQQNAgAE
        ZG9uZQEEbG9vcA4CAARkb25lAQRsb29wDwIABGRvbmUBBGxvb3AQAgIEZG9uZQMEbG9vcAcHAQAE
        aGVhcA==
        """
            .trimIndent()
    )

private fun decodeBase64(value: String): ByteArray {
    val compact = value.filterNot { it.isWhitespace() }
    require(compact.length % 4 == 0) { "invalid base64 length" }
    val out = ByteArray(compact.length / 4 * 3)
    var outIndex = 0
    var index = 0
    while (index < compact.length) {
        val a = base64Value(compact[index++])
        val b = base64Value(compact[index++])
        val cChar = compact[index++]
        val dChar = compact[index++]
        val c = if (cChar == '=') -1 else base64Value(cChar)
        val d = if (dChar == '=') -1 else base64Value(dChar)
        out[outIndex++] = ((a shl 2) or (b shr 4)).toByte()
        if (c >= 0) {
            out[outIndex++] = (((b and 0x0f) shl 4) or (c shr 2)).toByte()
        }
        if (d >= 0) {
            out[outIndex++] = (((c and 0x03) shl 6) or d).toByte()
        }
    }
    return out.copyOf(outIndex)
}

private fun base64Value(ch: Char): Int =
    when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> throw IllegalArgumentException("invalid base64 character: $ch")
    }

private fun expectedReadCalls(byteCount: Int, chunkSize: Int): Int =
    ((byteCount + chunkSize - 1) / chunkSize) + 1

private fun assertGeneratedBytes(bytes: ByteArray, chunkSize: Int) {
    for (index in bytes.indices) {
        val expected = (index % chunkSize).toByte()
        if (bytes[index] != expected) {
            fail("byte[$index] expected=$expected actual=${bytes[index]}")
        }
    }
}

private const val LargeCatalogPayloadBytes = 7_950_024
private const val LargeResponseReadChunkSize = 256 * 1024
private const val LargeIvdbCycleRunCount = 8
private const val ExpectedLargeIvdbCycleChecksum = 534_773_760
private const val ExpectedIvdbCycleBytes =
    "suvio-ivdb-index-v2 publicOnly=false createdAtEpochMs=1\n" +
        "[{\"partnerVideoId\":\"a\",\"publishedAt\":\"2026-01-02\",\"videoAccess\":\"Public\"}," +
        "{\"partnerVideoId\":\"b\",\"publishedAt\":\"2025-01-02\",\"videoAccess\":\"Public\"}]"
