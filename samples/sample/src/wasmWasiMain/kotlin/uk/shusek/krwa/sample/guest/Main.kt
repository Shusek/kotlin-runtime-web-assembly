@file:OptIn(
    kotlin.wasm.ExperimentalWasmInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package uk.shusek.krwa.sample.guest

import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.io.decodeSourceToSequence

private const val CLOCK_REALTIME = 0
private const val CLOCK_MONOTONIC = 1
private const val EVENTTYPE_CLOCK = 0
private const val LOOKUP_SYMLINK_FOLLOW = 1
private const val ERRNO_SUCCESS = 0
private const val ERRNO_LOOP = 32
private const val PREOPEN_FD_START = 3
private const val PREOPEN_FD_END = 16
private const val ERRNO_PERM = 63
private const val OFLAGS_CREAT = 1
private const val OFLAGS_TRUNC = 8
private const val RIGHTS_FD_DATASYNC = 1L
private const val RIGHTS_FD_READ = 2L
private const val RIGHTS_FD_SEEK = 4L
private const val RIGHTS_FD_FDSTAT_SET_FLAGS = 8L
private const val RIGHTS_FD_SYNC = 16L
private const val RIGHTS_FD_TELL = 32L
private const val RIGHTS_FD_WRITE = 64L
private const val RIGHTS_FD_ADVISE = 128L
private const val RIGHTS_FD_ALLOCATE = 256L
private const val RIGHTS_FD_READDIR = 16384L
private const val RIGHTS_FD_FILESTAT_GET = 2097152L
private const val RIGHTS_FD_FILESTAT_SET_SIZE = 4194304L
private const val WHENCE_SET = 0
private const val WHENCE_END = 2
private const val ADVICE_RANDOM = 2
private const val FDFLAGS_APPEND = 1
private const val FDFLAGS_SYNC = 16
private const val FSTFLAGS_ATIM = 1
private const val FSTFLAGS_MTIM = 4
private const val ERRNO_NOTCAPABLE = 76
private const val HOST_HTTP_MODULE = "sample:kotlin-wasi/host-http"
private const val HTTP_PROBE_PATH = "/products/1"
private const val HTTP_EXPECTED_STATUS = 200
private const val HTTP_FETCH_BODY_CAPACITY = 4096
private const val PRODUCT_JSON_ENV = "KRWA_PRODUCT_JSON"
private const val PRODUCT_REPORT_PATH = "krwa-wasi-products-report.txt"
private const val PRODUCT_REPORT_JSON_PATH = "krwa-wasi-products-report.json"
private const val SEEK_APPEND_PATH = "krwa-wasi-seek-probe.txt"
private const val EXPECTED_SEEK_APPEND_REPORT = "seek-header|seek-body;offsets=11/21"
private const val RANDOM_ACCESS_PATH = "krwa-wasi-random-access-probe.txt"
private const val RANDOM_ACCESS_BASE = "0123456789"
private const val EXPECTED_RANDOM_ACCESS_PAYLOAD = "K12-WASI-9|tail"
private const val EXPECTED_RANDOM_ACCESS_REPORT =
    "payload=K12-WASI-9|tail;slice=WASI;cursor=10;size=15;type=4"
private const val METADATA_SYNC_PATH = "krwa-wasi-metadata-sync-probe.txt"
private const val METADATA_SYNC_PAYLOAD = "metadata-sync-ok"
private const val EXPECTED_METADATA_SYNC_REPORT =
    "fdtype=4;rights=2097271;sync=ok;fd-size=16;path-size=16;path-type=4;times=true"
private const val MUTATION_PATH = "krwa-wasi-mutation-probe.txt"
private const val MUTATION_INITIAL_PAYLOAD = "mutable-wasi"
private const val MUTATION_FINAL_PAYLOAD = "mutable"
private const val MUTATION_ALLOCATED_SIZE = 64L
private const val MUTATION_ATIME_SECONDS = 1_700_000_000L
private const val MUTATION_MTIME_SECONDS = 1_700_000_001L
private const val EXPECTED_MUTATION_REPORT =
    "allocated=64;truncated=7;payload=mutable;atime=1700000000;mtime=1700000001;advise=ok"
private const val CAPABILITY_PATH = "krwa-wasi-capability-probe.txt"
private const val CAPABILITY_PAYLOAD = "capability-ok"
private const val EXPECTED_CAPABILITY_REPORT =
    "before=2097254;after=2097190;write-errno=76;payload=capability-ok"
private const val SANDBOX_ESCAPE_ENV = "KRWA_SANDBOX_ESCAPE_PATH"
private const val SANDBOX_DEFAULT_ESCAPE_PATH = "../krwa-wasi-outside.txt"
private const val EXPECTED_SANDBOX_REPORT = "errno=63;blocked=true"
private const val FD_FLAGS_PATH = "krwa-wasi-fd-flags-probe.txt"
private const val FD_FLAGS_PAYLOAD = "fd-flags-ok"
private const val EXPECTED_FD_FLAGS_REPORT = "before=0;after=17;payload=fd-flags-ok"
private const val PATH_LINK_PROBE_DIR = "krwa-wasi-link-probe"
private const val PATH_LINK_SOURCE_PATH = "$PATH_LINK_PROBE_DIR/source.txt"
private const val PATH_LINK_LINKED_PATH = "$PATH_LINK_PROBE_DIR/linked.txt"
private const val PATH_LINK_PAYLOAD = "path-link-ok"
private const val EXPECTED_PATH_LINK_REPORT = "source=linked;survives-unlink=path-link-ok;cleanup=ok"
private const val PATH_SYMLINK_PROBE_DIR = "krwa-wasi-symlink-probe"
private const val PATH_SYMLINK_TARGET_NAME = "target.txt"
private const val PATH_SYMLINK_TARGET_PATH = "$PATH_SYMLINK_PROBE_DIR/$PATH_SYMLINK_TARGET_NAME"
private const val PATH_SYMLINK_LINK_PATH = "$PATH_SYMLINK_PROBE_DIR/link.txt"
private const val PATH_SYMLINK_PAYLOAD = "path-symlink-ok"
private const val EXPECTED_PATH_SYMLINK_REPORT =
    "target=target.txt;nofollow-errno=32;follow=path-symlink-ok;cleanup=ok"
private const val DIRECTORY_PROBE_DIR = "krwa-wasi-dir-probe"
private const val DIRECTORY_SOURCE_PATH = "$DIRECTORY_PROBE_DIR/source.txt"
private const val DIRECTORY_RENAMED_PATH = "$DIRECTORY_PROBE_DIR/renamed.txt"
private const val DIRECTORY_PAYLOAD = "directory-lifecycle-ok"
private const val EXPECTED_DIRECTORY_REPORT = "dir=created;rename=ok;read=directory-lifecycle-ok;cleanup=ok"
private const val READDIR_PROBE_DIR = "krwa-wasi-readdir-probe"
private const val READDIR_ALPHA_PATH = "$READDIR_PROBE_DIR/alpha.txt"
private const val READDIR_BETA_PATH = "$READDIR_PROBE_DIR/beta.txt"
private const val EXPECTED_READDIR_REPORT = "entries=alpha.txt:4,beta.txt:4;count=2"
private const val POLL_USERDATA = 42424242L
private const val EXPECTED_POLL_REPORT = "clock=1;userdata=42424242;type=0;errno=0"
private const val EXPECTED_CLOCK_RESOLUTION_REPORT = "realtime=true;monotonic=true"
private const val EXPECTED_PRODUCT_REPORT_PREFIX = "count=3;stock=108;top=Essence Mascara Lash Princess;beauty=3;chunks="
private const val EXPECTED_INVALID_JSON_PREFIX = "handled=true;chunks="
private const val STREAM_READ_CHUNK_SIZE = 64
private const val INVALID_JSON_ARGUMENT = "--expect-invalid-json"

private val ProductJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class Product(
    val id: Int,
    val title: String,
    val category: String,
    val price: Double,
    val rating: Double,
    val stock: Int,
    val tags: List<String> = emptyList(),
    val brand: String? = null,
    val availabilityStatus: String = "",
    val dimensions: ProductDimensions? = null,
)

@Serializable
private data class ProductDimensions(
    val width: Double,
    val height: Double,
    val depth: Double,
)

@Serializable
private data class ProductStreamReport(
    val count: Int,
    val stock: Int,
    val top: String,
    val beauty: Int,
    val chunks: Int,
)

private data class ProductStreamInput(val products: List<Product>, val chunks: Int)

private data class ProductStreamResult(val summary: String, val jsonReadback: String)

private data class HostHttpResponse(val status: Int, val body: String)

suspend fun main(args: Array<String>) {
    val wasiArgs = wasiArguments()
    if (INVALID_JSON_ARGUMENT in wasiArgs && !shouldRunCliStdinPipeline(wasiArgs)) {
        println("component.start.invalid-json=deferred")
        return
    }
    if (INVALID_JSON_ARGUMENT in wasiArgs) {
        println("Hello from Kotlin/WASI 2.4")
        println("args.main=${args.joinToString(",")}")
        println("args.wasi=${wasiArgs.joinToString(",")}")
        println("stdin.invalid-json=${wasiInvalidProductStreamReport()}")
        wasiWriteStderr("stderr.invalid-json=handled\n")
        return
    }
    val productResponse =
        if (shouldRunCliStdinPipeline(wasiArgs)) {
            wasiHttpFetchBody()
        } else {
            HostHttpResponse(wasiHttpStatus(), wasiEnvironmentValue(PRODUCT_JSON_ENV))
        }
    println("Hello from Kotlin/WASI 2.4")
    println("args.main=${args.joinToString(",")}")
    println("args.wasi=${wasiArgs.joinToString(",")}")
    println("clock.realtime=${wasiClockTime(CLOCK_REALTIME) > 0L}")
    println("clock.monotonic=${wasiClockTime(CLOCK_MONOTONIC) > 0L}")
    println("clock.resolution=${wasiClockResolutionProbe()}")
    println("coroutine.result=${runCoroutineProbe()}")
    println("poll.clock=${wasiPollClockProbe()}")
    println("env.KRWA_SAMPLE=${wasiEnvironmentValue("KRWA_SAMPLE")}")
    println("random.checksum=${wasiRandomChecksum()}")
    println("fs.roundtrip=${wasiFileRoundTrip()}")
    println("fs.seek-append=${wasiSeekAppendRoundTrip()}")
    println("fs.random-access=${wasiRandomAccessFileProbe()}")
    println("fs.metadata-sync=${wasiMetadataSyncProbe()}")
    println("fs.mutation=${wasiMutationProbe()}")
    println("fs.capability=${wasiCapabilityProbe()}")
    println("fs.sandbox=${wasiSandboxBoundaryProbe()}")
    println("fs.fd-flags=${wasiFdFlagsProbe()}")
    println("fs.path-link=${wasiPathLinkProbe()}")
    println("fs.path-symlink=${wasiPathSymlinkProbe()}")
    println("fs.dir-lifecycle=${wasiDirectoryLifecycle()}")
    println("fs.readdir=${wasiReaddirProbe()}")
    println("http.status=${productResponse.status}")
    println("json.product=${wasiProductSummary(productResponse.body)}")
    if (shouldRunCliStdinPipeline(wasiArgs)) {
        val stdinReport = wasiCoroutineProductStreamReport()
        println("stdin.products=${stdinReport.summary}")
        println("fs.report.readback=${stdinReport.jsonReadback}")
    }
    wasiWriteStderr("stderr.probe=ok\n")
}

@WasmExport("sample:kotlin-wasi/api#run")
fun runPreview1ComponentProbe(): Int {
    if (INVALID_JSON_ARGUMENT in wasiArguments()) {
        return runInvalidJsonComponentProbe()
    }
    val env = wasiEnvironmentValue("KRWA_SAMPLE")
    val clockResolution = wasiClockResolutionProbe()
    val pollClock = wasiPollClockProbe()
    val randomChecksum = wasiRandomChecksum()
    val file = wasiFileRoundTrip()
    val seekAppend = wasiSeekAppendRoundTrip()
    val randomAccess = wasiRandomAccessFileProbe()
    val metadataSync = wasiMetadataSyncProbe()
    val mutation = wasiMutationProbe()
    val capability = wasiCapabilityProbe()
    val sandbox = wasiSandboxBoundaryProbe()
    val fdFlags = wasiFdFlagsProbe()
    val pathLink = wasiPathLinkProbe()
    val pathSymlink = wasiPathSymlinkProbe()
    val directoryLifecycle = wasiDirectoryLifecycle()
    val readdir = wasiReaddirProbe()
    val httpStatus = wasiHttpStatus()
    val productSummary = wasiProductSummary(wasiEnvironmentValue(PRODUCT_JSON_ENV))
    val stdinReport = wasiProductStreamReport()
    wasiWriteStdout("component.stdout=ok\n")
    wasiWriteStdout("component.clock.resolution=$clockResolution\n")
    wasiWriteStdout("component.poll.clock=$pollClock\n")
    wasiWriteStdout("component.fs.seek-append=$seekAppend\n")
    wasiWriteStdout("component.fs.random-access=$randomAccess\n")
    wasiWriteStdout("component.fs.metadata-sync=$metadataSync\n")
    wasiWriteStdout("component.fs.mutation=$mutation\n")
    wasiWriteStdout("component.fs.capability=$capability\n")
    wasiWriteStdout("component.fs.sandbox=$sandbox\n")
    wasiWriteStdout("component.fs.fd-flags=$fdFlags\n")
    wasiWriteStdout("component.fs.path-link=$pathLink\n")
    wasiWriteStdout("component.fs.path-symlink=$pathSymlink\n")
    wasiWriteStdout("component.fs.dir-lifecycle=$directoryLifecycle\n")
    wasiWriteStdout("component.fs.readdir=$readdir\n")
    wasiWriteStdout("component.http.status=$httpStatus\n")
    wasiWriteStdout("component.json.product=$productSummary\n")
    wasiWriteStdout("component.stdin.products=${stdinReport.summary}\n")
    wasiWriteStdout("component.fs.report.readback=${stdinReport.jsonReadback}\n")
    wasiWriteStderr("component.stderr=ok\n")
    return if (
        env == "component" &&
            clockResolution == EXPECTED_CLOCK_RESOLUTION_REPORT &&
            pollClock == EXPECTED_POLL_REPORT &&
            randomChecksum > 0 &&
            file == "preview1-file-ok" &&
            seekAppend == EXPECTED_SEEK_APPEND_REPORT &&
            randomAccess == EXPECTED_RANDOM_ACCESS_REPORT &&
            metadataSync == EXPECTED_METADATA_SYNC_REPORT &&
            mutation == EXPECTED_MUTATION_REPORT &&
            capability == EXPECTED_CAPABILITY_REPORT &&
            sandbox == EXPECTED_SANDBOX_REPORT &&
            fdFlags == EXPECTED_FD_FLAGS_REPORT &&
            pathLink == EXPECTED_PATH_LINK_REPORT &&
            pathSymlink == EXPECTED_PATH_SYMLINK_REPORT &&
            directoryLifecycle == EXPECTED_DIRECTORY_REPORT &&
            readdir == EXPECTED_READDIR_REPORT &&
            httpStatus == HTTP_EXPECTED_STATUS &&
            productSummary == "1:Essence Mascara Lash Princess:beauty:9.99:4.94:beauty+mascara:Low Stock" &&
            stdinReport.summary.startsWith(EXPECTED_PRODUCT_REPORT_PREFIX) &&
            stdinReport.summary == stdinReport.jsonReadback &&
            stdinReport.summary.substringAfter("chunks=").toInt() > 1
    ) {
        42
    } else {
        1
    }
}

private fun runInvalidJsonComponentProbe(): Int {
    val summary = wasiInvalidProductStreamReport()
    wasiWriteStdout("component.stdin.invalid-json=$summary\n")
    wasiWriteStderr("component.stderr.invalid-json=handled\n")
    return if (malformedJsonHandled(summary)) 43 else 1
}

private suspend fun runCoroutineProbe(): Int {
    delay(1)
    return 42
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiClockResolutionProbe(): String =
    withScopedMemoryAllocator { allocator ->
        val resolutionPtr = allocator.allocate(8).address.toInt()
        checkErrno(wasiClockResGet(CLOCK_REALTIME, resolutionPtr), "clock_res_get realtime")
        val realtime = loadI64(resolutionPtr)
        checkErrno(wasiClockResGet(CLOCK_MONOTONIC, resolutionPtr), "clock_res_get monotonic")
        val monotonic = loadI64(resolutionPtr)
        val report = "realtime=${realtime > 0L};monotonic=${monotonic > 0L}"
        check(report == EXPECTED_CLOCK_RESOLUTION_REPORT) {
            "unexpected clock resolution report: $report"
        }
        report
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPollClockProbe(): String =
    withScopedMemoryAllocator { allocator ->
        val subscription = allocator.allocate(48).address.toInt()
        val event = allocator.allocate(32).address.toInt()
        val eventCountPtr = allocator.allocate(4).address.toInt()

        storeI64(subscription, POLL_USERDATA)
        storeByte(subscription + 8, EVENTTYPE_CLOCK.toByte())
        storeI32(subscription + 16, CLOCK_MONOTONIC)
        storeI64(subscription + 24, 0L)
        storeI64(subscription + 32, 0L)
        storeI16(subscription + 40, 0)

        checkErrno(wasiPollOneoff(subscription, event, 1, eventCountPtr), "poll_oneoff")
        val eventCount = loadI32(eventCountPtr)
        val userData = loadI64(event)
        val errno = loadI16(event + 8).toInt() and 0xffff
        val eventType = loadByte(event + 10).toInt() and 0xff
        val report = "clock=$eventCount;userdata=$userData;type=$eventType;errno=$errno"
        check(report == EXPECTED_POLL_REPORT) { "unexpected poll report: $report" }
        report
    }

private fun shouldRunCliStdinPipeline(wasiArgs: List<String>): Boolean =
    "--component" !in wasiArgs && "--preview3-bridge" !in wasiArgs

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiArguments(): List<String> =
    withScopedMemoryAllocator { allocator ->
        val sizes = allocator.allocate(8).address.toInt()
        checkErrno(wasiArgsSizesGet(sizes, sizes + 4), "args_sizes_get")
        val count = loadI32(sizes)
        val bufferSize = loadI32(sizes + 4)
        val args = allocator.allocate(count * 4).address.toInt()
        val buffer = allocator.allocate(bufferSize).address.toInt()
        checkErrno(wasiArgsGet(args, buffer), "args_get")

        val result = ArrayList<String>()
        for (index in 0 until count) {
            val argPtr = loadI32(args + index * 4)
            result += loadUtf8Z(argPtr, buffer + bufferSize - argPtr)
        }
        result
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiEnvironmentValue(name: String): String =
    withScopedMemoryAllocator { allocator ->
        val sizes = allocator.allocate(8).address.toInt()
        checkErrno(wasiEnvironSizesGet(sizes, sizes + 4), "environ_sizes_get")
        val count = loadI32(sizes)
        val bufferSize = loadI32(sizes + 4)
        val environ = allocator.allocate(count * 4).address.toInt()
        val buffer = allocator.allocate(bufferSize).address.toInt()
        checkErrno(wasiEnvironGet(environ, buffer), "environ_get")

        val prefix = "$name="
        var result = ""
        for (index in 0 until count) {
            val entryPtr = loadI32(environ + index * 4)
            val entry = loadUtf8Z(entryPtr, buffer + bufferSize - entryPtr)
            if (entry.startsWith(prefix)) {
                result = entry.substring(prefix.length)
                break
            }
        }
        result
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiRandomChecksum(): Int =
    withScopedMemoryAllocator { allocator ->
        val data = allocator.allocate(8).address.toInt()
        checkErrno(wasiRandomGet(data, 8), "random_get")
        var checksum = 0
        for (index in 0 until 8) {
            checksum += loadByte(data + index).toInt() and 0xff
        }
        checksum
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiFileRoundTrip(): String {
    val fd = wasiPreopenFd("/")
    val path = "krwa-wasi-probe.txt"
    val payload = "preview1-file-ok"
    wasiWriteFile(fd, path, payload)
    return wasiReadFile(fd, path, payload.length)
}

private fun wasiSeekAppendRoundTrip(): String {
    val dirFd = wasiPreopenFd("/")
    val header = "seek-header"
    val body = "|seek-body"
    val expected = header + body
    val fd =
        wasiOpenFile(
            dirFd,
            SEEK_APPEND_PATH,
            OFLAGS_CREAT or OFLAGS_TRUNC,
            RIGHTS_FD_READ or RIGHTS_FD_WRITE,
        )
    try {
        wasiWriteOpenFd(fd, header)
        val headerOffset = wasiFdTellValue(fd)
        check(headerOffset == header.length.toLong()) { "unexpected header offset: $headerOffset" }
        val endOffset = wasiFdSeekValue(fd, 0L, WHENCE_END)
        check(endOffset == headerOffset) { "unexpected seek-end offset: $endOffset" }
        wasiWriteOpenFd(fd, body)
        val finalOffset = wasiFdTellValue(fd)
        check(finalOffset == expected.length.toLong()) { "unexpected final offset: $finalOffset" }
        check(wasiFdSeekValue(fd, 0L, WHENCE_SET) == 0L) { "failed to rewind seek probe" }
        val readback = wasiReadOpenFd(fd, expected.length)
        check(readback == expected) { "seek append readback mismatch: $readback" }
        return "$readback;offsets=$headerOffset/$finalOffset"
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close seek append")
    }
}

private fun wasiRandomAccessFileProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val fd =
        wasiOpenFile(
            dirFd,
            RANDOM_ACCESS_PATH,
            OFLAGS_CREAT or OFLAGS_TRUNC,
            RIGHTS_FD_READ or
                RIGHTS_FD_WRITE or
                RIGHTS_FD_SEEK or
                RIGHTS_FD_TELL or
                RIGHTS_FD_FILESTAT_GET,
        )
    try {
        wasiWriteOpenFd(fd, RANDOM_ACCESS_BASE)
        val cursorBeforeRandomAccess = wasiFdTellValue(fd)
        check(cursorBeforeRandomAccess == RANDOM_ACCESS_BASE.length.toLong()) {
            "unexpected random-access cursor before pwrite: $cursorBeforeRandomAccess"
        }
        wasiPwriteOpenFd(fd, "K", 0L)
        wasiPwriteOpenFd(fd, "-WASI-", 3L)
        wasiPwriteOpenFd(fd, "|tail", 10L)
        val cursorAfterRandomAccess = wasiFdTellValue(fd)
        check(cursorAfterRandomAccess == cursorBeforeRandomAccess) {
            "fd_pwrite moved cursor from $cursorBeforeRandomAccess to $cursorAfterRandomAccess"
        }
        val payload = wasiPreadOpenFd(fd, 0L, EXPECTED_RANDOM_ACCESS_PAYLOAD.length)
        val slice = wasiPreadOpenFd(fd, 4L, 4)
        check(payload == EXPECTED_RANDOM_ACCESS_PAYLOAD) {
            "random-access payload mismatch: $payload"
        }
        check(slice == "WASI") { "random-access slice mismatch: $slice" }
        val stat = wasiFdFilestatValue(fd)
        val report =
            "payload=$payload;slice=$slice;cursor=$cursorAfterRandomAccess;size=${stat.size};type=${stat.fileType}"
        check(report == EXPECTED_RANDOM_ACCESS_REPORT) {
            "unexpected random-access report: $report"
        }
        return report
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close random access")
    }
}

private fun wasiMetadataSyncProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val rights =
        RIGHTS_FD_DATASYNC or
            RIGHTS_FD_READ or
            RIGHTS_FD_SEEK or
            RIGHTS_FD_SYNC or
            RIGHTS_FD_TELL or
            RIGHTS_FD_WRITE or
            RIGHTS_FD_FILESTAT_GET
    val fd = wasiOpenFile(dirFd, METADATA_SYNC_PATH, OFLAGS_CREAT or OFLAGS_TRUNC, rights)
    try {
        wasiWriteOpenFd(fd, METADATA_SYNC_PAYLOAD)
        checkErrno(wasiFdDatasync(fd), "fd_datasync")
        checkErrno(wasiFdSync(fd), "fd_sync")
        val fdStat = wasiFdStatValue(fd)
        val fileStat = wasiFdFilestatValue(fd)
        val pathStat = wasiPathFilestatValue(dirFd, METADATA_SYNC_PATH)
        check(fdStat.fileType == fileStat.fileType) {
            "fdstat/filestat type mismatch: $fdStat != $fileStat"
        }
        check(fileStat.size == METADATA_SYNC_PAYLOAD.length.toLong()) {
            "fd filestat size mismatch: $fileStat"
        }
        check(pathStat.size == fileStat.size && pathStat.fileType == fileStat.fileType) {
            "path/fd filestat mismatch: $pathStat != $fileStat"
        }
        val report =
            "fdtype=${fdStat.fileType};rights=${fdStat.rightsBase};sync=ok;" +
                "fd-size=${fileStat.size};path-size=${pathStat.size};path-type=${pathStat.fileType};" +
                "times=${pathStat.modifiedTime > 0L && pathStat.changedTime > 0L}"
        check(report == EXPECTED_METADATA_SYNC_REPORT) {
            "unexpected metadata sync report: $report"
        }
        return report
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close metadata sync")
    }
}

private fun wasiMutationProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val rights =
        RIGHTS_FD_READ or
            RIGHTS_FD_SEEK or
            RIGHTS_FD_TELL or
            RIGHTS_FD_WRITE or
            RIGHTS_FD_ADVISE or
            RIGHTS_FD_ALLOCATE or
            RIGHTS_FD_FILESTAT_GET or
            RIGHTS_FD_FILESTAT_SET_SIZE
    val fd = wasiOpenFile(dirFd, MUTATION_PATH, OFLAGS_CREAT or OFLAGS_TRUNC, rights)
    try {
        wasiWriteOpenFd(fd, MUTATION_INITIAL_PAYLOAD)
        checkErrno(
            wasiFdAdvise(fd, 0L, MUTATION_INITIAL_PAYLOAD.length.toLong(), ADVICE_RANDOM),
            "fd_advise",
        )
        checkErrno(wasiFdAllocate(fd, 0L, MUTATION_ALLOCATED_SIZE), "fd_allocate")
        val allocatedSize = wasiFdFilestatValue(fd).size
        check(allocatedSize == MUTATION_ALLOCATED_SIZE) {
            "fd_allocate size mismatch: $allocatedSize"
        }
        checkErrno(
            wasiFdFilestatSetSize(fd, MUTATION_FINAL_PAYLOAD.length.toLong()),
            "fd_filestat_set_size",
        )
        val truncatedSize = wasiFdFilestatValue(fd).size
        val payload = wasiPreadOpenFd(fd, 0L, MUTATION_FINAL_PAYLOAD.length)
        check(truncatedSize == MUTATION_FINAL_PAYLOAD.length.toLong()) {
            "fd_filestat_set_size mismatch: $truncatedSize"
        }
        check(payload == MUTATION_FINAL_PAYLOAD) { "mutation payload mismatch: $payload" }
        wasiPathFilestatSetTimesValue(
            dirFd,
            MUTATION_PATH,
            MUTATION_ATIME_SECONDS * 1_000_000_000L,
            MUTATION_MTIME_SECONDS * 1_000_000_000L,
            FSTFLAGS_ATIM or FSTFLAGS_MTIM,
        )
        val stat = wasiPathFilestatValue(dirFd, MUTATION_PATH)
        val accessSeconds = stat.accessTime / 1_000_000_000L
        val modifiedSeconds = stat.modifiedTime / 1_000_000_000L
        val report =
            "allocated=$allocatedSize;truncated=$truncatedSize;payload=$payload;" +
                "atime=$accessSeconds;mtime=$modifiedSeconds;advise=ok"
        check(report == EXPECTED_MUTATION_REPORT) {
            "unexpected mutation report: $report"
        }
        return report
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close mutation")
    }
}

private fun wasiCapabilityProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val initialRights =
        RIGHTS_FD_READ or
            RIGHTS_FD_SEEK or
            RIGHTS_FD_TELL or
            RIGHTS_FD_WRITE or
            RIGHTS_FD_FILESTAT_GET
    val narrowedRights =
        RIGHTS_FD_READ or
            RIGHTS_FD_SEEK or
            RIGHTS_FD_TELL or
            RIGHTS_FD_FILESTAT_GET
    val fd = wasiOpenFile(dirFd, CAPABILITY_PATH, OFLAGS_CREAT or OFLAGS_TRUNC, initialRights)
    try {
        wasiWriteOpenFd(fd, CAPABILITY_PAYLOAD)
        val before = wasiFdStatValue(fd)
        check(before.rightsBase == initialRights) {
            "unexpected initial descriptor rights: ${before.rightsBase}"
        }
        checkErrno(
            wasiFdFdstatSetRights(fd, narrowedRights, 0L),
            "fd_fdstat_set_rights",
        )
        val after = wasiFdStatValue(fd)
        check(after.rightsBase == narrowedRights) {
            "unexpected narrowed descriptor rights: ${after.rightsBase}"
        }
        val writeErrno = wasiTryWriteOpenFd(fd, "!")
        check(writeErrno == ERRNO_NOTCAPABLE) {
            "unexpected write errno after rights narrowing: $writeErrno"
        }
        val payload = wasiPreadOpenFd(fd, 0L, CAPABILITY_PAYLOAD.length)
        check(payload == CAPABILITY_PAYLOAD) {
            "capability payload changed after denied write: $payload"
        }
        val report =
            "before=${before.rightsBase};after=${after.rightsBase};write-errno=$writeErrno;payload=$payload"
        check(report == EXPECTED_CAPABILITY_REPORT) {
            "unexpected capability report: $report"
        }
        return report
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close capability")
    }
}

private fun wasiSandboxBoundaryProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val escapePath = wasiEnvironmentValue(SANDBOX_ESCAPE_ENV).ifBlank { SANDBOX_DEFAULT_ESCAPE_PATH }
    val errno = wasiTryOpenFile(dirFd, escapePath, 0, RIGHTS_FD_READ)
    val report = "errno=$errno;blocked=${errno == ERRNO_PERM}"
    check(report == EXPECTED_SANDBOX_REPORT) {
        "unexpected sandbox report for $escapePath: $report"
    }
    return report
}

private fun wasiFdFlagsProbe(): String {
    val dirFd = wasiPreopenFd("/")
    val rights =
        RIGHTS_FD_READ or
            RIGHTS_FD_WRITE or
            RIGHTS_FD_FDSTAT_SET_FLAGS or
            RIGHTS_FD_FILESTAT_GET
    val fd = wasiOpenFile(dirFd, FD_FLAGS_PATH, OFLAGS_CREAT or OFLAGS_TRUNC, rights)
    try {
        val before = wasiFdStatValue(fd)
        check(before.flags == 0) { "unexpected initial fd flags: ${before.flags}" }
        checkErrno(
            wasiFdFdstatSetFlags(fd, FDFLAGS_APPEND or FDFLAGS_SYNC),
            "fd_fdstat_set_flags",
        )
        val after = wasiFdStatValue(fd)
        check(after.flags == (FDFLAGS_APPEND or FDFLAGS_SYNC)) {
            "unexpected fd flags after set: ${after.flags}"
        }
        wasiWriteOpenFd(fd, FD_FLAGS_PAYLOAD)
        val payload = wasiPreadOpenFd(fd, 0L, FD_FLAGS_PAYLOAD.length)
        check(payload == FD_FLAGS_PAYLOAD) { "fd flags payload mismatch: $payload" }
        val report = "before=${before.flags};after=${after.flags};payload=$payload"
        check(report == EXPECTED_FD_FLAGS_REPORT) {
            "unexpected fd flags report: $report"
        }
        return report
    } finally {
        checkErrno(wasiFdClose(fd), "fd_close fd flags")
    }
}

private fun wasiPathLinkProbe(): String {
    val dirFd = wasiPreopenFd("/")
    wasiPathCreateDirectoryValue(dirFd, PATH_LINK_PROBE_DIR)
    wasiWriteFile(dirFd, PATH_LINK_SOURCE_PATH, PATH_LINK_PAYLOAD)
    wasiPathLinkValue(dirFd, PATH_LINK_SOURCE_PATH, dirFd, PATH_LINK_LINKED_PATH)
    wasiPathUnlinkFileValue(dirFd, PATH_LINK_SOURCE_PATH)
    val readback = wasiReadFile(dirFd, PATH_LINK_LINKED_PATH, PATH_LINK_PAYLOAD.length)
    check(readback == PATH_LINK_PAYLOAD) { "path_link readback mismatch: $readback" }
    wasiPathUnlinkFileValue(dirFd, PATH_LINK_LINKED_PATH)
    wasiPathRemoveDirectoryValue(dirFd, PATH_LINK_PROBE_DIR)
    return EXPECTED_PATH_LINK_REPORT
}

private fun wasiPathSymlinkProbe(): String {
    val dirFd = wasiPreopenFd("/")
    wasiPathCreateDirectoryValue(dirFd, PATH_SYMLINK_PROBE_DIR)
    wasiWriteFile(dirFd, PATH_SYMLINK_TARGET_PATH, PATH_SYMLINK_PAYLOAD)
    wasiPathSymlinkValue(PATH_SYMLINK_TARGET_NAME, dirFd, PATH_SYMLINK_LINK_PATH)
    val target =
        wasiPathReadlinkValue(dirFd, PATH_SYMLINK_LINK_PATH, PATH_SYMLINK_TARGET_NAME.length)
    check(target == PATH_SYMLINK_TARGET_NAME) { "path_readlink target mismatch: $target" }
    val noFollowErrno = wasiTryOpenFile(dirFd, PATH_SYMLINK_LINK_PATH, 0, RIGHTS_FD_READ)
    check(noFollowErrno == ERRNO_LOOP) {
        "path_open symlink without follow returned $noFollowErrno"
    }
    val readback =
        wasiReadFile(
            dirFd,
            PATH_SYMLINK_LINK_PATH,
            PATH_SYMLINK_PAYLOAD.length,
            lookupFlags = LOOKUP_SYMLINK_FOLLOW,
        )
    check(readback == PATH_SYMLINK_PAYLOAD) { "path_symlink readback mismatch: $readback" }
    wasiPathUnlinkFileValue(dirFd, PATH_SYMLINK_LINK_PATH)
    wasiPathUnlinkFileValue(dirFd, PATH_SYMLINK_TARGET_PATH)
    wasiPathRemoveDirectoryValue(dirFd, PATH_SYMLINK_PROBE_DIR)
    val report = "target=$target;nofollow-errno=$noFollowErrno;follow=$readback;cleanup=ok"
    check(report == EXPECTED_PATH_SYMLINK_REPORT) { "unexpected path_symlink report: $report" }
    return report
}

private fun wasiDirectoryLifecycle(): String {
    val dirFd = wasiPreopenFd("/")
    wasiPathCreateDirectoryValue(dirFd, DIRECTORY_PROBE_DIR)
    wasiWriteFile(dirFd, DIRECTORY_SOURCE_PATH, DIRECTORY_PAYLOAD)
    wasiPathRenameValue(dirFd, DIRECTORY_SOURCE_PATH, dirFd, DIRECTORY_RENAMED_PATH)
    val readback = wasiReadFile(dirFd, DIRECTORY_RENAMED_PATH, DIRECTORY_PAYLOAD.length)
    check(readback == DIRECTORY_PAYLOAD) { "directory lifecycle readback mismatch: $readback" }
    wasiPathUnlinkFileValue(dirFd, DIRECTORY_RENAMED_PATH)
    wasiPathRemoveDirectoryValue(dirFd, DIRECTORY_PROBE_DIR)
    return EXPECTED_DIRECTORY_REPORT
}

private fun wasiReaddirProbe(): String {
    val dirFd = wasiPreopenFd("/")
    wasiPathCreateDirectoryValue(dirFd, READDIR_PROBE_DIR)
    wasiWriteFile(dirFd, READDIR_ALPHA_PATH, "alpha")
    wasiWriteFile(dirFd, READDIR_BETA_PATH, "beta")
    val listingFd = wasiOpenFile(dirFd, READDIR_PROBE_DIR, 0, RIGHTS_FD_READDIR)
    val entries =
        try {
            wasiReadDirectoryEntries(listingFd)
        } finally {
            checkErrno(wasiFdClose(listingFd), "fd_close readdir")
        }
    wasiPathUnlinkFileValue(dirFd, READDIR_ALPHA_PATH)
    wasiPathUnlinkFileValue(dirFd, READDIR_BETA_PATH)
    wasiPathRemoveDirectoryValue(dirFd, READDIR_PROBE_DIR)

    val files = entries.filterNot { it.name == "." || it.name == ".." }.sortedBy { it.name }
    val report = "entries=${files.joinToString(",") { "${it.name}:${it.fileType}" }};count=${files.size}"
    check(report == EXPECTED_READDIR_REPORT) { "unexpected readdir report: $report from $entries" }
    return report
}

private fun wasiHttpStatus(): Int =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = HTTP_PROBE_PATH.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val responsePtr = allocator.allocate(4).address.toInt()
        storeBytes(pathPtr, pathBytes)
        hostHttpFetch(pathPtr, pathBytes.size, responsePtr, 0)
    }

