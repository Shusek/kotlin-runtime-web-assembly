package uk.shusek.krwa.gradle

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WasmSpecTestGenConfigTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun parserAndRuntimeExclusionsRemainSeparateInConfigAndJson() {
        val directory = temporaryDirectory.toFile()
        directory.resolve("included-wasts.txt").writeText("address.wast\n")
        directory.resolve("excluded-tests.txt").writeText("SpecV1AddressTest.test1\n")
        directory
            .resolve("excluded-runtime-tests.txt")
            .writeText("SpecV1AddressTest.test2\n")
        directory.resolve("excluded-wasts.txt").writeText("obsolete-keywords.wast\n")
        directory
            .resolve("excluded-runtime-wasts.txt")
            .writeText("proposals/threads/atomic.wast\n")

        val config = directory.readWasmSpecTestGenConfig()
        val json =
            wasmSpecTestGenJson(
                config = config,
                testsuiteFolder = directory.resolve("suite"),
                sourceDestinationFolder = directory.resolve("sources"),
                compiledWastTargetFolder = directory.resolve("compiled"),
                offline = true,
            )

        assertEquals(listOf("SpecV1AddressTest.test1"), config.excludedTests)
        assertEquals(listOf("SpecV1AddressTest.test2"), config.excludedRuntimeTests)
        assertEquals(listOf("obsolete-keywords.wast"), config.excludedWasts)
        assertEquals(
            listOf("proposals/threads/atomic.wast"),
            config.excludedRuntimeWasts,
        )
        assertTrue(
            json.contains("\"excludedRuntimeTests\": [\"SpecV1AddressTest.test2\"]"),
        )
        assertTrue(
            json.contains(
                "\"excludedRuntimeWasts\": [\"proposals/threads/atomic.wast\"]",
            ),
        )
    }
}
