package uk.shusek.krwa.runtime

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WasmtimeExecutionConfigTest {
    @Test
    fun wasmtimeConfigsDefaultToAutomaticPlatformTarget() {
        assertEquals(WasmtimeAutomaticTarget, WasmtimeExecutionConfig().target)
        assertEquals(
            WasmtimeAutomaticTarget,
            preview3ComponentConfig().target,
        )
    }

    @Test
    fun wasmtimeExecutionConfigCarriesResourceLimits() {
        val config = WasmtimeExecutionConfig(
            maxMemoryBytes = 32L * 1024L * 1024L,
            maxWasmStackBytes = 128L * 1024L,
            maxTableElements = 256L,
            maxInstances = 2L,
            maxTables = 4L,
            maxMemories = 8L,
            maxFuel = 1_000_000L,
        )

        assertEquals(32L * 1024L * 1024L, config.maxMemoryBytes)
        assertEquals(128L * 1024L, config.maxWasmStackBytes)
        assertEquals(256L, config.maxTableElements)
        assertEquals(2L, config.maxInstances)
        assertEquals(4L, config.maxTables)
        assertEquals(8L, config.maxMemories)
        assertEquals(1_000_000L, config.maxFuel)
    }

    @Test
    fun wasmtimeExecutionConfigRejectsInvalidResourceLimits() {
        val stackError = assertFailsWith<IllegalArgumentException> {
            WasmtimeExecutionConfig(maxWasmStackBytes = 0)
        }
        val tableError = assertFailsWith<IllegalArgumentException> {
            WasmtimeExecutionConfig(maxTableElements = -2)
        }
        val fuelError = assertFailsWith<IllegalArgumentException> {
            WasmtimeExecutionConfig(maxFuel = -2)
        }

        assertContains(stackError.message.orEmpty(), "max Wasm stack bytes must be positive")
        assertContains(tableError.message.orEmpty(), "max table elements must be")
        assertContains(fuelError.message.orEmpty(), "max fuel must be")
    }

    @Test
    fun preview3ComponentConfigRejectsEmptyComponentBytes() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(precompiledComponentBytes = byteArrayOf())
        }

        assertContains(error.message.orEmpty(), "component bytes must not be empty")
    }

    @Test
    fun preview3ComponentConfigRejectsHostFilesystemRoot() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(hostPreopenRoot = "/")
        }

        assertContains(error.message.orEmpty(), "host preopen root must not be the filesystem root")
    }

    @Test
    fun preview3ComponentConfigRejectsRelativeHostPreopenRoot() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(hostPreopenRoot = "suvio-plugin-cache/plugin")
        }

        assertContains(error.message.orEmpty(), "host preopen root must be absolute")
    }

    @Test
    fun preview3ComponentConfigRejectsHostPreopenRootWhitespaceAndSegments() {
        listOf(
            " /tmp/suvio-plugin-cache/plugin",
            "/tmp/../suvio-plugin-cache/plugin",
            "/tmp/./plugin",
        ).forEach { hostRoot ->
            val error = assertFailsWith<IllegalArgumentException> {
                preview3ComponentConfig(hostPreopenRoot = hostRoot)
            }

            assertContains(error.message.orEmpty(), "host preopen root")
        }
    }

    @Test
    fun preview3ComponentConfigAcceptsMultipleExplicitPreopens() {
        val config = WasmtimePreview3ComponentConfig(
            precompiledComponentBytes = byteArrayOf(1),
            preopens = listOf(
                WasmtimePreview3Preopen(
                    hostRoot = "/tmp/suvio-plugin-cache/plugin/cache",
                    guestRoot = "/suvio/cache",
                ),
                WasmtimePreview3Preopen(
                    hostRoot = "/tmp/suvio-plugin-cache/plugin/data",
                    guestRoot = "/suvio/data",
                    writable = false,
                ),
            ),
        )

        assertEquals(2, config.preopens.size)
        assertEquals("/suvio/cache", config.preopens[0].guestRoot)
        assertEquals(false, config.preopens[1].writable)
    }

    @Test
    fun preview3ComponentConfigCarriesArgumentsAndEnvironment() {
        val config = preview3ComponentConfig(
            arguments = listOf("plugin-id", "--warm-cache"),
            environment = mapOf(
                "SUVIO_PLUGIN_ID" to "dev.suvio.test",
                "SUVIO_CACHE_DIR" to "/suvio/cache",
            ),
        )

        assertEquals(listOf("plugin-id", "--warm-cache"), config.arguments)
        assertEquals("dev.suvio.test", config.environment["SUVIO_PLUGIN_ID"])
        assertEquals("/suvio/cache", config.environment["SUVIO_CACHE_DIR"])
    }

    @Test
    fun preview3ComponentConfigCarriesNetworkPolicy() {
        val config = preview3ComponentConfig(
            networkPolicy = WasmtimePreview3NetworkPolicy(
                httpEndpoints =
                    listOf(
                        WasmtimePreview3HttpEndpoint(
                            WasmtimePreview3HttpProtocol.Https,
                            "api.example.test",
                            443,
                        ),
                        WasmtimePreview3HttpEndpoint(
                            WasmtimePreview3HttpProtocol.Http,
                            "127.0.0.1",
                            8080,
                        ),
                    ),
            ),
        )

        assertEquals(
            listOf("https://api.example.test:443", "http://127.0.0.1:8080"),
            config.networkPolicy.encodedHttpEndpoints(),
        )
    }

    @Test
    fun preview3NetworkPolicyRejectsInvalidOrAmbiguousEndpoints() {
        listOf(
            "",
            " api.example.test",
            "https://api.example.test",
            "api.example.test/path",
            "*.example.test",
            "user@api.example.test",
            "api:port",
            "münich.example",
            "127.000.0.1",
            "2001:db8::1::2",
            "bad\u0000host",
        )
            .forEach { host ->
                val error = assertFailsWith<IllegalArgumentException> {
                    WasmtimePreview3HttpEndpoint(
                        WasmtimePreview3HttpProtocol.Https,
                        host,
                        443,
                    )
                }

                assertContains(error.message.orEmpty(), "network host")
            }
        assertFailsWith<IllegalArgumentException> {
            WasmtimePreview3HttpEndpoint(
                WasmtimePreview3HttpProtocol.Https,
                "api.example.test",
                0,
            )
        }
        val endpoint =
            WasmtimePreview3HttpEndpoint(
                WasmtimePreview3HttpProtocol.Https,
                "api.example.test",
                443,
            )
        assertFailsWith<IllegalArgumentException> {
            WasmtimePreview3NetworkPolicy(httpEndpoints = listOf(endpoint, endpoint))
        }
        assertEquals(
            "https://[2001:db8:0:0:0:0:0:1]:443",
            WasmtimePreview3NetworkPolicy(
                httpEndpoints = listOf(
                    WasmtimePreview3HttpEndpoint(
                        WasmtimePreview3HttpProtocol.Https,
                        "[2001:DB8::1]",
                        443,
                    ),
                ),
            ).encodedHttpEndpoints().single(),
        )
        assertFailsWith<IllegalArgumentException> {
            WasmtimePreview3NetworkPolicy(
                httpEndpoints = listOf(
                    WasmtimePreview3HttpEndpoint(
                        WasmtimePreview3HttpProtocol.Https,
                        "API.EXAMPLE.TEST.",
                        443,
                    ),
                    WasmtimePreview3HttpEndpoint(
                        WasmtimePreview3HttpProtocol.Https,
                        "api.example.test",
                        443,
                    ),
                ),
            )
        }
    }

    @Test
    fun preview3ComponentConfigRejectsBlankEnvironmentKey() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(environment = mapOf("" to "value"))
        }

        assertContains(error.message.orEmpty(), "environment key must not be blank")
    }

    @Test
    fun preview3ComponentConfigRejectsNulInArgumentsAndEnvironment() {
        val argumentError = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(arguments = listOf("bad\u0000arg"))
        }
        val environmentError = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(environment = mapOf("KEY" to "bad\u0000value"))
        }

        assertContains(argumentError.message.orEmpty(), "argument 0 must not contain NUL")
        assertContains(environmentError.message.orEmpty(), "environment entries must not contain NUL")
    }

    @Test
    fun preview3ComponentConfigRejectsEmptyPreopenList() {
        val error = assertFailsWith<IllegalArgumentException> {
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = byteArrayOf(1),
                preopens = emptyList(),
            )
        }

        assertContains(error.message.orEmpty(), "preopen list must not be empty")
    }

    @Test
    fun preview3ComponentConfigRejectsDuplicateGuestPreopenRoots() {
        val error = assertFailsWith<IllegalArgumentException> {
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = byteArrayOf(1),
                preopens = listOf(
                    WasmtimePreview3Preopen("/tmp/suvio-plugin-cache/plugin/cache-a", "/suvio/cache"),
                    WasmtimePreview3Preopen("/tmp/suvio-plugin-cache/plugin/cache-b", "/suvio/cache/"),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "guest preopen root must be unique")
    }

    @Test
    fun preview3ComponentConfigRejectsNonPositiveMemoryLimit() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(maxMemoryBytes = 0)
        }

        assertContains(error.message.orEmpty(), "max memory bytes must be positive")
    }

    @Test
    fun preview3ComponentConfigCarriesExecutionTimeout() {
        val config = preview3ComponentConfig(executionTimeoutMillis = 12_345L)

        assertEquals(12_345L, config.executionTimeoutMillis)
    }

    @Test
    fun preview3ComponentConfigCarriesResourceLimits() {
        val config = preview3ComponentConfig(
            maxWasmStackBytes = 256L * 1024L,
            maxTableElements = 512L,
            maxInstances = 16L,
            maxTables = 32L,
            maxMemories = 64L,
            maxFuel = 5_000_000L,
        )

        assertEquals(256L * 1024L, config.maxWasmStackBytes)
        assertEquals(512L, config.maxTableElements)
        assertEquals(16L, config.maxInstances)
        assertEquals(32L, config.maxTables)
        assertEquals(64L, config.maxMemories)
        assertEquals(5_000_000L, config.maxFuel)
    }

    @Test
    fun preview3ComponentConfigRejectsInvalidResourceLimits() {
        val stackError = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(maxWasmStackBytes = 0)
        }
        val memoryCountError = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(maxMemories = -2)
        }
        val fuelError = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(maxFuel = -2)
        }

        assertContains(stackError.message.orEmpty(), "max Wasm stack bytes must be positive")
        assertContains(memoryCountError.message.orEmpty(), "Preview3 max memories must be")
        assertContains(fuelError.message.orEmpty(), "Preview3 max fuel must be")
    }

    @Test
    fun preview3ComponentConfigRejectsNegativeExecutionTimeout() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(executionTimeoutMillis = -1)
        }

        assertContains(error.message.orEmpty(), "execution timeout millis must not be negative")
    }

    @Test
    fun preview3ComponentConfigRejectsRelativeGuestPreopenRoot() {
        val error = assertFailsWith<IllegalArgumentException> {
            preview3ComponentConfig(guestPreopenRoot = "suvio")
        }

        assertContains(error.message.orEmpty(), "guest preopen root must be absolute")
    }

    @Test
    fun preview3ComponentConfigRejectsGuestParentSegments() {
        listOf("/../suvio", "/./suvio", "\\suvio\\cache").forEach { guestRoot ->
            val error = assertFailsWith<IllegalArgumentException> {
                preview3ComponentConfig(guestPreopenRoot = guestRoot)
            }

            assertContains(error.message.orEmpty(), "guest preopen root")
        }
    }

    private fun preview3ComponentConfig(
        precompiledComponentBytes: ByteArray = byteArrayOf(1),
        hostPreopenRoot: String = "/tmp/suvio-plugin-cache/plugin",
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
    ): WasmtimePreview3ComponentConfig = WasmtimePreview3ComponentConfig(
        precompiledComponentBytes = precompiledComponentBytes,
        hostPreopenRoot = hostPreopenRoot,
        guestPreopenRoot = guestPreopenRoot,
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
}
