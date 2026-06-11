package uk.shusek.krwa.sample

import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

internal data class HttpFetchResponse(val status: Int, val body: String)

internal val kotlinWasiHttpModule = "sample:kotlin-wasi/host-http"
internal val kotlinWasiHttpPath = "/products/1"
internal val kotlinWasiProductJson =
    """
    {"id":1,"title":"Essence Mascara Lash Princess","description":"The Essence Mascara Lash Princess is a popular mascara known for its volumizing and lengthening effects.","category":"beauty","price":9.99,"rating":4.94,"stock":5,"tags":["beauty","mascara"],"brand":"Essence","availabilityStatus":"Low Stock","dimensions":{"width":23.17,"height":14.43,"depth":28.01}}
    """
        .trimIndent()
internal val kotlinWasiProductSummary =
    "1:Essence Mascara Lash Princess:beauty:9.99:4.94:beauty+mascara:Low Stock"
internal val kotlinWasiProductsJson =
    """
    [
      {"id":1,"title":"Essence Mascara Lash Princess","category":"beauty","price":9.99,"rating":4.94,"stock":5,"tags":["beauty","mascara"],"brand":"Essence","availabilityStatus":"Low Stock","dimensions":{"width":23.17,"height":14.43,"depth":28.01}},
      {"id":2,"title":"Eyeshadow Palette with Mirror","category":"beauty","price":19.99,"rating":3.28,"stock":44,"tags":["beauty","eyeshadow"],"brand":"Glamour Beauty","availabilityStatus":"In Stock","dimensions":{"width":12.42,"height":8.63,"depth":29.13}},
      {"id":3,"title":"Powder Canister","category":"beauty","price":14.99,"rating":3.82,"stock":59,"tags":["beauty","face powder"],"brand":"Velvet Touch","availabilityStatus":"In Stock","dimensions":{"width":24.16,"height":10.7,"depth":11.07}}
    ]
    """
        .trimIndent()
internal val kotlinWasiMalformedProductsJson =
    """
    [
      {"id":1,"title":"Essence Mascara Lash Princess","category":"beauty","price":9.99,"rating":4.94,"stock":5,"tags":["beauty","mascara"],"brand":"Essence","availabilityStatus":"Low Stock","dimensions":{"width":23.17,"height":14.43,"depth":28.01}},
      {"id":2,"title":"Broken Stream Product","category":"beauty","price":
    ]
    """
        .trimIndent()
internal const val kotlinWasiInvalidJsonArgument = "--expect-invalid-json"
internal const val kotlinWasiStreamChunkSize = 64
internal val kotlinWasiProductsChunks =
    (kotlinWasiProductsJson.encodeToByteArray().size + kotlinWasiStreamChunkSize - 1) /
        kotlinWasiStreamChunkSize
internal val kotlinWasiProductsReport =
    "count=3;stock=108;top=Essence Mascara Lash Princess;beauty=3;chunks=$kotlinWasiProductsChunks"
internal val kotlinWasiProductsReportJson =
    """{"count":3,"stock":108,"top":"Essence Mascara Lash Princess","beauty":3,"chunks":$kotlinWasiProductsChunks}"""
internal const val kotlinWasiSeekAppendFile = "krwa-wasi-seek-probe.txt"
internal const val kotlinWasiSeekAppendPayload = "seek-header|seek-body"
internal const val kotlinWasiSeekAppendReport = "$kotlinWasiSeekAppendPayload;offsets=11/21"
internal const val kotlinWasiRandomAccessFile = "krwa-wasi-random-access-probe.txt"
internal const val kotlinWasiRandomAccessPayload = "K12-WASI-9|tail"
internal const val kotlinWasiRandomAccessReport =
    "payload=$kotlinWasiRandomAccessPayload;slice=WASI;cursor=10;size=15;type=4"
internal const val kotlinWasiMetadataSyncFile = "krwa-wasi-metadata-sync-probe.txt"
internal const val kotlinWasiMetadataSyncPayload = "metadata-sync-ok"
internal const val kotlinWasiMetadataSyncReport =
    "fdtype=4;rights=2097271;sync=ok;fd-size=16;path-size=16;path-type=4;times=true"
internal const val kotlinWasiMutationFile = "krwa-wasi-mutation-probe.txt"
internal const val kotlinWasiMutationPayload = "mutable"
internal const val kotlinWasiMutationReport =
    "allocated=64;truncated=7;payload=mutable;atime=1700000000;mtime=1700000001;advise=ok"
