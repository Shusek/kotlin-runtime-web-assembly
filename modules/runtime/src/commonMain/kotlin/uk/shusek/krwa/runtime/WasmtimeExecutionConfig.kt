package uk.shusek.krwa.runtime

/** Selects the safest supported Wasmtime target for the current platform. */
const val WasmtimeAutomaticTarget: String = "auto"

const val WasmtimeNativeTarget: String = "native"

const val WasmtimePulley32Target: String = "pulley32"

const val WasmtimePulleyTarget: String = "pulley64"

const val DefaultWasmtimeMaxMemoryBytes: Long = 256L * 1024L * 1024L

const val DefaultWasmtimeMaxWasmStackBytes: Long = 512L * 1024L

const val WasmtimeUnlimitedResourceLimit: Long = -1L

const val DefaultWasmtimeCoreMaxInstances: Long = 1L

const val DefaultWasmtimeCoreMaxTables: Long = 128L

const val DefaultWasmtimeCoreMaxMemories: Long = 16L

data class WasmtimeExecutionConfig(
    val target: String = WasmtimeAutomaticTarget,
    val precompiledModuleBytes: ByteArray? = null,
    val maxMemoryBytes: Long = DefaultWasmtimeMaxMemoryBytes,
    val maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
    val maxTableElements: Long = WasmtimeUnlimitedResourceLimit,
    val maxInstances: Long = DefaultWasmtimeCoreMaxInstances,
    val maxTables: Long = DefaultWasmtimeCoreMaxTables,
    val maxMemories: Long = DefaultWasmtimeCoreMaxMemories,
    val maxFuel: Long = WasmtimeUnlimitedResourceLimit,
) {
    init {
        require(maxMemoryBytes > 0) { "Wasmtime max memory bytes must be positive" }
        require(maxWasmStackBytes > 0) { "Wasmtime max Wasm stack bytes must be positive" }
        validateWasmtimeResourceLimit("max table elements", maxTableElements)
        validateWasmtimeResourceLimit("max instances", maxInstances)
        validateWasmtimeResourceLimit("max tables", maxTables)
        validateWasmtimeResourceLimit("max memories", maxMemories)
        validateWasmtimeResourceLimit("max fuel", maxFuel)
    }
}

data class WasmtimePreview3Preopen(
    val hostRoot: String,
    val guestRoot: String = "/",
    val writable: Boolean = true,
) {
    init {
        val trimmedHostRoot = hostRoot.trim()
        require(hostRoot.isNotBlank()) {
            "Wasmtime Preview3 host preopen root must not be blank"
        }
        require(hostRoot == trimmedHostRoot) {
            "Wasmtime Preview3 host preopen root must not contain surrounding whitespace"
        }
        require(hostRoot.isAbsoluteHostPreopenPath()) {
            "Wasmtime Preview3 host preopen root must be absolute"
        }
        require(!trimmedHostRoot.isHostFilesystemRoot()) {
            "Wasmtime Preview3 host preopen root must not be the filesystem root"
        }
        require(trimmedHostRoot.hostPathSegments().none { segment -> segment == "." || segment == ".." }) {
            "Wasmtime Preview3 host preopen root must not contain current or parent segments"
        }
        val trimmedGuestRoot = guestRoot.trim()
        require(guestRoot.isNotBlank()) {
            "Wasmtime Preview3 guest preopen root must not be blank"
        }
        require(guestRoot == trimmedGuestRoot) {
            "Wasmtime Preview3 guest preopen root must not contain surrounding whitespace"
        }
        require(guestRoot.startsWith('/')) {
            "Wasmtime Preview3 guest preopen root must be absolute"
        }
        require('\\' !in guestRoot) {
            "Wasmtime Preview3 guest preopen root must use forward slashes"
        }
        require(guestRoot.pathSegments().none { segment -> segment == "." || segment == ".." }) {
            "Wasmtime Preview3 guest preopen root must not contain current or parent segments"
        }
    }
}

enum class WasmtimePreview3HttpProtocol {
    Http,
    Https,
}

