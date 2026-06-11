package uk.shusek.krwa.sample

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WasiHttpRequest
import uk.shusek.krwa.component.WasiHttpResponse
import uk.shusek.krwa.component.WasiSuspendingHttpClient
import uk.shusek.krwa.component.WasmPlugin
import uk.shusek.krwa.component.WitPackage

internal const val SHOWCASE_WASI3_HTTP_AUTHORITY: String = "127.0.0.1:7777"
internal const val SHOWCASE_WASI3_HTTP_PATH_WITH_QUERY: String = "/probe?x=p3"

@OptIn(ExperimentalTime::class)
internal fun runWasiPreview3CliClocksRandomScenario(module: ByteArray) {
    var monotonicReads = 0
    val wasi =
        WasiPreview3.builder()
            .withArguments("guest.wasm", "alpha", "beta")
            .withEnvironment("MODE", "p3")
            .withInitialCwd("/work")
            .withFixedWallClock(Instant.fromEpochSeconds(1_700_000_000L, 42))
            .withWallClockResolutionNanos(123L)
            .withMonotonicClock {
                monotonicReads += 1
                if (monotonicReads == 1) {
                    1_000_000L
                } else {
                    1_000_123L
                }
            }
            .withMonotonicResolutionNanos(456L)
            .withSecureRandom(kotlin.random.Random(7L))
            .withInsecureSeed(11L, 12L)
            .build()
    val plugin =
        WasmPlugin.builder(wasiPreview3CliClocksRandomWit())
            .withModule(module)
            .withWasiPreview3(wasi)
            .build()

    requireShowcaseValue(7L, plugin.call("api.run"), "WASIp3 CLI, clocks, random")
}

internal fun runWasiPreview3HttpClientScenario(module: ByteArray) {
    val httpClient = RecordingWasiPreview3HttpClient()
    val plugin =
        WasmPlugin.builder(wasiPreview3HttpClientWit())
            .withModule(module)
            .withWasiPreview3(
                WasiPreview3.builder()
                    .withNetworking()
                    .withHttpClient(httpClient)
                    .build()
            )
            .build()

    requireShowcaseValue(203L, plugin.call("api.run"), "WASIp3 HTTP client")
    val request = httpClient.request ?: error("WASIp3 HTTP client did not receive a request")
    requireShowcaseValue("GET", request.method, "WASIp3 HTTP method")
    requireShowcaseValue(
        "http://$SHOWCASE_WASI3_HTTP_AUTHORITY$SHOWCASE_WASI3_HTTP_PATH_WITH_QUERY",
        request.uri,
        "WASIp3 HTTP request URI",
    )
}

internal fun runWasiPreview3KtorHttpClientScenario(module: ByteArray) {
    var observedMethod: String? = null
    var observedUri: String? = null
    val client = showcaseKtorHttpClient { method, uri ->
        observedMethod = method
        observedUri = uri
    }

    try {
        val plugin =
            WasmPlugin.builder(wasiPreview3HttpClientWit())
                .withModule(module)
                .withWasiPreview3(
                    WasiPreview3.builder()
                        .withNetworking()
                        .withHttpClient(client)
                        .build()
                )
                .build()

        requireShowcaseValue(202L, plugin.call("api.run"), "WASIp3 Ktor HttpClient")
        requireShowcaseValue("GET", observedMethod, "Ktor HTTP method")
        requireShowcaseValue(
            "http://$SHOWCASE_WASI3_HTTP_AUTHORITY$SHOWCASE_WASI3_HTTP_PATH_WITH_QUERY",
            observedUri,
            "Ktor request URI",
        )
    } finally {
        client.close()
    }
}

internal fun configureWasiPreview3KtorHttpClient() {
    val client = showcaseKtorHttpClient { _, _ -> }
    try {
        WasiPreview3.builder()
            .withNetworking()
            .withHttpClient(client)
            .build()
            .close()
    } finally {
        client.close()
    }
}

