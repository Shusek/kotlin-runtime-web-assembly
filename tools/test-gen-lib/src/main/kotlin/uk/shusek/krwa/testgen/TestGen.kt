package uk.shusek.krwa.testgen

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.util.Locale
import java.util.stream.Collectors
import uk.shusek.krwa.testgen.Constants.Companion.SPEC_JSON
import uk.shusek.krwa.testgen.StringUtils.Companion.escapedCamelCase
import uk.shusek.krwa.testgen.wast.Wast
import uk.shusek.krwa.tools.wasm.Wast2Json

/** This plugin should generate the testsuite out of wast files. */
class TestGen private constructor() {
    companion object {
        @JvmStatic
        fun execute(
            testSuiteRepo: String,
            testSuiteRepoRef: String,
            testsuiteFolder: File,
            sourceDestinationFolder: File,
            compiledWastTargetFolder: File,
            includedWasts: List<String>,
            excludedTests: List<String>,
            excludedMalformedWasts: List<String>,
            excludedInvalidWasts: List<String>,
            excludedUninstantiableWasts: List<String>,
            excludedUnlinkableWasts: List<String>,
            excludedWasts: List<String>,
        ) {
            validate(includedWasts, "includedWasts", true)
            validate(excludedTests, "excludedTests", false)
            validate(excludedWasts, "excludedWasts", true)
            validate(excludedMalformedWasts, "excludedMalformedWasts", true)
            validate(excludedInvalidWasts, "excludedInvalidWasts", true)
            validate(excludedUninstantiableWasts, "excludedUninstantiableWasts", true)
            validate(excludedUnlinkableWasts, "excludedUnlinkableWasts", true)

            val testSuiteDownloader = TestSuiteDownloader()
            val testGen =
                KotlinTestGen(
                    excludedTests,
                    excludedMalformedWasts,
                    excludedInvalidWasts,
                    excludedUninstantiableWasts,
                    excludedUnlinkableWasts,
                )

            if (!compiledWastTargetFolder.isDirectory && !compiledWastTargetFolder.mkdirs()) {
                throw RuntimeException("Failed to create folder: $compiledWastTargetFolder")
            }

            if (!sourceDestinationFolder.isDirectory && !sourceDestinationFolder.mkdirs()) {
                throw RuntimeException("Failed to create folder: $sourceDestinationFolder")
            }

            try {
                testSuiteDownloader.downloadTestsuite(
                    testSuiteRepo,
                    testSuiteRepoRef,
                    testsuiteFolder,
                )

                val allWastFiles = HashSet<String>()
                try {
                    Files.newDirectoryStream(testsuiteFolder.toPath(), "*.wast").use { stream ->
                        stream.forEach { path -> allWastFiles.add(path.fileName.toString()) }
                    }
                } catch (e: IOException) {
                    throw RuntimeException("Failed to list wast files in $testsuiteFolder", e)
                }

                includedWasts.forEach(allWastFiles::remove)
                excludedMalformedWasts.forEach(allWastFiles::remove)
                excludedInvalidWasts.forEach(allWastFiles::remove)
                excludedUninstantiableWasts.forEach(allWastFiles::remove)
                excludedUnlinkableWasts.forEach(allWastFiles::remove)
                excludedWasts.forEach(allWastFiles::remove)
                if (allWastFiles.isNotEmpty()) {
                    throw RuntimeException(
                        "Some wast files are not included or excluded: $allWastFiles"
                    )
                }

                val testGenerator =
                    TestGenerator(
                        testGen,
                        sourceDestinationFolder,
                        testsuiteFolder,
                        compiledWastTargetFolder,
                    )

                includedWasts.parallelStream().forEach(testGenerator::generateTests)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        private fun validate(items: List<String>, name: String, requireSorted: Boolean) {
            val set = HashSet<String>()
            for (item in items) {
                if (!set.add(item)) {
                    throw RuntimeException("$name contains duplicate: $item")
                }
            }
            if (requireSorted) {
                val sorted = items.stream().sorted().collect(Collectors.toList())
                if (sorted != items) {
                    throw RuntimeException("$name is not sorted. Expected: $sorted")
                }
            }
        }
    }

    private class TestGenerator(
        private val testGen: KotlinTestGen,
        private val sourceDestinationFolder: File,
        private val testsuiteFolder: File,
        private val compiledWastTargetFolder: File,
    ) {
        fun generateTests(spec: String) {
            val wastFile = testsuiteFolder.toPath().resolve(spec).toFile()
            if (!wastFile.exists()) {
                throw IllegalArgumentException("Wast file ${wastFile.absolutePath} not found")
            }

            var plainName = wastFile.name.replace(".wast", "")
            if (wastFile.parentFile.parentFile.name.equals("proposals", ignoreCase = true)) {
                val proposal = escapedCamelCase(wastFile.parentFile.name)
                plainName =
                    proposal +
                        plainName.substring(0, 1).uppercase(Locale.ROOT) +
                        plainName.substring(1)
            }
            val wasmFilesFolder = compiledWastTargetFolder.toPath().resolve(plainName).toFile()
            val specFile = wasmFilesFolder.toPath().resolve(SPEC_JSON).toFile()
            if (!wasmFilesFolder.isDirectory && !wasmFilesFolder.mkdirs()) {
                throw RuntimeException("Could not create folder: $wasmFilesFolder")
            }

            Wast2Json.builder()
                .withFile(wastFile)
                .withOutput(specFile.toPath().parent.toFile())
                .build()
                .process()

            val name = specFile.toPath().parent.toFile().name
            val generated = testGen.generate(name, readWast(specFile), "/$plainName")
            val packageDir =
                sourceDestinationFolder
                    .toPath()
                    .resolve(generated.packageName.replace('.', File.separatorChar))
            try {
                Files.createDirectories(packageDir)
                Files.writeString(packageDir.resolve(generated.typeName + ".kt"), generated.source)
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }

        private fun readWast(file: File): Wast {
            try {
                return ObjectMapper().readValue(file, Wast::class.java)
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }
}