private fun wasiHttpFetchBody(): HostHttpResponse =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = HTTP_PROBE_PATH.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val responsePtr = allocator.allocate(4 + HTTP_FETCH_BODY_CAPACITY).address.toInt()
        storeBytes(pathPtr, pathBytes)
        val status = hostHttpFetch(pathPtr, pathBytes.size, responsePtr, HTTP_FETCH_BODY_CAPACITY)
        val bodyLength = loadI32(responsePtr)
        check(bodyLength in 0..HTTP_FETCH_BODY_CAPACITY) { "HTTP body too large: $bodyLength" }
        HostHttpResponse(status, loadUtf8(responsePtr + 4, bodyLength))
    }

private fun wasiProductSummary(json: String): String {
    val product = ProductJson.decodeFromString<Product>(json)
    val tags = product.tags.joinToString("+")
    return "${product.id}:${product.title}:${product.category}:${product.price}:${product.rating}:$tags:${product.availabilityStatus}"
}

private suspend fun wasiCoroutineProductStreamReport(): ProductStreamResult {
    val input = decodeProductsFromStdin()
    val report =
        coroutineScope {
            val count = async { input.products.size }
            val stock = async { input.products.sumOf { it.stock } }
            val top = async { input.products.maxBy { it.rating }.title }
            val beauty = async {
                delay(1)
                input.products.count { it.category == "beauty" }
            }
            ProductStreamReport(
                count.await(),
                stock.await(),
                top.await(),
                beauty.await(),
                input.chunks,
            )
        }
    return writeProductReport(report)
}

