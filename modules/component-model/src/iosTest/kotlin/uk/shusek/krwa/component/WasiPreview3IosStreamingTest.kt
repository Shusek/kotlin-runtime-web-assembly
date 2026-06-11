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
                "krwa-ios-test-file-cycle-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("test-index-v1.json")
        val temp = root.resolve("test-index-v1.json.tmp")
        val run = root.resolve("test-index-v1.json.tmp.run-0")

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(filesystemProbePackage())
                    .withModule(indexFileCycleProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", root.toString())
                            .build()
                    )
                    .build()

            val written = plugin.call("api.run")
            assertEquals(ExpectedIndexCycleBytes.encodeToByteArray().size.toLong(), written)
            assertEquals(ExpectedIndexCycleBytes, fileSystem.read(output) { readByteArray().decodeToString() })
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
                "krwa-ios-index-large-file-cycle-${Random.nextInt(Int.MAX_VALUE)}"
            )
        val output = root.resolve("cache.json")
        val temp = root.resolve("cache.tmp")
        val runs = (0 until LargeIndexCycleRunCount).map { index -> root.resolve("run-$index.bin") }

        fileSystem.createDirectories(root)
        try {
            val plugin =
                WasmPlugin.builder(filesystemProbePackage())
                    .withModule(indexLargeFileCycleProbeModule())
                    .withWasiPreview3(
                        WasiPreview3.builder()
                            .withPreopenedDirectory("/", root.toString())
                            .build()
                    )
                    .build()

            val checksum = plugin.call("api.run")
            assertEquals(ExpectedLargeIndexCycleChecksum.toLong(), checksum)
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
        AGFzbQEAAAABOwlgAAF/YAd/f39/f39/AGAEf39/fwF/YAF/AX9gAn9/AX9gA39/fwBgA39/fwF/YAF/AGAFf39/fn8AAtkE
        CxV3YXNpOmh0dHAvdHlwZXNAMC4zLjATW2NvbnN0cnVjdG9yXWZpZWxkcwAAFXdhc2k6aHR0cC90eXBlc0AwLjMuMBNbc3Rh
        dGljXXJlcXVlc3QubmV3AAEVd2FzaTpodHRwL3R5cGVzQDAuMy4wHVttZXRob2RdcmVxdWVzdC5zZXQtYXV0aG9yaXR5AAIV
        d2FzaTpodHRwL3R5cGVzQDAuMy4wI1ttZXRob2RdcmVxdWVzdC5zZXQtcGF0aC13aXRoLXF1ZXJ5AAIWd2FzaTpodHRwL2Ns
        aWVudEAwLjMuMARzZW5kAAMWd2FzaTpodHRwL2NsaWVudEAwLjMuMCBbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdc2Vu
        ZAAEFXdhc2k6aHR0cC90eXBlc0AwLjMuMB1bc3RhdGljXXJlc3BvbnNlLmNvbnN1bWUtYm9keQAFFXdhc2k6aHR0cC90eXBl
        c0AwLjMuMDlbYXN5bmMtbG93ZXJdW3N0cmVhbS1yZWFkLTFdW3N0YXRpY11yZXNwb25zZS5jb25zdW1lLWJvZHkABh53YXNp
        OmZpbGVzeXN0ZW0vcHJlb3BlbnNAMC4zLjAPZ2V0LWRpcmVjdG9yaWVzAAcbd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4w
        J1thc3luYy1sb3dlcl1bbWV0aG9kXWRlc2NyaXB0b3Iub3Blbi1hdAAEG3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMBhb
        bWV0aG9kXWRlc2NyaXB0b3Iud3JpdGUACAMDAgIABQMBAAgGCAF/AUHgpxILBywDBm1lbW9yeQIAFWNhbm9uaWNhbF9hYmlf
        cmVhbGxvYwALB2FwaS5ydW4ADAqaAwIgAQF/IwAgAkEBa2ogAkEBa0F/c3EhBCAEIANqJAAgBAv2AgEKfxAAQQBBAEEAQQBB
        AEHgABABQeAAKAIAIQAgAEEBQRBBDxACQQBHBEBB3wcPCyAAQQFBwABBBhADQQBHBEBB4AcPCyAAEAQhAiACQYABEAVBAEcE
        QEHhBw8LQYABLQAAQQBHBEBB4gcPC0GEASgCACEBIAFBAEGgARAGQaABKAIAIQNBwAEQCEHEASgCAEEBRwRAQeMHDwtBwAEo
        AgAoAgAhBEHQASAENgIAQdQBQQA6AABB2AFB8AE2AgBB3AFBCTYCAEHgAUEJOgAAQeEBQQI6AABB0AFBgAIQCSEGIAZBAkcE
        QEHkBw8LQYACLQAAQQBHBEBB5QcPC0GEAigCACEFA0AgA0GAIEGAgBAQByEGIAZBf0YEQAwBCyAGQQ9xIQcgBkEEdiEIIAdB
        AEYgCEEAS3EEQCAFQYAgIAggCa1BoAIQCkGgAi0AAEEARwRAQeYHDwtBqAIpAwAgCK1SBEBB5wcPCyAJIAhqIQkMAQsLIAkL
        CzADAEEQCw9leGFtcGxlLmludmFsaWQAQcAACwYvbGFyZ2UAQfABCwlpbmRleC5iaW4ArwIEbmFtZQGQAQwACmZpZWxkc19u
        ZXcBC3JlcXVlc3RfbmV3Ag1zZXRfYXV0aG9yaXR5AwhzZXRfcGF0aAQEc2VuZAUQc2VuZF9mdXR1cmVfcmVhZAYQcmVzcG9u
        c2VfY29uc3VtZQcLc3RyZWFtX3JlYWQID2dldF9kaXJlY3RvcmllcwkHb3Blbl9hdAoFd3JpdGUMA3J1bgKLAQ0AAAEAAgAD
        AAQABQAGAAcACAAJAAoACwUAA29sZAEIb2xkX3NpemUCBWFsaWduAwhuZXdfc2l6ZQQDcHRyDAoAB3JlcXVlc3QBCHJlc3Bv
        bnNlAgZmdXR1cmUDBnN0cmVhbQQEYmFzZQUEZmlsZQYGc3RhdHVzBwRraW5kCAVjb3VudAkFdG90YWwHBwEABGhlYXA=
        """
            .trimIndent()
    )

private fun httpScanToFileProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABOwlgAAF/YAd/f39/f39/AGAEf39/fwF/YAF/AX9gAn9/AX9gA39/fwBgA39/fwF/YAF/AGAFf39/fn8AAtkE
        CxV3YXNpOmh0dHAvdHlwZXNAMC4zLjATW2NvbnN0cnVjdG9yXWZpZWxkcwAAFXdhc2k6aHR0cC90eXBlc0AwLjMuMBNbc3Rh
        dGljXXJlcXVlc3QubmV3AAEVd2FzaTpodHRwL3R5cGVzQDAuMy4wHVttZXRob2RdcmVxdWVzdC5zZXQtYXV0aG9yaXR5AAIV
        d2FzaTpodHRwL3R5cGVzQDAuMy4wI1ttZXRob2RdcmVxdWVzdC5zZXQtcGF0aC13aXRoLXF1ZXJ5AAIWd2FzaTpodHRwL2Ns
        aWVudEAwLjMuMARzZW5kAAMWd2FzaTpodHRwL2NsaWVudEAwLjMuMCBbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdc2Vu
        ZAAEFXdhc2k6aHR0cC90eXBlc0AwLjMuMB1bc3RhdGljXXJlc3BvbnNlLmNvbnN1bWUtYm9keQAFFXdhc2k6aHR0cC90eXBl
        c0AwLjMuMDlbYXN5bmMtbG93ZXJdW3N0cmVhbS1yZWFkLTFdW3N0YXRpY11yZXNwb25zZS5jb25zdW1lLWJvZHkABh53YXNp
        OmZpbGVzeXN0ZW0vcHJlb3BlbnNAMC4zLjAPZ2V0LWRpcmVjdG9yaWVzAAcbd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4w
        J1thc3luYy1sb3dlcl1bbWV0aG9kXWRlc2NyaXB0b3Iub3Blbi1hdAAEG3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMBhb
        bWV0aG9kXWRlc2NyaXB0b3Iud3JpdGUACAMEAwICAAUDAQAIBg0CfwFB4KcSC38BQQALBywDBm1lbW9yeQIAFWNhbm9uaWNh
        bF9hYmlfcmVhbGxvYwALB2FwaS5ydW4ADQrmAwMgAQF/IwAgAkEBa2ogAkEBa0F/c3EhBCAEIANqJAAgBAsxACAAIAEgAiAD
        rUHAAhAKQcACLQAAQQBHBEBBAQ8LQcgCKQMAIAKtUgRAQQIPC0EAC5ADAQt/EABBAEEAQQBBAEEAQeAAEAFB4AAoAgAhACAA
        QQFBEEEPEAJBAEcEQEHeBw8LIABBAUHAAEEGEANBAEcEQEHfBw8LIAAQBCECIAJBgAEQBUEARwRAQeAHDwtBgAEtAABBAEcE
        QEHhBw8LQYQBKAIAIQEgAUEAQaABEAZBoAEoAgAhA0HAARAIQcQBKAIAQQFHBEBB4gcPC0HAASgCACgCACEEQdABIAQ2AgBB
        1AFBADoAAEHYAUHwATYCAEHcAUEINgIAQeABQQk6AABB4QFBAjoAAEHQAUHgAhAJIQYgBkECRwRAQeMHDwtB4AItAABBAEcE
        QEHkBw8LQeQCKAIAIQUgBUGQAkEHQQAQDARAQeUHDwsDQCADQYAgQYCAEBAHIQYgBkF/RgRADAELIAZBD3EhByAGQQR2IQhB
        ACEKA0AgCiAISQRAIwFBgCAgCmotAABqJAEgCkEBaiEKDAELCyAJIAhqIQkgB0EARiAIQQBLcQRADAELCyAFQaACQQVBBxAM
        BEBB5gcPCyAJCwtHBQBBEAsPZXhhbXBsZS5pbnZhbGlkAEHAAAsGL2xhcmdlAEHwAQsIc2Nhbi50bXAAQZACCwdoZWFkZXIK
        AEGgAgsFZG9uZQoA4wIEbmFtZQGfAQ0ACmZpZWxkc19uZXcBC3JlcXVlc3RfbmV3Ag1zZXRfYXV0aG9yaXR5AwhzZXRfcGF0
        aAQEc2VuZAUQc2VuZF9mdXR1cmVfcmVhZAYQcmVzcG9uc2VfY29uc3VtZQcLc3RyZWFtX3JlYWQID2dldF9kaXJlY3Rvcmll
        cwkHb3Blbl9hdAoFd3JpdGUMDXdyaXRlX2NoZWNrZWQNA3J1bgKmAQ4AAAEAAgADAAQABQAGAAcACAAJAAoACwUAA29sZAEI
        b2xkX3NpemUCBWFsaWduAwhuZXdfc2l6ZQQDcHRyDAQAAmZkAQNwdHICA2xlbgMGb2Zmc2V0DQsAB3JlcXVlc3QBCHJlc3Bv
        bnNlAgZmdXR1cmUDBnN0cmVhbQQEYmFzZQUEZmlsZQYGc3RhdHVzBwRraW5kCAVjb3VudAkFdG90YWwKAWkHEQIABGhlYXAB
        CGNoZWNrc3Vt
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

private fun indexFileCycleProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABSwtgAX8AYAJ/fwF/YAV/f39+fwBgA39+fwBgA39/fwF/YAd/f39/f39/AGAEf39/fwBgBH9/f38Bf2AFf39/f38B
        f2ABfwF/YAABfwKABAged2FzaTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wD2dldC1kaXJlY3RvcmllcwAAG3dhc2k6ZmlsZXN5
        c3RlbS90eXBlc0AwLjMuMCdbYXN5bmMtbG93ZXJdW21ldGhvZF1kZXNjcmlwdG9yLm9wZW4tYXQAARt3YXNpOmZpbGVzeXN0ZW0v
        dHlwZXNAMC4zLjAYW21ldGhvZF1kZXNjcmlwdG9yLndyaXRlAAIbd2FzaTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wIlttZXRob2Rd
        ZGVzY3JpcHRvci5yZWFkLXZpYS1zdHJlYW0AAxt3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjA+W2FzeW5jLWxvd2VyXVtzdHJl
        YW0tcmVhZC0wXVttZXRob2RdZGVzY3JpcHRvci5yZWFkLXZpYS1zdHJlYW0ABBt3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAc
        W21ldGhvZF1kZXNjcmlwdG9yLnJlbmFtZS1hdAAFG3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMCFbbWV0aG9kXWRlc2NyaXB0
        b3IudW5saW5rLWZpbGUtYXQABht3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAZW3Jlc291cmNlLWRyb3BdZGVzY3JpcHRvcgAA
        AwYFBwgHCQoFAwEAAQYMAn8BQYAQC38BQQALBywDBm1lbW9yeQIAFWNhbm9uaWNhbF9hYmlfcmVhbGxvYwAIB2FwaS5ydW4ADAq4
        BgUgAQF/IwAgAkEBa2ogAkEBa0F/c3EhBCAEIANqJAAgBAteAQF/QeADIAA2AgBB5ANBADoAAEHoAyABNgIAQewDIAI2AgBB8AMg
        AzoAAEHxAyAEOgAAQeADQYAEEAEhBSAFQQJHBEBBAA8LQYAELQAAQQBHBEBBAA8LQYQEKAIACzEAIAAgASACIAOtQaAEEAJBoAQt
        AABBAEcEQEEBDwtBqAQpAwAgAq1SBEBBAg8LQQALfgEGf0EAJAEgAEIAQcAEEANBwAQoAgAhAQNAIAFBgAVBERAEIQIgAkF/RgRA
        DAELIAJBD3EhAyACQQR2IQRBACEGA0AgBiAESQRAIwFBgAUgBmotAABqJAEgBkEBaiEGDAELCyAFIARqIQUgA0EARiAEQQBLcQRA
        DAELCyAFC4QEAQZ/QcAFEABBxAUoAgBBAUcEQEHeBw8LQcAFKAIAKAIAIQAgAEEQQRZBCUECEAkhASABRQRAQd8HDwsgAEHgAEEc
        QQlBAxAJIQIgAkUEQEHgBw8LIAFBoAFBOCAEEAohBSAFBEBB4QcPCyAEQThqIQQgAUGAAkEBIAQQCiEFIAUEQEHiBw8LIARBAWoh
        BEGABkHIADYCACACQYAGQQRBABAKIQUgBQRAQeMHDwsgAkGgAkHIAEEEEAohBSAFBEBB5AcPC0GABkHIADYCACACQYAGQQRBzAAQ
        CiEFIAUEQEHlBw8LIAJBgANByABB0AAQCiEFIAUEQEHmBw8LIAIQByAAQeAAQRxBAEEBEAkhAyADRQRAQecHDwsgAxALQZgBRwRA
        QegHDwsjAUGG3ABHBEBB6QcPCyADEAcgAUGgAkHIACAEEAohBSAFBEBB6gcPCyAEQcgAaiEEIAFBgQJBASAEEAohBSAFBEBB6wcP
        CyAEQQFqIQQgAUGAA0HIACAEEAohBSAFBEBB7AcPCyAEQcgAaiEEIAFBggJBASAEEAohBSAFBEBB7QcPCyAEQQFqIQQgARAHIABB
        EEEWIABBwABBEkGgBhAFQaAGLQAAQQBHBEBB7gcPCyAAQeAAQRxBoAYQBkGgBi0AAEEARwRAQe8HDwsgBEHLAUcEQEHwBw8LIAQL
        C8UCCQBBEAsWdGVzdC1pbmRleC12MS5qc29uLnRtcABBwAALEnRlc3QtaW5kZXgtdjEuanNvbgBB4AALHHRlc3QtaW5kZXgtdjEu
        anNvbi50bXAucnVuLTAAQaABCzhzdXZpby10ZXN0LWluZGV4LXYyIHB1YmxpY09ubHk9ZmFsc2UgY3JlYXRlZEF0RXBvY2hNcz0x
        CgBBgAILAVsAQYECCwEsAEGCAgsBXQBBoAILSHsicGFydG5lclZpZGVvSWQiOiJhIiwicHVibGlzaGVkQXQiOiIyMDI2LTAxLTAy
        IiwidmlkZW9BY2Nlc3MiOiJQdWJsaWMifQBBgAMLSHsicGFydG5lclZpZGVvSWQiOiJiIiwicHVibGlzaGVkQXQiOiIyMDI1LTAx
        LTAyIiwidmlkZW9BY2Nlc3MiOiJQdWJsaWMifQCjAwRuYW1lAZoBDAAPZ2V0X2RpcmVjdG9yaWVzAQdvcGVuX2F0AgV3cml0ZQMP
        cmVhZF92aWFfc3RyZWFtBAtzdHJlYW1fcmVhZAUJcmVuYW1lX2F0Bg51bmxpbmtfZmlsZV9hdAcPZHJvcF9kZXNjcmlwdG9yCQlv
        cGVuX2ZpbGUKDXdyaXRlX2NoZWNrZWQLDXJlYWRfY2hlY2tzdW0MA3J1bgLrAQ0AAAEAAgADAAQABQAGAAcACAUAA29sZAEIb2xk
        X3NpemUCBWFsaWduAwhuZXdfc2l6ZQQDcHRyCQYABGJhc2UBCHBhdGhfcHRyAghwYXRoX2xlbgMKb3Blbl9mbGFncwQQZGVzY3Jp
        cHRvcl9mbGFncwUGc3RhdHVzCgQAAmZkAQNwdHICA2xlbgMGb2Zmc2V0CwcAAmZkAQZzdHJlYW0CBnN0YXR1cwMEa2luZAQFY291
        bnQFBXRvdGFsBgFpDAYABGJhc2UBBG1haW4CA3J1bgMHcmVhZF9mZAQGb2Zmc2V0BQZzdGF0dXMHEQIABGhlYXABCGNoZWNrc3Vt
        """
            .trimIndent()
    )

