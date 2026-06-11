package uk.shusek.krwa.testgen

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

class TestGenConfig {
    var testSuiteRepo: String = "https://github.com/WebAssembly/testsuite"
    var testSuiteRepoRef: String = "88e97b0f742f4c3ee01fea683da130f344dd7b02"
    var testsuiteFolder: String = ""
    var sourceDestinationFolder: String = ""
    var compiledWastTargetFolder: String = ""
    var includedWasts: List<String> = emptyList()
    var excludedTests: List<String> = emptyList()
    var excludedMalformedWasts: List<String> = emptyList()
    var excludedInvalidWasts: List<String> = emptyList()
    var excludedUninstantiableWasts: List<String> = emptyList()
    var excludedUnlinkableWasts: List<String> = emptyList()
    var excludedWasts: List<String> = emptyList()
}

object TestGenCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: TestGenCli <config.json>" }

        val config = ObjectMapper().readValue(File(args[0]), TestGenConfig::class.java)
        TestGen.execute(
            config.testSuiteRepo,
            config.testSuiteRepoRef,
            File(config.testsuiteFolder),
            File(config.sourceDestinationFolder),
            File(config.compiledWastTargetFolder),
            config.includedWasts,
            config.excludedTests,
            config.excludedMalformedWasts,
            config.excludedInvalidWasts,
            config.excludedUninstantiableWasts,
            config.excludedUnlinkableWasts,
            config.excludedWasts,
        )
    }
}