private fun wasiProductStreamReport(): ProductStreamResult {
    val input = decodeProductsFromStdin()
    val report = productReport(input)
    return writeProductReport(report)
}

private fun decodeProductsFromStdin(): ProductStreamInput {
    val source = WasiFdSource(0)
    return ProductStreamInput(decodeProducts(source), source.chunks())
}

private fun wasiInvalidProductStreamReport(): String {
    val source = WasiFdSource(0)
    return try {
        decodeProducts(source)
        "handled=false;chunks=${source.chunks()}"
    } catch (error: IllegalArgumentException) {
        "${EXPECTED_INVALID_JSON_PREFIX}${source.chunks()}"
    }
}

private fun malformedJsonHandled(summary: String): Boolean =
    summary.startsWith(EXPECTED_INVALID_JSON_PREFIX) &&
        summary.substringAfter("chunks=").toIntOrNull()?.let { it > 0 } == true

private fun decodeProducts(source: WasiFdSource): List<Product> =
    ProductJson.decodeSourceToSequence(
            source.buffered(),
            Product.serializer(),
            DecodeSequenceMode.ARRAY_WRAPPED,
        )
        .toList()


private fun productReport(input: ProductStreamInput): ProductStreamReport {
    val top = input.products.maxBy { it.rating }
    val beautyCount = input.products.count { it.category == "beauty" }
    return ProductStreamReport(
        count = input.products.size,
        stock = input.products.sumOf { it.stock },
        top = top.title,
        beauty = beautyCount,
        chunks = input.chunks,
    )
}