private fun showcaseKtorHttpClient(onRequest: (String, String) -> Unit): HttpClient =
    HttpClient(
        MockEngine { request ->
            onRequest(request.method.value, request.url.toString())
            respond(
                content = "ktor-reply",
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
    )

internal fun runWasiPreview3FilesystemScenario(
    module: ByteArray,
    hostRoot: String,
) {
    val plugin =
        WasmPlugin.builder(wasiPreview3FilesystemWit())
            .withModule(module)
            .withWasiPreview3(
                WasiPreview3.builder()
                    .withPreopenedDirectory("/", hostRoot)
                    .build()
            )
            .build()

    requireShowcaseValue(532L, plugin.call("api.run"), "WASIp3 filesystem preopen stream")
}

internal fun runWasiPreview3SocketsScenario(module: ByteArray) {
    val plugin =
        WasmPlugin.builder(wasiPreview3SocketsWit())
            .withModule(module)
            .withWasiPreview3(
                WasiPreview3.builder()
                    .withNetworking()
                    .build()
            )
            .build()

    requireShowcaseValue(42L, plugin.call("api.run"), "WASIp3 TCP/UDP sockets")
}

internal fun runWasiPreview3CanonicalIntrinsicsScenarios(
    streamModule: ByteArray,
    futureModule: ByteArray,
) {
    val streamPlugin =
        WasmPlugin.builder(wasiPreview3StreamIntrinsicsWit())
            .withModule(streamModule)
            .withWasiPreview3(WasiPreview3.builder().build())
            .build()

    requireShowcaseValue(294L, streamPlugin.call("api.run"), "WASIp3 canonical stream intrinsics")

    val futureWasi = WasiPreview3.builder().build()
    val futurePlugin =
        WasmPlugin.builder(wasiPreview3FutureIntrinsicsWit())
            .withModule(futureModule)
            .withHostImport("plugin", "seed") { futureWasi.completedFuture(0L) }
            .withWasiPreview3(futureWasi)
            .build()

    requireShowcaseValue(123456L, futurePlugin.call("api.run"), "WASIp3 canonical future intrinsics")
}

private class RecordingWasiPreview3HttpClient : WasiSuspendingHttpClient {
    var request: WasiHttpRequest? = null
        private set

    override fun send(request: WasiHttpRequest): WasiHttpResponse =
        record(request)

    override suspend fun sendSuspending(request: WasiHttpRequest): WasiHttpResponse =
        record(request)

    private fun record(request: WasiHttpRequest): WasiHttpResponse {
        this.request = request
        return WasiHttpResponse(
            203,
            mapOf("X-P3" to listOf("ok")),
            "reply".encodeToByteArray(),
        )
    }
}

internal fun wasiPreview3CliClocksRandomWit(
    version: String = WasiPreview3.DEFAULT_VERSION,
): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-sync;

        world plugin {
          import wasi:cli/environment@${version};
          import wasi:clocks/system-clock@${version};
          import wasi:clocks/monotonic-clock@${version};
          import wasi:random/random@${version};
          import wasi:random/insecure-seed@${version};
          export api;
        }

        interface api {
          run: func() -> u64;
        }

        package wasi:cli@${version} {
          interface environment {
            get-environment: func() -> list<tuple<string, string>>;
            get-arguments: func() -> list<string>;
            get-initial-cwd: func() -> option<string>;
          }
        }

        package wasi:clocks@${version} {
          interface types {
            type duration = u64;
          }
          interface system-clock {
            use types.{duration};
            record instant {
              seconds: s64,
              nanoseconds: u32,
            }
            now: func() -> instant;
            get-resolution: func() -> duration;
          }
          interface monotonic-clock {
            use types.{duration};
            type mark = u64;
            now: func() -> mark;
            get-resolution: func() -> duration;
            wait-for: async func(how-long: duration);
          }
        }

        package wasi:random@${version} {
          interface random {
            get-random-bytes: func(max-len: u64) -> list<u8>;
            get-random-u64: func() -> u64;
          }
          interface insecure-seed {
            get-insecure-seed: func() -> tuple<u64, u64>;
          }
        }
        """
            .trimIndent()
    )

internal fun wasiPreview3CliClocksRandomWat(
    version: String = WasiPreview3.DEFAULT_VERSION,
): String =
    """
    (module
      (import "wasi:cli/environment@${version}" "get-arguments" (func _D_args (param i32)))
      (import "wasi:cli/environment@${version}" "get-environment" (func _D_env (param i32)))
      (import "wasi:cli/environment@${version}" "get-initial-cwd" (func _D_cwd (param i32)))
      (import "wasi:clocks/system-clock@${version}" "now" (func _D_system_now (param i32)))
      (import "wasi:clocks/system-clock@${version}" "get-resolution" (func _D_system_resolution (result i64)))
      (import "wasi:clocks/monotonic-clock@${version}" "now" (func _D_monotonic_now (result i64)))
      (import "wasi:clocks/monotonic-clock@${version}" "get-resolution" (func _D_monotonic_resolution (result i64)))
      (import "wasi:clocks/monotonic-clock@${version}" "[async-lower]wait-for" (func _D_monotonic_wait_for (param i64) (result i32)))
      (import "wasi:random/random@${version}" "get-random-bytes" (func _D_random_bytes (param i64) (param i32)))
      (import "wasi:random/insecure-seed@${version}" "get-insecure-seed" (func _D_seed (param i32)))
      (memory (export "memory") 1)
      (global _D_heap (mut i32) (i32.const 256))
      (func (export "canonical_abi_realloc")
        (param _D_old i32) (param _D_old_size i32)
        (param _D_align i32) (param _D_new_size i32)
        (result i32)
        (local _D_ptr i32)
        (local.set _D_ptr
          (i32.and
            (i32.add (global.get _D_heap) (i32.sub (local.get _D_align) (i32.const 1)))
            (i32.xor
              (i32.sub (local.get _D_align) (i32.const 1))
              (i32.const -1))))
        (global.set _D_heap (i32.add (local.get _D_ptr) (local.get _D_new_size)))
        (local.get _D_ptr))
      (func _D_run (result i64)
        (call _D_args (i32.const 64))
        (if (i32.ne (i32.load (i32.const 68)) (i32.const 3)) (then unreachable))
        (call _D_env (i32.const 80))
        (if (i32.ne (i32.load (i32.const 84)) (i32.const 1)) (then unreachable))
        (call _D_cwd (i32.const 96))
        (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 1)) (then unreachable))
        (if (i32.ne (i32.load (i32.const 104)) (i32.const 5)) (then unreachable))
        (call _D_system_now (i32.const 112))
        (if (i64.ne (i64.load (i32.const 112)) (i64.const 1700000000)) (then unreachable))
        (if (i64.ne (call _D_system_resolution) (i64.const 123)) (then unreachable))
        (if (i64.ne (call _D_monotonic_now) (i64.const 123)) (then unreachable))
        (if (i64.ne (call _D_monotonic_resolution) (i64.const 456)) (then unreachable))
        (if (i32.ne (call _D_monotonic_wait_for (i64.const 0)) (i32.const 2)) (then unreachable))
        (call _D_random_bytes (i64.const 4) (i32.const 128))
        (if (i32.ne (i32.load (i32.const 132)) (i32.const 4)) (then unreachable))
        (call _D_seed (i32.const 144))
        (if (i64.ne (i64.load (i32.const 144)) (i64.const 11)) (then unreachable))
        (if (i64.ne (i64.load (i32.const 152)) (i64.const 12)) (then unreachable))
        (i64.const 7))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()

internal fun wasiPreview3HttpClientWit(
    version: String = WasiPreview3.DEFAULT_VERSION,
): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-http-client;

        world plugin {
          import wasi:http/types@${version};
          import wasi:http/client@${version};
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:http@${version} {
          interface client {
            use types.{request, response, error-code};
            send: async func(request: request) -> result<response, error-code>;
          }

          interface types {
            variant error-code {
              HTTP-request-denied,
              HTTP-request-URI-invalid,
              HTTP-request-method-invalid,
              connection-refused,
              connection-timeout,
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
              get-status-code: func() -> u16;
            }
          }
        }
        """
            .trimIndent()
    )

internal fun wasiPreview3HttpClientWat(
    authority: String,
    pathWithQuery: String,
    version: String = WasiPreview3.DEFAULT_VERSION,
): String =
    """
    (module
      (import "wasi:http/types@${version}" "[constructor]fields" (func _D_fields_new (result i32)))
      (import "wasi:http/types@${version}" "[static]request.new" (func _D_request_new (param i32 i32 i32 i32 i32 i32 i32)))
      (import "wasi:http/types@${version}" "[method]request.set-authority" (func _D_set_authority (param i32 i32 i32 i32) (result i32)))
      (import "wasi:http/types@${version}" "[method]request.set-path-with-query" (func _D_set_path (param i32 i32 i32 i32) (result i32)))
      (import "wasi:http/client@${version}" "send" (func _D_send (param i32) (result i32)))
      (import "wasi:http/client@${version}" "[async-lower][future-read-0]send" (func _D_send_future_read (param i32 i32) (result i32)))
      (import "wasi:http/client@${version}" "waitable-set.new" (func _D_waitable_set_new (result i32)))
      (import "wasi:http/client@${version}" "waitable.join" (func _D_waitable_join (param i32 i32)))
      (import "wasi:http/client@${version}" "waitable-set.wait" (func _D_waitable_set_wait (param i32 i32) (result i32)))
      (import "wasi:http/client@${version}" "waitable-set.drop" (func _D_waitable_set_drop (param i32)))
      (import "wasi:http/types@${version}" "[method]response.get-status-code" (func _D_status (param i32) (result i32)))
      (memory (export "memory") 1)
      (global _D_heap (mut i32) (i32.const 256))
      (data (i32.const 16) "${authority}")
      (data (i32.const 64) "${pathWithQuery}")
      (func (export "canonical_abi_realloc")
        (param _D_old i32) (param _D_old_size i32)
        (param _D_align i32) (param _D_new_size i32)
        (result i32)
        (local _D_ptr i32)
        (local.set _D_ptr
          (i32.and
            (i32.add (global.get _D_heap) (i32.sub (local.get _D_align) (i32.const 1)))
            (i32.xor
              (i32.sub (local.get _D_align) (i32.const 1))
              (i32.const -1))))
        (global.set _D_heap (i32.add (local.get _D_ptr) (local.get _D_new_size)))
        (local.get _D_ptr))
      (func _D_run (result i32)
        (local _D_request i32)
        (local _D_response i32)
        (local _D_future i32)
        (local _D_send_status i32)
        (local _D_waitable_set i32)
        (call _D_request_new
          (call _D_fields_new)
          (i32.const 0)
          (i32.const 0)
          (i32.const 0)
          (i32.const 0)
          (i32.const 0)
          (i32.const 96))
        (local.set _D_request (i32.load (i32.const 96)))
        (if
          (i32.ne
            (call _D_set_authority
              (local.get _D_request)
              (i32.const 1)
              (i32.const 16)
              (i32.const ${authority.length}))
            (i32.const 0))
          (then unreachable))
        (if
          (i32.ne
            (call _D_set_path
              (local.get _D_request)
              (i32.const 1)
              (i32.const 64)
              (i32.const ${pathWithQuery.length}))
            (i32.const 0))
          (then unreachable))
        (local.set _D_future (call _D_send (local.get _D_request)))
        (local.set _D_send_status (call _D_send_future_read (local.get _D_future) (i32.const 128)))
        (if (i32.eq (local.get _D_send_status) (i32.const -1))
          (then
            (local.set _D_waitable_set (call _D_waitable_set_new))
            (call _D_waitable_join (local.get _D_future) (local.get _D_waitable_set))
            (if (i32.ne (call _D_waitable_set_wait (local.get _D_waitable_set) (i32.const 192)) (i32.const 4)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 192)) (local.get _D_future)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 196)) (i32.const 0)) (then unreachable))
            (call _D_waitable_set_drop (local.get _D_waitable_set)))
          (else
            (if (i32.ne (local.get _D_send_status) (i32.const 0)) (then unreachable))))
        (if (i32.ne (i32.load8_u (i32.const 128)) (i32.const 0)) (then unreachable))
        (local.set _D_response (i32.load (i32.const 132)))
        (call _D_status (local.get _D_response)))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()