data class WasmtimePreview3HttpEndpoint(
    val protocol: WasmtimePreview3HttpProtocol,
    val host: String,
    val port: Int,
) {
    val normalizedHost: String = canonicalizeExactNetworkHost(host)

    init {
        require(port in 1..65_535) {
            "Wasmtime Preview3 HTTP endpoint port must be between 1 and 65535"
        }
    }

    internal fun encoded(): String {
        val authorityHost = if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost
        val scheme =
            when (protocol) {
                WasmtimePreview3HttpProtocol.Http -> "http"
                WasmtimePreview3HttpProtocol.Https -> "https"
            }
        return "$scheme://$authorityHost:$port"
    }
}

data class WasmtimePreview3NetworkPolicy(
    val httpEndpoints: List<WasmtimePreview3HttpEndpoint> = emptyList(),
) {
    init {
        require(httpEndpoints.map(WasmtimePreview3HttpEndpoint::encoded).distinct().size == httpEndpoints.size) {
            "Wasmtime Preview3 HTTP endpoints must be unique"
        }
    }

    fun encodedHttpEndpoints(): List<String> =
        httpEndpoints.map(WasmtimePreview3HttpEndpoint::encoded)
}

private fun String.isAbsoluteHostPreopenPath(): Boolean {
    val path = trim()
    if (path.startsWith('/')) return true
    if (path.startsWith("\\\\")) return true
    return path.length >= WindowsDriveAbsolutePathLength &&
        path[1] == ':' &&
        (path[2] == '\\' || path[2] == '/')
}

private fun String.isHostFilesystemRoot(): Boolean {
    val normalized = replace('\\', '/')
    return normalized == "/" ||
        normalized == "//" ||
        WindowsDriveRootRegex.matches(normalized)
}

private fun String.hostPathSegments(): List<String> = replace('\\', '/').pathSegments()

private fun String.pathSegments(): List<String> = split('/').filter(String::isNotBlank)