private fun writeProductReport(report: ProductStreamReport): ProductStreamResult {
    val fd = wasiPreopenFd("/")
    val encoded =
        ProductJson.encodeToString(
            ProductStreamReport.serializer(),
            report,
        )
    wasiWriteFile(fd, PRODUCT_REPORT_PATH, report.summary())
    wasiWriteFile(fd, PRODUCT_REPORT_JSON_PATH, encoded)
    val decoded =
        ProductJson.decodeFromString(
            ProductStreamReport.serializer(),
            wasiReadFile(fd, PRODUCT_REPORT_JSON_PATH, encoded.length),
        )
    check(decoded == report) { "serialized report readback mismatch: $decoded != $report" }
    return ProductStreamResult(report.summary(), decoded.summary())
}

private fun ProductStreamReport.summary(): String =
    "count=$count;stock=$stock;top=$top;beauty=$beauty;chunks=$chunks"

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPreopenFd(path: String): Int =
    withScopedMemoryAllocator { allocator ->
        val prestat = allocator.allocate(8).address.toInt()
        var result = -1
        for (fd in PREOPEN_FD_START..PREOPEN_FD_END) {
            if (wasiFdPrestatGet(fd, prestat) == ERRNO_SUCCESS) {
                val nameLength = loadI32(prestat + 4)
                val namePtr = allocator.allocate(nameLength).address.toInt()
                checkErrno(wasiFdPrestatDirName(fd, namePtr, nameLength), "fd_prestat_dir_name")
                if (loadUtf8(namePtr, nameLength) == path) {
                    result = fd
                    break
                }
            }
        }
        check(result >= 0) { "preopen $path not found" }
        result
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteFile(dirFd: Int, path: String, text: String) {
    withScopedMemoryAllocator { allocator ->
        val fd = wasiOpenFile(dirFd, path, OFLAGS_CREAT or OFLAGS_TRUNC, RIGHTS_FD_WRITE)
        try {
            wasiWriteOpenFd(fd, text)
        } finally {
            checkErrno(wasiFdClose(fd), "fd_close write")
        }
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiOpenFile(dirFd: Int, path: String, openFlags: Int, rightsBase: Long): Int =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val fdPtr = allocator.allocate(4).address.toInt()

        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathOpen(
                dirFd,
                0,
                pathPtr,
                pathBytes.size,
                openFlags,
                rightsBase,
                rightsBase,
                0,
                fdPtr,
            ),
            "path_open $path",
        )
        loadI32(fdPtr)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiTryOpenFile(dirFd: Int, path: String, openFlags: Int, rightsBase: Long): Int =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val fdPtr = allocator.allocate(4).address.toInt()

        storeBytes(pathPtr, pathBytes)
        val errno =
            wasiPathOpen(
                dirFd,
                0,
                pathPtr,
                pathBytes.size,
                openFlags,
                rightsBase,
                rightsBase,
                0,
                fdPtr,
            )
        if (errno == ERRNO_SUCCESS) {
            checkErrno(wasiFdClose(loadI32(fdPtr)), "fd_close try-open $path")
        }
        errno
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteOpenFd(fd: Int, text: String) {
    withScopedMemoryAllocator { allocator ->
        val data = text.encodeToByteArray()
        val dataPtr = allocator.allocate(data.size).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val writtenPtr = allocator.allocate(4).address.toInt()
        storeBytes(dataPtr, data)
        storeI32(iov, dataPtr)
        storeI32(iov + 4, data.size)
        checkErrno(wasiFdWrite(fd, iov, 1, writtenPtr), "fd_write file")
        check(loadI32(writtenPtr) == data.size) { "short file write" }
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiTryWriteOpenFd(fd: Int, text: String): Int =
    withScopedMemoryAllocator { allocator ->
        val data = text.encodeToByteArray()
        val dataPtr = allocator.allocate(data.size).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val writtenPtr = allocator.allocate(4).address.toInt()
        storeBytes(dataPtr, data)
        storeI32(iov, dataPtr)
        storeI32(iov + 4, data.size)
        wasiFdWrite(fd, iov, 1, writtenPtr)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiReadOpenFd(fd: Int, maxLength: Int): String =
    withScopedMemoryAllocator { allocator ->
        val dataPtr = allocator.allocate(maxLength).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val readPtr = allocator.allocate(4).address.toInt()
        storeI32(iov, dataPtr)
        storeI32(iov + 4, maxLength)
        checkErrno(wasiFdRead(fd, iov, 1, readPtr), "fd_read open file")
        loadUtf8(dataPtr, loadI32(readPtr))
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiReadFile(dirFd: Int, path: String, maxLength: Int, lookupFlags: Int = 0): String =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val fdPtr = allocator.allocate(4).address.toInt()
        val dataPtr = allocator.allocate(maxLength).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val readPtr = allocator.allocate(4).address.toInt()

        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathOpen(
                dirFd,
                lookupFlags,
                pathPtr,
                pathBytes.size,
                0,
                RIGHTS_FD_READ,
                RIGHTS_FD_READ,
                0,
                fdPtr,
            ),
            "path_open read",
        )
        val fd = loadI32(fdPtr)
        storeI32(iov, dataPtr)
        storeI32(iov + 4, maxLength)
        checkErrno(wasiFdRead(fd, iov, 1, readPtr), "fd_read file")
        val read = loadI32(readPtr)
        checkErrno(wasiFdClose(fd), "fd_close read")
        loadUtf8(dataPtr, read)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPwriteOpenFd(fd: Int, text: String, offset: Long) {
    withScopedMemoryAllocator { allocator ->
        val data = text.encodeToByteArray()
        val dataPtr = allocator.allocate(data.size).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val writtenPtr = allocator.allocate(4).address.toInt()
        storeBytes(dataPtr, data)
        storeI32(iov, dataPtr)
        storeI32(iov + 4, data.size)
        checkErrno(wasiFdPwrite(fd, iov, 1, offset, writtenPtr), "fd_pwrite")
        check(loadI32(writtenPtr) == data.size) { "short fd_pwrite" }
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPreadOpenFd(fd: Int, offset: Long, maxLength: Int): String =
    withScopedMemoryAllocator { allocator ->
        val dataPtr = allocator.allocate(maxLength).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val readPtr = allocator.allocate(4).address.toInt()
        storeI32(iov, dataPtr)
        storeI32(iov + 4, maxLength)
        checkErrno(wasiFdPread(fd, iov, 1, offset, readPtr), "fd_pread")
        loadUtf8(dataPtr, loadI32(readPtr))
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiFdSeekValue(fd: Int, offset: Long, whence: Int): Long =
    withScopedMemoryAllocator { allocator ->
        val offsetPtr = allocator.allocate(8).address.toInt()
        checkErrno(wasiFdSeek(fd, offset, whence, offsetPtr), "fd_seek")
        loadI64(offsetPtr)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiFdTellValue(fd: Int): Long =
    withScopedMemoryAllocator { allocator ->
        val offsetPtr = allocator.allocate(8).address.toInt()
        checkErrno(wasiFdTell(fd, offsetPtr), "fd_tell")
        loadI64(offsetPtr)
    }

private data class WasiFdStat(val fileType: Int, val flags: Int, val rightsBase: Long)

private data class WasiFileStat(
    val fileType: Int,
    val size: Long,
    val accessTime: Long,
    val modifiedTime: Long,
    val changedTime: Long,
)

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiFdStatValue(fd: Int): WasiFdStat =
    withScopedMemoryAllocator { allocator ->
        val statPtr = allocator.allocate(24).address.toInt()
        checkErrno(wasiFdFdstatGet(fd, statPtr), "fd_fdstat_get")
        WasiFdStat(
            fileType = loadByte(statPtr).toInt() and 0xff,
            flags = loadI16(statPtr + 2).toInt() and 0xffff,
            rightsBase = loadI64(statPtr + 8),
        )
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiFdFilestatValue(fd: Int): WasiFileStat =
    withScopedMemoryAllocator { allocator ->
        val statPtr = allocator.allocate(64).address.toInt()
        checkErrno(wasiFdFilestatGet(fd, statPtr), "fd_filestat_get")
        loadWasiFileStat(statPtr)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathFilestatValue(dirFd: Int, path: String): WasiFileStat =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val statPtr = allocator.allocate(64).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathFilestatGet(dirFd, 0, pathPtr, pathBytes.size, statPtr),
            "path_filestat_get $path",
        )
        loadWasiFileStat(statPtr)
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathFilestatSetTimesValue(
    dirFd: Int,
    path: String,
    accessTime: Long,
    modifiedTime: Long,
    flags: Int,
) {
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathFilestatSetTimes(
                dirFd,
                0,
                pathPtr,
                pathBytes.size,
                accessTime,
                modifiedTime,
                flags,
            ),
            "path_filestat_set_times $path",
        )
    }
}

private fun loadWasiFileStat(statPtr: Int): WasiFileStat =
    WasiFileStat(
        fileType = loadByte(statPtr + 16).toInt() and 0xff,
        size = loadI64(statPtr + 32),
        accessTime = loadI64(statPtr + 40),
        modifiedTime = loadI64(statPtr + 48),
        changedTime = loadI64(statPtr + 56),
    )

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathCreateDirectoryValue(dirFd: Int, path: String) {
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathCreateDirectory(dirFd, pathPtr, pathBytes.size),
            "path_create_directory $path",
        )
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathRenameValue(oldFd: Int, oldPath: String, newFd: Int, newPath: String) {
    withScopedMemoryAllocator { allocator ->
        val oldPathBytes = oldPath.encodeToByteArray()
        val newPathBytes = newPath.encodeToByteArray()
        val oldPathPtr = allocator.allocate(oldPathBytes.size).address.toInt()
        val newPathPtr = allocator.allocate(newPathBytes.size).address.toInt()
        storeBytes(oldPathPtr, oldPathBytes)
        storeBytes(newPathPtr, newPathBytes)
        checkErrno(
            wasiPathRename(
                oldFd,
                oldPathPtr,
                oldPathBytes.size,
                newFd,
                newPathPtr,
                newPathBytes.size,
            ),
            "path_rename $oldPath",
        )
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathLinkValue(oldFd: Int, oldPath: String, newFd: Int, newPath: String) {
    withScopedMemoryAllocator { allocator ->
        val oldPathBytes = oldPath.encodeToByteArray()
        val newPathBytes = newPath.encodeToByteArray()
        val oldPathPtr = allocator.allocate(oldPathBytes.size).address.toInt()
        val newPathPtr = allocator.allocate(newPathBytes.size).address.toInt()
        storeBytes(oldPathPtr, oldPathBytes)
        storeBytes(newPathPtr, newPathBytes)
        checkErrno(
            wasiPathLink(
                oldFd,
                0,
                oldPathPtr,
                oldPathBytes.size,
                newFd,
                newPathPtr,
                newPathBytes.size,
            ),
            "path_link $oldPath",
        )
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathSymlinkValue(oldPath: String, dirFd: Int, newPath: String) {
    withScopedMemoryAllocator { allocator ->
        val oldPathBytes = oldPath.encodeToByteArray()
        val newPathBytes = newPath.encodeToByteArray()
        val oldPathPtr = allocator.allocate(oldPathBytes.size).address.toInt()
        val newPathPtr = allocator.allocate(newPathBytes.size).address.toInt()
        storeBytes(oldPathPtr, oldPathBytes)
        storeBytes(newPathPtr, newPathBytes)
        checkErrno(
            wasiPathSymlink(
                oldPathPtr,
                oldPathBytes.size,
                dirFd,
                newPathPtr,
                newPathBytes.size,
            ),
            "path_symlink $newPath",
        )
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathReadlinkValue(dirFd: Int, path: String, capacity: Int): String =
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        val targetPtr = allocator.allocate(capacity).address.toInt()
        val sizePtr = allocator.allocate(4).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathReadlink(
                dirFd,
                pathPtr,
                pathBytes.size,
                targetPtr,
                capacity,
                sizePtr,
            ),
            "path_readlink $path",
        )
        loadUtf8(targetPtr, loadI32(sizePtr))
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathUnlinkFileValue(dirFd: Int, path: String) {
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(wasiPathUnlinkFile(dirFd, pathPtr, pathBytes.size), "path_unlink_file $path")
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPathRemoveDirectoryValue(dirFd: Int, path: String) {
    withScopedMemoryAllocator { allocator ->
        val pathBytes = path.encodeToByteArray()
        val pathPtr = allocator.allocate(pathBytes.size).address.toInt()
        storeBytes(pathPtr, pathBytes)
        checkErrno(
            wasiPathRemoveDirectory(dirFd, pathPtr, pathBytes.size),
            "path_remove_directory $path",
        )
    }
}

private data class DirectoryEntry(val name: String, val fileType: Int)

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiReadDirectoryEntries(fd: Int): List<DirectoryEntry> =
    withScopedMemoryAllocator { allocator ->
        val bufferLength = 512
        val bufferPtr = allocator.allocate(bufferLength).address.toInt()
        val usedPtr = allocator.allocate(4).address.toInt()
        checkErrno(wasiFdReaddir(fd, bufferPtr, bufferLength, 0L, usedPtr), "fd_readdir")
        val used = loadI32(usedPtr)
        val entries = ArrayList<DirectoryEntry>()
        var cursor = 0
        while (cursor + 24 <= used) {
            val nameLength = loadI32(bufferPtr + cursor + 16)
            val fileType = loadByte(bufferPtr + cursor + 20).toInt() and 0xff
            val entryLength = 24 + nameLength
            if (nameLength < 0 || cursor + entryLength > used) break
            entries += DirectoryEntry(loadUtf8(bufferPtr + cursor + 24, nameLength), fileType)
            cursor += entryLength
        }
        entries
    }

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteStderr(text: String) {
    wasiWriteFd(2, text)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteStdout(text: String) {
    wasiWriteFd(1, text)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteFd(fd: Int, text: String) {
    withScopedMemoryAllocator { allocator ->
        val bytes = text.encodeToByteArray()
        val dataPtr = allocator.allocate(bytes.size).address.toInt()
        val iov = allocator.allocate(8).address.toInt()
        val writtenPtr = allocator.allocate(4).address.toInt()
        storeBytes(dataPtr, bytes)
        storeI32(iov, dataPtr)
        storeI32(iov + 4, bytes.size)
        checkErrno(wasiFdWrite(fd, iov, 1, writtenPtr), "fd_write $fd")
    }
}

private class WasiFdSource(private val fd: Int) : RawSource {
    private var closed = false
    private var chunks = 0

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (closed) return -1L
        if (byteCount <= 0L) return 0L
        val requested = byteCount.coerceAtMost(STREAM_READ_CHUNK_SIZE.toLong()).toInt()
        val read =
            withScopedMemoryAllocator { allocator ->
                val dataPtr = allocator.allocate(requested).address.toInt()
                val iov = allocator.allocate(8).address.toInt()
                val readPtr = allocator.allocate(4).address.toInt()
                storeI32(iov, dataPtr)
                storeI32(iov + 4, requested)
                checkErrno(wasiFdRead(fd, iov, 1, readPtr), "fd_read stream $fd")
                val count = loadI32(readPtr)
                if (count > 0) {
                    sink.write(ByteArray(count) { index -> loadByte(dataPtr + index) })
                }
                count
            }
        if (read == 0) {
            closed = true
            return -1L
        }
        chunks += 1
        return read.toLong()
    }

    override fun close() {
        closed = true
    }

    fun chunks(): Int = chunks
}

@WasmImport("wasi_snapshot_preview1", "clock_time_get")
private external fun wasiClockTimeGet(clockId: Int, precision: Long, resultPtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "clock_res_get")
private external fun wasiClockResGet(clockId: Int, resultPtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "args_sizes_get")
private external fun wasiArgsSizesGet(argc: Int, argvBufSize: Int): Int

@WasmImport("wasi_snapshot_preview1", "args_get")
private external fun wasiArgsGet(argv: Int, argvBuf: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_sizes_get")
private external fun wasiEnvironSizesGet(environCount: Int, environBufSize: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_get")
private external fun wasiEnvironGet(environ: Int, environBuf: Int): Int

@WasmImport("wasi_snapshot_preview1", "random_get")
private external fun wasiRandomGet(buf: Int, bufLen: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
private external fun wasiFdPrestatGet(fd: Int, buf: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name")
private external fun wasiFdPrestatDirName(fd: Int, path: Int, pathLen: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun wasiPathOpen(
    dirFd: Int,
    lookupFlags: Int,
    path: Int,
    pathLen: Int,
    openFlags: Int,
    rightsBase: Long,
    rightsInheriting: Long,
    fdFlags: Int,
    retFd: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_create_directory")
private external fun wasiPathCreateDirectory(dirFd: Int, path: Int, pathLen: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_rename")
private external fun wasiPathRename(
    oldFd: Int,
    oldPath: Int,
    oldPathLen: Int,
    newFd: Int,
    newPath: Int,
    newPathLen: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_link")
private external fun wasiPathLink(
    oldFd: Int,
    lookupFlags: Int,
    oldPath: Int,
    oldPathLen: Int,
    newFd: Int,
    newPath: Int,
    newPathLen: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_symlink")
private external fun wasiPathSymlink(
    oldPath: Int,
    oldPathLen: Int,
    dirFd: Int,
    newPath: Int,
    newPathLen: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_readlink")
private external fun wasiPathReadlink(
    dirFd: Int,
    path: Int,
    pathLen: Int,
    buf: Int,
    bufLen: Int,
    resultPtr: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_unlink_file")
private external fun wasiPathUnlinkFile(dirFd: Int, path: Int, pathLen: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_remove_directory")
private external fun wasiPathRemoveDirectory(dirFd: Int, path: Int, pathLen: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun wasiFdWrite(fd: Int, iovs: Int, iovsLen: Int, nwritten: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun wasiFdRead(fd: Int, iovs: Int, iovsLen: Int, nread: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_datasync")
private external fun wasiFdDatasync(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_sync")
private external fun wasiFdSync(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_fdstat_get")
private external fun wasiFdFdstatGet(fd: Int, stat: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_fdstat_set_flags")
private external fun wasiFdFdstatSetFlags(fd: Int, flags: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_fdstat_set_rights")
private external fun wasiFdFdstatSetRights(fd: Int, rightsBase: Long, rightsInheriting: Long): Int

@WasmImport("wasi_snapshot_preview1", "fd_advise")
private external fun wasiFdAdvise(fd: Int, offset: Long, length: Long, advice: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_allocate")
private external fun wasiFdAllocate(fd: Int, offset: Long, length: Long): Int

@WasmImport("wasi_snapshot_preview1", "fd_pwrite")
private external fun wasiFdPwrite(fd: Int, iovs: Int, iovsLen: Int, offset: Long, nwritten: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_pread")
private external fun wasiFdPread(fd: Int, iovs: Int, iovsLen: Int, offset: Long, nread: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_readdir")
private external fun wasiFdReaddir(fd: Int, buf: Int, bufLen: Int, cookie: Long, bufused: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_seek")
private external fun wasiFdSeek(fd: Int, offset: Long, whence: Int, newOffset: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_tell")
private external fun wasiFdTell(fd: Int, offset: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_filestat_get")
private external fun wasiFdFilestatGet(fd: Int, stat: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_filestat_set_size")
private external fun wasiFdFilestatSetSize(fd: Int, size: Long): Int

@WasmImport("wasi_snapshot_preview1", "path_filestat_get")
private external fun wasiPathFilestatGet(
    dirFd: Int,
    lookupFlags: Int,
    path: Int,
    pathLen: Int,
    stat: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "path_filestat_set_times")
private external fun wasiPathFilestatSetTimes(
    dirFd: Int,
    lookupFlags: Int,
    path: Int,
    pathLen: Int,
    accessTime: Long,
    modifiedTime: Long,
    flags: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun wasiFdClose(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "poll_oneoff")
private external fun wasiPollOneoff(input: Int, output: Int, subscriptions: Int, events: Int): Int

@WasmImport(HOST_HTTP_MODULE, "fetch")
private external fun hostHttpFetch(pathPtr: Int, pathLen: Int, responsePtr: Int, responseCapacity: Int): Int

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiClockTime(clockId: Int): Long =
    withScopedMemoryAllocator { allocator ->
        val result = allocator.allocate(8)
        val errno = wasiClockTimeGet(clockId, 1L, result.address.toInt())
        checkErrno(errno, "clock_time_get")
        Pointer(result.address.toInt().toUInt()).loadLong()
    }

private fun checkErrno(errno: Int, call: String) {
    check(errno == ERRNO_SUCCESS) { "$call failed with errno=$errno" }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun loadByte(ptr: Int): Byte = Pointer(ptr.toUInt()).loadByte()

@OptIn(UnsafeWasmMemoryApi::class)
private fun loadI32(ptr: Int): Int = Pointer(ptr.toUInt()).loadInt()

@OptIn(UnsafeWasmMemoryApi::class)
private fun loadI64(ptr: Int): Long = Pointer(ptr.toUInt()).loadLong()

@OptIn(UnsafeWasmMemoryApi::class)
private fun loadI16(ptr: Int): Short = Pointer(ptr.toUInt()).loadShort()

@OptIn(UnsafeWasmMemoryApi::class)
private fun storeByte(ptr: Int, value: Byte) {
    Pointer(ptr.toUInt()).storeByte(value)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun storeI16(ptr: Int, value: Short) {
    Pointer(ptr.toUInt()).storeShort(value)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun storeI32(ptr: Int, value: Int) {
    Pointer(ptr.toUInt()).storeInt(value)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun storeI64(ptr: Int, value: Long) {
    Pointer(ptr.toUInt()).storeLong(value)
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun storeBytes(ptr: Int, bytes: ByteArray) {
    for (index in bytes.indices) {
        Pointer((ptr + index).toUInt()).storeByte(bytes[index])
    }
}

private fun loadUtf8(ptr: Int, length: Int): String {
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        bytes[index] = loadByte(ptr + index)
    }
    return bytes.decodeToString()
}

private fun loadUtf8Z(ptr: Int, maxLength: Int): String {
    var length = 0
    while (length < maxLength && loadByte(ptr + length) != 0.toByte()) {
        length++
    }
    return loadUtf8(ptr, length)
}

@WasmExport
fun endiveSampleMarker(): Int = 240