internal fun wasiPreview3FilesystemWit(
    version: String = WasiPreview3.DEFAULT_VERSION,
): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-filesystem;

        world plugin {
          import wasi:filesystem/types@${version};
          import wasi:filesystem/preopens@${version};
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:filesystem@${version} {
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
              read-via-stream: func(offset: filesize) -> tuple<stream<u8>, future<result<_, error-code>>>;
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

internal fun wasiPreview3FilesystemWat(
    version: String = WasiPreview3.DEFAULT_VERSION,
): String =
    """
    (module
      (import "wasi:filesystem/preopens@${version}" "get-directories" (func _D_get_directories (param i32)))
      (import "wasi:filesystem/types@${version}" "[method]descriptor.open-at" (func _D_open_at (param i32 i32 i32 i32 i32 i32) (result i32)))
      (import "wasi:filesystem/types@${version}" "[async-lower][future-read-0][method]descriptor.open-at" (func _D_open_at_future_read (param i32 i32) (result i32)))
      (import "wasi:filesystem/types@${version}" "[method]descriptor.read-via-stream" (func _D_read_stream (param i32) (param i64) (param i32)))
      (import "wasi:filesystem/types@${version}" "[async-lower][stream-read-0][method]descriptor.read-via-stream" (func _D_stream_read (param i32 i32 i32) (result i32)))
      (import "wasi:filesystem/types@${version}" "[async-lower][future-read-1][method]descriptor.read-via-stream" (func _D_future_read (param i32 i32) (result i32)))
      (memory (export "memory") 1)
      (global _D_heap (mut i32) (i32.const 256))
      (data (i32.const 16) "hello.txt")
      (func (export "canonical_abi_realloc")
        (param _D_old i32) (param _D_old_size i32)
        (param _D_align i32) (param _D_new_size i32)
        (result i32)
        (local _D_ptr i32)
        (local.set _D_ptr
          (i32.and
            (i32.add (global.get _D_heap) (i32.sub (local.get _D_align) (i32.const 1)))
            (i32.xor
              (i32.sub (local.get _D_align) (i32.const 1))
              (i32.const -1))))
        (global.set _D_heap (i32.add (local.get _D_ptr) (local.get _D_new_size)))
        (local.get _D_ptr))
      (func _D_run (result i32)
        (local _D_base i32)
        (local _D_file i32)
        (local _D_stream i32)
        (local _D_future i32)
        (local _D_status i32)
        (local _D_eof_status i32)
        (call _D_get_directories (i32.const 48))
        (local.set _D_base (i32.load (i32.load (i32.const 48))))
        (local.set _D_future
          (call _D_open_at
            (local.get _D_base)
            (i32.const 0)
            (i32.const 16)
            (i32.const 9)
            (i32.const 0)
            (i32.const 1)))
        (local.set _D_status (call _D_open_at_future_read (local.get _D_future) (i32.const 64)))
        (if (i32.ne (local.get _D_status) (i32.const 0)) (then unreachable))
        (if (i32.ne (i32.load8_u (i32.const 64)) (i32.const 0)) (then unreachable))
        (local.set _D_file (i32.load (i32.const 68)))
        (call _D_read_stream (local.get _D_file) (i64.const 0) (i32.const 96))
        (local.set _D_stream (i32.load (i32.const 96)))
        (local.set _D_future (i32.load (i32.const 100)))
        (local.set _D_status
          (call _D_stream_read
            (local.get _D_stream)
            (i32.const 128)
            (i32.const 16)))
        (if (i32.ne (i32.and (local.get _D_status) (i32.const -16)) (i32.const 80)) (then unreachable))
        (if
          (i32.eqz (i32.and (local.get _D_status) (i32.const 15)))
          (then
            (local.set _D_eof_status
              (call _D_stream_read
                (local.get _D_stream)
                (i32.const 144)
                (i32.const 16)))
            (if (i32.ne (local.get _D_eof_status) (i32.const 1)) (then unreachable)))
          (else
            (if (i32.ne (i32.and (local.get _D_status) (i32.const 15)) (i32.const 1)) (then unreachable))))
        (local.set _D_status (call _D_future_read (local.get _D_future) (i32.const 160)))
        (if (i32.ne (local.get _D_status) (i32.const 0)) (then unreachable))
        (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 0)) (then unreachable))
        (i32.add
          (i32.add
            (i32.add
              (i32.add
                (i32.load8_u (i32.const 128))
                (i32.load8_u (i32.const 129)))
              (i32.load8_u (i32.const 130)))
            (i32.load8_u (i32.const 131)))
          (i32.load8_u (i32.const 132))))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()

internal fun wasiPreview3SocketsWit(
    version: String = WasiPreview3.DEFAULT_VERSION,
): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-sockets;

        world plugin {
          import wasi:sockets/types@${version};
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:sockets@${version} {
          interface types {
            variant error-code {
              access-denied,
              not-supported,
              invalid-argument,
              out-of-memory,
              timeout,
              invalid-state,
              address-not-bindable,
              address-in-use,
              remote-unreachable,
              connection-refused,
              connection-broken,
              connection-reset,
              connection-aborted,
              datagram-too-large,
              other(option<string>),
            }

            enum ip-address-family {
              ipv4,
              ipv6,
            }

            type ipv4-address = tuple<u8, u8, u8, u8>;
            type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

            record ipv4-socket-address {
              port: u16,
              address: ipv4-address,
            }

            record ipv6-socket-address {
              port: u16,
              flow-info: u32,
              address: ipv6-address,
              scope-id: u32,
            }

            variant ip-socket-address {
              ipv4(ipv4-socket-address),
              ipv6(ipv6-socket-address),
            }

            resource tcp-socket {
              create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
              connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
              get-local-address: func() -> result<ip-socket-address, error-code>;
              get-remote-address: func() -> result<ip-socket-address, error-code>;
            }

            resource udp-socket {
              create: static func(address-family: ip-address-family) -> result<udp-socket, error-code>;
              send: async func(data: list<u8>, remote-address: option<ip-socket-address>) -> result<_, error-code>;
            }
          }
        }
        """
            .trimIndent()
    )

internal fun wasiPreview3SocketsWat(
    tcpPort: Int,
    udpPort: Int,
    version: String = WasiPreview3.DEFAULT_VERSION,
): String =
    """
    (module
      (import "wasi:sockets/types@${version}" "[static]tcp-socket.create" (func _D_tcp_create (param i32) (param i32)))
      (import "wasi:sockets/types@${version}" "[async-lower][method]tcp-socket.connect" (func _D_tcp_connect (param i32 i32) (result i32)))
      (import "wasi:sockets/types@${version}" "[method]tcp-socket.get-local-address" (func _D_tcp_local (param i32) (param i32)))
      (import "wasi:sockets/types@${version}" "[method]tcp-socket.get-remote-address" (func _D_tcp_remote (param i32) (param i32)))
      (import "wasi:sockets/types@${version}" "[static]udp-socket.create" (func _D_udp_create (param i32) (param i32)))
      (import "wasi:sockets/types@${version}" "[async-lower][method]udp-socket.send" (func _D_udp_send (param i32 i32) (result i32)))
      (import "wasi:sockets/types@${version}" "waitable-set.new" (func _D_waitable_set_new (result i32)))
      (import "wasi:sockets/types@${version}" "waitable.join" (func _D_waitable_join (param i32 i32)))
      (import "wasi:sockets/types@${version}" "waitable-set.wait" (func _D_waitable_set_wait (param i32 i32) (result i32)))
      (import "wasi:sockets/types@${version}" "waitable-set.drop" (func _D_waitable_set_drop (param i32)))
      (memory (export "memory") 1)
      (data (i32.const 16) "ping")
      (func _D_run (result i32)
        (local _D_tcp i32)
        (local _D_udp i32)
        (local _D_status i32)
        (local _D_subtask i32)
        (local _D_waitable_set i32)
        (call _D_tcp_create (i32.const 0) (i32.const 64))
        (if (i32.ne (i32.load8_u (i32.const 64)) (i32.const 0)) (then unreachable))
        (local.set _D_tcp (i32.load (i32.const 68)))
        (i32.store (i32.const 32) (local.get _D_tcp))
        (i32.store8 (i32.const 36) (i32.const 0))
        (i32.store16 (i32.const 40) (i32.const ${tcpPort}))
        (i32.store8 (i32.const 42) (i32.const 127))
        (i32.store8 (i32.const 43) (i32.const 0))
        (i32.store8 (i32.const 44) (i32.const 0))
        (i32.store8 (i32.const 45) (i32.const 1))
        (local.set _D_status (call _D_tcp_connect (i32.const 32) (i32.const 80)))
        (if (i32.ne (local.get _D_status) (i32.const 2))
          (then
            (if (i32.ne (i32.and (local.get _D_status) (i32.const 15)) (i32.const 1)) (then unreachable))
            (local.set _D_subtask (i32.shr_u (local.get _D_status) (i32.const 4)))
            (local.set _D_waitable_set (call _D_waitable_set_new))
            (call _D_waitable_join (local.get _D_subtask) (local.get _D_waitable_set))
            (if (i32.ne (call _D_waitable_set_wait (local.get _D_waitable_set) (i32.const 304)) (i32.const 1)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 304)) (local.get _D_subtask)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 308)) (i32.const 2)) (then unreachable))
            (call _D_waitable_set_drop (local.get _D_waitable_set))))
        (if (i32.ne (i32.load8_u (i32.const 80)) (i32.const 0)) (then unreachable))
        (call _D_tcp_local (local.get _D_tcp) (i32.const 96))
        (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 0)) (then unreachable))
        (call _D_tcp_remote (local.get _D_tcp) (i32.const 144))
        (if (i32.ne (i32.load8_u (i32.const 144)) (i32.const 0)) (then unreachable))
        (call _D_udp_create (i32.const 0) (i32.const 192))
        (if (i32.ne (i32.load8_u (i32.const 192)) (i32.const 0)) (then unreachable))
        (local.set _D_udp (i32.load (i32.const 196)))
        (i32.store (i32.const 224) (local.get _D_udp))
        (i32.store (i32.const 228) (i32.const 16))
        (i32.store (i32.const 232) (i32.const 4))
        (i32.store8 (i32.const 236) (i32.const 1))
        (i32.store8 (i32.const 240) (i32.const 0))
        (i32.store16 (i32.const 244) (i32.const ${udpPort}))
        (i32.store8 (i32.const 246) (i32.const 127))
        (i32.store8 (i32.const 247) (i32.const 0))
        (i32.store8 (i32.const 248) (i32.const 0))
        (i32.store8 (i32.const 249) (i32.const 1))
        (local.set _D_status (call _D_udp_send (i32.const 224) (i32.const 288)))
        (if (i32.ne (local.get _D_status) (i32.const 2))
          (then
            (if (i32.ne (i32.and (local.get _D_status) (i32.const 15)) (i32.const 1)) (then unreachable))
            (local.set _D_subtask (i32.shr_u (local.get _D_status) (i32.const 4)))
            (local.set _D_waitable_set (call _D_waitable_set_new))
            (call _D_waitable_join (local.get _D_subtask) (local.get _D_waitable_set))
            (if (i32.ne (call _D_waitable_set_wait (local.get _D_waitable_set) (i32.const 304)) (i32.const 1)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 304)) (local.get _D_subtask)) (then unreachable))
            (if (i32.ne (i32.load (i32.const 308)) (i32.const 2)) (then unreachable))
            (call _D_waitable_set_drop (local.get _D_waitable_set))))
        (if (i32.ne (i32.load8_u (i32.const 288)) (i32.const 0)) (then unreachable))
        (i32.const 42))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()

internal fun wasiPreview3StreamIntrinsicsWit(
    version: String = WasiPreview3.DEFAULT_VERSION,
): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-stream-intrinsics;

        world plugin {
          export api;
        }

        interface api {
          run: func() -> u32;
        }

        package wasi:cli@${version} {
          interface stdin {
            read-via-stream: func() -> tuple<stream<u8>, future<result>>;
          }
        }
        """
            .trimIndent()
    )