private fun indexLargeFileCycleProbeModule(): ByteArray =
    decodeBase64(
        """
        AGFzbQEAAAABYA5gAX8AYAZ/f39/f38Bf2ACf38Bf2AFf39/fn8AYAN/fn8AYAN/f38Bf2AHf39/f39/fwBgBH9/f38AYAR/
        f39/AX9gBX9/f39/AX9gAn9/AGABfwF/YAN/f38AYAABfwKlBQoed2FzaTpmaWxlc3lzdGVtL3ByZW9wZW5zQDAuMy4wD2dl
        dC1kaXJlY3RvcmllcwAAG3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMBpbbWV0aG9kXWRlc2NyaXB0b3Iub3Blbi1hdAAB
        G3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMDZbYXN5bmMtbG93ZXJdW2Z1dHVyZS1yZWFkLTBdW21ldGhvZF1kZXNjcmlw
        dG9yLm9wZW4tYXQAAht3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAYW21ldGhvZF1kZXNjcmlwdG9yLndyaXRlAAMbd2Fz
        aTpmaWxlc3lzdGVtL3R5cGVzQDAuMy4wIlttZXRob2RdZGVzY3JpcHRvci5yZWFkLXZpYS1zdHJlYW0ABBt3YXNpOmZpbGVz
        eXN0ZW0vdHlwZXNAMC4zLjA+W2FzeW5jLWxvd2VyXVtzdHJlYW0tcmVhZC0wXVttZXRob2RdZGVzY3JpcHRvci5yZWFkLXZp
        YS1zdHJlYW0ABRt3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjA+W2FzeW5jLWxvd2VyXVtmdXR1cmUtcmVhZC0xXVttZXRo
        b2RdZGVzY3JpcHRvci5yZWFkLXZpYS1zdHJlYW0AAht3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAcW21ldGhvZF1kZXNj
        cmlwdG9yLnJlbmFtZS1hdAAGG3dhc2k6ZmlsZXN5c3RlbS90eXBlc0AwLjMuMCFbbWV0aG9kXWRlc2NyaXB0b3IudW5saW5r
        LWZpbGUtYXQABxt3YXNpOmZpbGVzeXN0ZW0vdHlwZXNAMC4zLjAZW3Jlc291cmNlLWRyb3BdZGVzY3JpcHRvcgAAAwoJCAkH
        CgcLBQwNBQMBAAIGCAF/AUGAwAALBywDBm1lbW9yeQIAFWNhbm9uaWNhbF9hYmlfcmVhbGxvYwAKB2FwaS5ydW4AEgruBgkg
        AQF/IwAgAkEBa2ogAkEBa0F/c3EhBCAEIANqJAAgBAs5AQJ/IABBACABIAIgAyAEEAEhBSAFQYAEEAIhBiAGQQBHBEAAC0GA
        BC0AAEEARwRAAAtBhAQoAgALKwAgACABIAIgA61BoAQQA0GgBC0AAEEARwRAAAtBqAQpAwAgAq1SBEAACwswAQF/QQAhAgJA
        A0AgAkGAIE8NAUGAICACaiAAIAFqIAJqOgAAIAJBAWohAgwACwsLRgECfyAAIAEgAkEJQQIQCyEEQQAhBQJAA0AgBUGAAU8N
        ASADIAUQDSAEQYAgQYAgIAVBDHQQDCAFQQFqIQUMAAsLIAQQCQsyAQJ/QQAhAUEAIQICQANAIAEgAE8NASACQYAgIAFqLQAA
        aiECIAFBAWohAQwACwsgAgueAQEHfyAAIAEgAkEAQQEQCyEDIANCAEHABBAEQcAEKAIAIQRBxAQoAgAhBSAFQeAEEAYhBiAG
        QX9HBEAAC0HgBC0AAEEARwRAAAtBACEIQQAhCQJAA0AgCEGAgCBPDQEgBEGAIEGAIBAFIQYgBkEEdiEHIAdFBEAACyAJIAcQ
        D2ohCSAIIAdqIQgMAAsLIAhBgIAgRwRAAAsgAxAJIAkLGgAgACABIAJBgAUQCEGABS0AAEEARwRAAAsL/gIBA39BgAgQAEGE
        CCgCAEEBSQRAAAtBgAgoAgAoAgAhACAAQRBBCUEJQQIQCyEBIAFBgAJBB0EAEAwgAEEwQQlBABAOIABBwABBCUEREA4gAEHQ
        AEEJQSIQDiAAQeAAQQlBMxAOIABB8ABBCUHEABAOIABBgAFBCUHVABAOIABBkAFBCUHmABAOIABBoAFBCUH3ABAOQQAhAiAC
        IABBMEEJEBBqIQIgAiAAQcAAQQkQEGohAiACIABB0ABBCRAQaiECIAIgAEHgAEEJEBBqIQIgAiAAQfAAQQkQEGohAiACIABB
        gAFBCRAQaiECIAIgAEGQAUEJEBBqIQIgAiAAQaABQQkQEGohAiABQZACQQdBBxAMIAEQCSAAQRBBCSAAQSBBCkGABRAHQYAF
        LQAAQQBHBEAACyAAQTBBCRARIABBwABBCRARIABB0ABBCRARIABB4ABBCRARIABB8ABBCRARIABBgAFBCRARIABBkAFBCRAR
        IABBoAFBCRARIAILC68BDABBEAsJY2FjaGUudG1wAEEgCwpjYWNoZS5qc29uAEEwCwlydW4tMC5iaW4AQcAACwlydW4tMS5i
        aW4AQdAACwlydW4tMi5iaW4AQeAACwlydW4tMy5iaW4AQfAACwlydW4tNC5iaW4AQYABCwlydW4tNS5iaW4AQZABCwlydW4t
        Ni5iaW4AQaABCwlydW4tNy5iaW4AQYACCwdoZWFkZXIKAEGQAgsHbWVyZ2VkCgCnBQRuYW1lAfABEgAPZ2V0X2RpcmVjdG9y
        aWVzAQdvcGVuX2F0AhNvcGVuX2F0X2Z1dHVyZV9yZWFkAwV3cml0ZQQLcmVhZF9zdHJlYW0FC3N0cmVhbV9yZWFkBgtmdXR1
        cmVfcmVhZAcJcmVuYW1lX2F0CA51bmxpbmtfZmlsZV9hdAkPZHJvcF9kZXNjcmlwdG9yCwlvcGVuX2ZpbGUMDXdyaXRlX2No
        ZWNrZWQNC2ZpbGxfYnVmZmVyDgl3cml0ZV9ydW4PCnN1bV9idWZmZXIQEXJlYWRfcnVuX2NoZWNrc3VtEQ51bmxpbmtfY2hl
        Y2tlZBIDcnVuAugCCQoFAANvbGQBCG9sZF9zaXplAgVhbGlnbgMIbmV3X3NpemUEA3B0cgsHAARiYXNlAQhwYXRoX3B0cgII
        cGF0aF9sZW4DCm9wZW5fZmxhZ3MEEGRlc2NyaXB0b3JfZmxhZ3MFBmZ1dHVyZQYLb3Blbl9zdGF0dXMMBAACZmQBA3B0cgID
        bGVuAwZvZmZzZXQNAwAEc2VlZAEFY2h1bmsCAWkOBgAEYmFzZQEIcGF0aF9wdHICCHBhdGhfbGVuAwRzZWVkBAJmZAUFY2h1
        bmsPAwAFY291bnQBAWkCA3N1bRAKAARiYXNlAQhwYXRoX3B0cgIIcGF0aF9sZW4DAmZkBAZzdHJlYW0FBmZ1dHVyZQYLcmVh
        ZF9zdGF0dXMHBWNvdW50CAV0b3RhbAkIY2hlY2tzdW0RAwAEYmFzZQEIcGF0aF9wdHICCHBhdGhfbGVuEgMABGJhc2UBBHRl
        bXACCGNoZWNrc3VtAzkEDQIABGRvbmUBBGxvb3AOAgAEZG9uZQEEbG9vcA8CAARkb25lAQRsb29wEAICBGRvbmUDBGxvb3AH
        BwEABGhlYXA=
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
private const val LargeIndexCycleRunCount = 8
private const val ExpectedLargeIndexCycleChecksum = 534_773_760
private const val ExpectedIndexCycleBytes =
    "suvio-test-index-v2 publicOnly=false createdAtEpochMs=1\n" +
        "[{\"partnerVideoId\":\"a\",\"publishedAt\":\"2026-01-02\",\"videoAccess\":\"Public\"}," +
        "{\"partnerVideoId\":\"b\",\"publishedAt\":\"2025-01-02\",\"videoAccess\":\"Public\"}]"