internal const val kotlinWasiCapabilityFile = "krwa-wasi-capability-probe.txt"
internal const val kotlinWasiCapabilityPayload = "capability-ok"
internal const val kotlinWasiCapabilityReport =
    "before=2097254;after=2097190;write-errno=76;payload=capability-ok"
internal const val kotlinWasiSandboxPayload = "outside-host-only"
internal const val kotlinWasiSandboxReport = "errno=63;blocked=true"
internal const val kotlinWasiFdFlagsFile = "krwa-wasi-fd-flags-probe.txt"
internal const val kotlinWasiFdFlagsPayload = "fd-flags-ok"
internal const val kotlinWasiFdFlagsReport = "before=0;after=17;payload=fd-flags-ok"
internal const val kotlinWasiPathLinkProbeDir = "krwa-wasi-link-probe"
internal const val kotlinWasiPathLinkReport = "source=linked;survives-unlink=path-link-ok;cleanup=ok"
internal const val kotlinWasiPathSymlinkProbeDir = "krwa-wasi-symlink-probe"
internal const val kotlinWasiPathSymlinkReport =
    "target=target.txt;nofollow-errno=32;follow=path-symlink-ok;cleanup=ok"
internal const val kotlinWasiDirectoryProbeDir = "krwa-wasi-dir-probe"
internal const val kotlinWasiDirectoryLifecycleReport =
    "dir=created;rename=ok;read=directory-lifecycle-ok;cleanup=ok"
internal const val kotlinWasiReaddirProbeDir = "krwa-wasi-readdir-probe"
internal const val kotlinWasiReaddirReport = "entries=alpha.txt:4,beta.txt:4;count=2"
internal const val kotlinWasiPollReport = "clock=1;userdata=42424242;type=0;errno=0"
internal const val kotlinWasiClockResolutionReport = "realtime=true;monotonic=true"

internal data class KotlinWasiGuestFileSnapshot(
    val probeText: String,
    val seekAppendText: String,
    val randomAccessText: String,
    val metadataSyncText: String,
    val mutationText: String,
    val capabilityText: String,
    val sandboxText: String,
    val fdFlagsText: String,
    val pathLinkProbeExists: Boolean,
    val pathSymlinkProbeExists: Boolean,
    val directoryProbeExists: Boolean,
    val readdirProbeExists: Boolean,
    val productsReportText: String,
    val productsReportJsonText: String,
)

internal fun assertKotlinWasiPreview1Output(stdout: String, stderr: String) {
    require(stdout.contains("Hello from Kotlin/WASI 2.4")) { stdout }
    require(stdout.contains("args.wasi=kotlin-guest.wasm,alpha,beta")) { stdout }
    require(stdout.contains("clock.realtime=true")) { stdout }
    require(stdout.contains("clock.monotonic=true")) { stdout }
    require(stdout.contains("clock.resolution=$kotlinWasiClockResolutionReport")) { stdout }
    require(stdout.contains("coroutine.result=42")) { stdout }
    require(stdout.contains("poll.clock=$kotlinWasiPollReport")) { stdout }
    require(stdout.contains("env.KRWA_SAMPLE=preview1")) { stdout }
    val randomChecksum =
        Regex("""random\.checksum=(\d+)""").find(stdout)?.groupValues?.get(1)?.toInt()
    require(randomChecksum != null && randomChecksum > 0) { stdout }
    require(stdout.contains("fs.roundtrip=preview1-file-ok")) { stdout }
    require(stdout.contains("fs.seek-append=$kotlinWasiSeekAppendReport")) { stdout }
    require(stdout.contains("fs.random-access=$kotlinWasiRandomAccessReport")) { stdout }
    require(stdout.contains("fs.metadata-sync=$kotlinWasiMetadataSyncReport")) { stdout }
    require(stdout.contains("fs.mutation=$kotlinWasiMutationReport")) { stdout }
    require(stdout.contains("fs.capability=$kotlinWasiCapabilityReport")) { stdout }
    require(stdout.contains("fs.sandbox=$kotlinWasiSandboxReport")) { stdout }
    require(stdout.contains("fs.fd-flags=$kotlinWasiFdFlagsReport")) { stdout }
    require(stdout.contains("fs.path-link=$kotlinWasiPathLinkReport")) { stdout }
    require(stdout.contains("fs.path-symlink=$kotlinWasiPathSymlinkReport")) { stdout }
    require(stdout.contains("fs.dir-lifecycle=$kotlinWasiDirectoryLifecycleReport")) { stdout }
    require(stdout.contains("fs.readdir=$kotlinWasiReaddirReport")) { stdout }
    require(stdout.contains("http.status=200")) { stdout }
    require(stdout.contains("json.product=$kotlinWasiProductSummary")) { stdout }
    require(stdout.contains("stdin.products=$kotlinWasiProductsReport")) { stdout }
    require(stdout.contains("fs.report.readback=$kotlinWasiProductsReport")) { stdout }
    require(stderr.contains("stderr.probe=ok")) { "Unexpected stderr: $stderr" }
}