internal fun wasiPreview3StreamIntrinsicsWat(
    version: String = WasiPreview3.DEFAULT_VERSION,
): String =
    """
    (module
      (import "wasi:cli/stdin@${version}" "[stream-new-0]read-via-stream" (func _D_stream_new (result i64)))
      (import "wasi:cli/stdin@${version}" "[async-lower][stream-write-0]read-via-stream" (func _D_stream_write (param i32 i32 i32) (result i32)))
      (import "wasi:cli/stdin@${version}" "[stream-drop-writable-0]read-via-stream" (func _D_drop_writable (param i32)))
      (import "wasi:cli/stdin@${version}" "[async-lower][stream-read-0]read-via-stream" (func _D_stream_read (param i32 i32 i32) (result i32)))
      (import "wasi:cli/stdin@${version}" "[stream-drop-readable-0]read-via-stream" (func _D_drop_readable (param i32)))
      (memory (export "memory") 1)
      (data (i32.const 32) "abc")
      (func _D_run (result i32)
        (local _D_pair i64)
        (local _D_reader i32)
        (local _D_writer i32)
        (local _D_write_status i32)
        (local _D_read_status i32)
        (local.set _D_pair (call _D_stream_new))
        (local.set _D_reader (i32.wrap_i64 (local.get _D_pair)))
        (local.set _D_writer (i32.wrap_i64 (i64.shr_u (local.get _D_pair) (i64.const 32))))
        (local.set _D_write_status
          (call _D_stream_write
            (local.get _D_writer)
            (i32.const 32)
            (i32.const 3)))
        (if (i32.ne (local.get _D_write_status) (i32.const 48)) (then unreachable))
        (call _D_drop_writable (local.get _D_writer))
        (local.set _D_read_status
          (call _D_stream_read
            (local.get _D_reader)
            (i32.const 64)
            (i32.const 8)))
        (if (i32.ne (local.get _D_read_status) (i32.const 49)) (then unreachable))
        (call _D_drop_readable (local.get _D_reader))
        (i32.add
          (i32.add
            (i32.load8_u (i32.const 64))
            (i32.load8_u (i32.const 65)))
          (i32.load8_u (i32.const 66))))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()

internal fun wasiPreview3FutureIntrinsicsWit(): WitPackage =
    WitPackage.parse(
        """
        package sample:wasi3-future-intrinsics;

        world plugin {
          import seed: func() -> future<u32>;
          export api;
        }

        interface api {
          run: func() -> u32;
        }
        """
            .trimIndent()
    )

internal fun wasiPreview3FutureIntrinsicsWat(): String =
    """
    (module
      (import "plugin" "[future-new-0]seed" (func _D_future_new (result i64)))
      (import "plugin" "[async-lower][future-write-0]seed" (func _D_future_write (param i32 i32) (result i32)))
      (import "plugin" "[async-lower][future-read-0]seed" (func _D_future_read (param i32 i32) (result i32)))
      (import "plugin" "[future-drop-writable-0]seed" (func _D_drop_writable (param i32)))
      (import "plugin" "[future-drop-readable-0]seed" (func _D_drop_readable (param i32)))
      (memory (export "memory") 1)
      (func _D_run (result i32)
        (local _D_pair i64)
        (local _D_reader i32)
        (local _D_writer i32)
        (local _D_status i32)
        (local.set _D_pair (call _D_future_new))
        (local.set _D_reader (i32.wrap_i64 (local.get _D_pair)))
        (local.set _D_writer (i32.wrap_i64 (i64.shr_u (local.get _D_pair) (i64.const 32))))
        (i32.store (i32.const 32) (i32.const 123456))
        (local.set _D_status
          (call _D_future_write
            (local.get _D_writer)
            (i32.const 32)))
        (if (i32.ne (local.get _D_status) (i32.const 0)) (then unreachable))
        (call _D_drop_writable (local.get _D_writer))
        (local.set _D_status
          (call _D_future_read
            (local.get _D_reader)
            (i32.const 64)))
        (if (i32.ne (local.get _D_status) (i32.const 0)) (then unreachable))
        (call _D_drop_readable (local.get _D_reader))
        (i32.load (i32.const 64)))
      (export "api.run" (func _D_run))
    )
    """.trimIndent()
