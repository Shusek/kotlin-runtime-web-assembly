package uk.shusek.krwa.testgen

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

class TestGenConfig {
    var testSuiteRepo: String = DEFAULT_WASM_TEST_SUITE_REPOSITORY
    var testSuiteRepoRef: String = DEFAULT_WASM_TEST_SUITE_REVISION
    var testsuiteFolder: String = ""
    var sourceDestinationFolder: String = ""
    var compiledWastTargetFolder: String = ""
    var includedWasts: List<String> = emptyList()
    var excludedTests: List<String> = emptyList()
    var excludedRuntimeTests: List<String> = emptyList()
    var excludedMalformedWasts: List<String> = emptyList()
    var excludedInvalidWasts: List<String> = emptyList()
    var excludedUninstantiableWasts: List<String> = emptyList()
    var excludedUnlinkableWasts: List<String> = emptyList()
    var excludedWasts: List<String> = emptyList()
    var excludedRuntimeWasts: List<String> = emptyList()
}

object TestGenCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: TestGenCli <config.json>" }

        val preparation = readWasmTestPreparationConfig(File(args[0]))
        val config = preparation.config
        TestGen.executePrepared(
            config.testSuiteRepo,
            config.testSuiteRepoRef,
            preparation.archiveSha256,
            File(config.testsuiteFolder),
            File(config.sourceDestinationFolder),
            File(config.compiledWastTargetFolder),
            config.includedWasts,
            config.excludedTests,
            config.excludedRuntimeTests,
            config.excludedMalformedWasts,
            config.excludedInvalidWasts,
            config.excludedUninstantiableWasts,
            config.excludedUnlinkableWasts,
            config.excludedWasts,
            config.excludedRuntimeWasts,
            preparation.offline,
        )
    }
}

internal object TestSuitePrepareCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) {
            "Usage: TestSuitePrepareCli <repository> <revision> <sha256> <folder> <offline>"
        }
        val offline =
            args[4].toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("offline must be true or false")
        prepareWasmTestsuite(
            testSuiteRepo = args[0],
            testSuiteRepoRef = args[1],
            archiveSha256 = args[2],
            testSuiteFolder = File(args[3]),
            offline = offline,
            forceExtract = true,
        )
    }
}

private data class WasmTestPreparationConfig(
    val config: TestGenConfig,
    val archiveSha256: String,
    val offline: Boolean,
)

private fun readWasmTestPreparationConfig(file: File): WasmTestPreparationConfig {
    val mapper =
        ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val tree = mapper.readTree(file)
    val config = mapper.treeToValue(tree, TestGenConfig::class.java)
    val archiveSha256 =
        tree.path("testSuiteArchiveSha256").asText(DEFAULT_WASM_TEST_SUITE_ARCHIVE_SHA256)
    require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) {
        "testSuiteArchiveSha256 must be a lowercase SHA-256 value"
    }
    return WasmTestPreparationConfig(
        config = config,
        archiveSha256 = archiveSha256,
        offline = tree.path("offline").asBoolean(false),
    )
}