internal fun assertKotlinWasiComponentOutput(stdout: String, stderr: String, label: String) {
    require(stdout.contains("component.stdout=ok")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.clock.resolution=$kotlinWasiClockResolutionReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.poll.clock=$kotlinWasiPollReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.seek-append=$kotlinWasiSeekAppendReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.random-access=$kotlinWasiRandomAccessReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.metadata-sync=$kotlinWasiMetadataSyncReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.mutation=$kotlinWasiMutationReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.capability=$kotlinWasiCapabilityReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.sandbox=$kotlinWasiSandboxReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.fd-flags=$kotlinWasiFdFlagsReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.path-link=$kotlinWasiPathLinkReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.path-symlink=$kotlinWasiPathSymlinkReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.dir-lifecycle=$kotlinWasiDirectoryLifecycleReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.readdir=$kotlinWasiReaddirReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.http.status=200")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.json.product=$kotlinWasiProductSummary")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.stdin.products=$kotlinWasiProductsReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stdout.contains("component.fs.report.readback=$kotlinWasiProductsReport")) {
        "Unexpected $label stdout: $stdout"
    }
    require(stderr.contains("component.stderr=ok")) {
        "Unexpected $label stderr: $stderr"
    }
}

internal fun assertKotlinWasiGuestFiles(snapshot: KotlinWasiGuestFileSnapshot, label: String) {
    requireValue("preview1-file-ok", snapshot.probeText, "$label preopened file")
    requireValue(kotlinWasiSeekAppendPayload, snapshot.seekAppendText, "$label fd_seek/fd_tell file")
    requireValue(kotlinWasiRandomAccessPayload, snapshot.randomAccessText, "$label fd_pwrite/fd_pread file")
    requireValue(kotlinWasiMetadataSyncPayload, snapshot.metadataSyncText, "$label fd sync/stat file")
    requireValue(kotlinWasiMutationPayload, snapshot.mutationText, "$label fd allocate/truncate/times file")
    requireValue(kotlinWasiCapabilityPayload, snapshot.capabilityText, "$label descriptor capability file")
    requireValue(kotlinWasiSandboxPayload, snapshot.sandboxText, "$label preopen sandbox sentinel")
    requireValue(kotlinWasiFdFlagsPayload, snapshot.fdFlagsText, "$label descriptor flags file")
    require(!snapshot.pathLinkProbeExists) { "$label path_link probe did not clean up" }
    require(!snapshot.pathSymlinkProbeExists) { "$label path_symlink probe did not clean up" }
    require(!snapshot.directoryProbeExists) { "$label directory lifecycle did not clean up" }
    require(!snapshot.readdirProbeExists) { "$label readdir probe did not clean up" }
    requireValue(kotlinWasiProductsReport, snapshot.productsReportText, "$label streaming products report")
    requireValue(kotlinWasiProductsReportJson, snapshot.productsReportJsonText, "$label serialized products report")
}

internal fun kotlinWasiPluginWit(): String =
    """
    package sample:kotlin-wasi;

    interface api {
      run: func() -> u32;
    }

    interface host-http {
      fetch: func(path-with-query: string, body-buffer-ptr: u32, body-buffer-len: u32) -> u32;
    }

    world plugin {
      import host-http;
      export api;
    }
    """
        .trimIndent()

internal fun kotlinWasiUnusedHttpHostFunction(onCall: () -> Unit): HostFunction =
    HostFunction(
        kotlinWasiHttpModule,
        "fetch",
        FunctionType.of(
            listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
            listOf(ValType.I32),
        ),
        WasmFunctionHandle { _, _ ->
            onCall()
            longArrayOf(599L)
        },
    )

internal fun assertMalformedStdinOutput(text: String, prefix: String, label: String) {
    val match = Regex("""${Regex.escape(prefix)}handled=true;chunks=(\d+)""").find(text)
    require(match != null && match.groupValues[1].toInt() > 0) {
        "Unexpected $label stdout: $text"
    }
}

internal fun <T> requireValue(expected: T, actual: T, label: String) {
    require(expected == actual) { "$label: expected <$expected>, got <$actual>" }
}