data class WasmtimePreview3ComponentConfig(
    val target: String = WasmtimeAutomaticTarget,
    val precompiledComponentBytes: ByteArray,
    val preopens: List<WasmtimePreview3Preopen>,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val networkPolicy: WasmtimePreview3NetworkPolicy = WasmtimePreview3NetworkPolicy(),
    val maxMemoryBytes: Long = DefaultWasmtimeMaxMemoryBytes,
    val executionTimeoutMillis: Long = 0,
    val maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
    val maxTableElements: Long = WasmtimeUnlimitedResourceLimit,
    val maxInstances: Long = WasmtimeUnlimitedResourceLimit,
    val maxTables: Long = WasmtimeUnlimitedResourceLimit,
    val maxMemories: Long = WasmtimeUnlimitedResourceLimit,
    val maxFuel: Long = WasmtimeUnlimitedResourceLimit,
) {
    constructor(
        target: String = WasmtimeAutomaticTarget,
        precompiledComponentBytes: ByteArray,
        hostPreopenRoot: String,
        guestPreopenRoot: String = "/",
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        networkPolicy: WasmtimePreview3NetworkPolicy = WasmtimePreview3NetworkPolicy(),
        maxMemoryBytes: Long = DefaultWasmtimeMaxMemoryBytes,
        executionTimeoutMillis: Long = 0,
        maxWasmStackBytes: Long = DefaultWasmtimeMaxWasmStackBytes,
        maxTableElements: Long = WasmtimeUnlimitedResourceLimit,
        maxInstances: Long = WasmtimeUnlimitedResourceLimit,
        maxTables: Long = WasmtimeUnlimitedResourceLimit,
        maxMemories: Long = WasmtimeUnlimitedResourceLimit,
        maxFuel: Long = WasmtimeUnlimitedResourceLimit,
    ) : this(
        target = target,
        precompiledComponentBytes = precompiledComponentBytes,
        preopens = listOf(
            WasmtimePreview3Preopen(
                hostRoot = hostPreopenRoot,
                guestRoot = guestPreopenRoot,
            ),
        ),
        arguments = arguments,
        environment = environment,
        networkPolicy = networkPolicy,
        maxMemoryBytes = maxMemoryBytes,
        executionTimeoutMillis = executionTimeoutMillis,
        maxWasmStackBytes = maxWasmStackBytes,
        maxTableElements = maxTableElements,
        maxInstances = maxInstances,
        maxTables = maxTables,
        maxMemories = maxMemories,
        maxFuel = maxFuel,
    )

    val hostPreopenRoot: String
        get() = singlePreopen().hostRoot

    val guestPreopenRoot: String
        get() = singlePreopen().guestRoot

    init {
        require(precompiledComponentBytes.isNotEmpty()) {
            "Wasmtime Preview3 component bytes must not be empty"
        }
        require(maxMemoryBytes > 0) {
            "Wasmtime Preview3 max memory bytes must be positive"
        }
        require(executionTimeoutMillis >= 0) {
            "Wasmtime Preview3 execution timeout millis must not be negative"
        }
        require(maxWasmStackBytes > 0) {
            "Wasmtime Preview3 max Wasm stack bytes must be positive"
        }
        validateWasmtimeResourceLimit("Preview3 max table elements", maxTableElements)
        validateWasmtimeResourceLimit("Preview3 max instances", maxInstances)
        validateWasmtimeResourceLimit("Preview3 max tables", maxTables)
        validateWasmtimeResourceLimit("Preview3 max memories", maxMemories)
        validateWasmtimeResourceLimit("Preview3 max fuel", maxFuel)
        require(preopens.isNotEmpty()) {
            "Wasmtime Preview3 preopen list must not be empty"
        }
        val duplicateGuestRoot = preopens
            .groupingBy { preopen -> preopen.guestRoot.normalizedGuestRoot() }
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.key
        require(duplicateGuestRoot == null) {
            "Wasmtime Preview3 guest preopen root must be unique: $duplicateGuestRoot"
        }
        arguments.forEachIndexed { index, argument ->
            require(!argument.contains('\u0000')) {
                "Wasmtime Preview3 argument $index must not contain NUL"
            }
        }
        environment.forEach { (key, value) ->
            require(key.isNotBlank()) {
                "Wasmtime Preview3 environment key must not be blank"
            }
            require(!key.contains('\u0000') && !value.contains('\u0000')) {
                "Wasmtime Preview3 environment entries must not contain NUL"
            }
        }
    }

    private fun singlePreopen(): WasmtimePreview3Preopen {
        require(preopens.size == 1) {
            "Wasmtime Preview3 component config has ${preopens.size} preopens"
        }
        return preopens.single()
    }
}

private const val WindowsDriveAbsolutePathLength = 3
private val WindowsDriveRootRegex = Regex("^[A-Za-z]:/?$")

private fun String.normalizedGuestRoot(): String = trimEnd('/').ifEmpty { "/" }

private fun validateWasmtimeResourceLimit(name: String, value: Long) {
    require(value >= WasmtimeUnlimitedResourceLimit) {
        "Wasmtime $name must be $WasmtimeUnlimitedResourceLimit for unlimited or non-negative"
    }
}

expect fun wasmtimeTargetUnavailableReason(target: String): String?

expect fun wasmtimePreview3ComponentUnavailableReason(config: WasmtimePreview3ComponentConfig): String?

expect fun wasmtimePreview3ComponentCall0UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
): String?

expect fun wasmtimePreview3ComponentCallS32UnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: Int,
    expectedResult: Int,
): String?

expect fun wasmtimePreview3ComponentCallStringUnavailableReason(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
    expectedResult: String,
): String?

expect fun wasmtimePreview3ComponentCallString(
    config: WasmtimePreview3ComponentConfig,
    exportName: String,
    argument: String,
): String

expect fun wasmtimePreview3CommandRunUnavailableReason(config: WasmtimePreview3ComponentConfig): String?

expect fun installWasmtimePulleyExecutionProviderIfAvailable()
