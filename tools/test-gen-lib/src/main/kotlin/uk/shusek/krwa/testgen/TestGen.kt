package uk.shusek.krwa.testgen

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
            excludedRuntimeTests: List<String> = emptyList(),
            excludedRuntimeWasts: List<String> = emptyList(),
        ) {
            require(
                testSuiteRepo == DEFAULT_WASM_TEST_SUITE_REPOSITORY &&
                    testSuiteRepoRef == DEFAULT_WASM_TEST_SUITE_REVISION
            ) {
                "WebAssembly testsuite downloads must use the repository's pinned revision and SHA-256"
            }
            executePrepared(
                testSuiteRepo = testSuiteRepo,
                testSuiteRepoRef = testSuiteRepoRef,
                archiveSha256 = DEFAULT_WASM_TEST_SUITE_ARCHIVE_SHA256,
                testsuiteFolder = testsuiteFolder,
                sourceDestinationFolder = sourceDestinationFolder,
                compiledWastTargetFolder = compiledWastTargetFolder,
                includedWasts = includedWasts,
                excludedTests = excludedTests,
                excludedRuntimeTests = excludedRuntimeTests,
                excludedMalformedWasts = excludedMalformedWasts,
                excludedInvalidWasts = excludedInvalidWasts,
                excludedUninstantiableWasts = excludedUninstantiableWasts,
                excludedUnlinkableWasts = excludedUnlinkableWasts,
                excludedWasts = excludedWasts,
                excludedRuntimeWasts = excludedRuntimeWasts,
                offline = false,
            )
        }

        internal fun executePrepared(
            testSuiteRepo: String,
            testSuiteRepoRef: String,
            archiveSha256: String,
            testsuiteFolder: File,
            sourceDestinationFolder: File,
            compiledWastTargetFolder: File,
            includedWasts: List<String>,
            excludedTests: List<String>,
            excludedRuntimeTests: List<String>,
            excludedMalformedWasts: List<String>,
            excludedInvalidWasts: List<String>,
            excludedUninstantiableWasts: List<String>,
            excludedUnlinkableWasts: List<String>,
            excludedWasts: List<String>,
            excludedRuntimeWasts: List<String>,
            offline: Boolean,
        ) {
            validate(includedWasts, "includedWasts", true)
            validate(excludedTests, "excludedTests", false)
            validate(excludedRuntimeTests, "excludedRuntimeTests", false)
            validate(excludedWasts, "excludedWasts", true)
            validate(excludedRuntimeWasts, "excludedRuntimeWasts", true)
            validate(excludedMalformedWasts, "excludedMalformedWasts", true)
            validate(excludedInvalidWasts, "excludedInvalidWasts", true)
            validate(excludedUninstantiableWasts, "excludedUninstantiableWasts", true)
            validate(excludedUnlinkableWasts, "excludedUnlinkableWasts", true)
            validateDisjoint(
                excludedTests,
                "excludedTests",
                excludedRuntimeTests,
                "excludedRuntimeTests",
            )
            validateDisjoint(
                excludedWasts,
                "excludedWasts",
                excludedRuntimeWasts,
                "excludedRuntimeWasts",
            )

            val testGen =
                KotlinTestGen(
                    excludedTests,
                    excludedMalformedWasts,
                    excludedInvalidWasts,
                    excludedUninstantiableWasts,
                    excludedUnlinkableWasts,
                    excludedRuntimeTests,
                )

            if (!compiledWastTargetFolder.isDirectory && !compiledWastTargetFolder.mkdirs()) {
                throw RuntimeException("Failed to create folder: $compiledWastTargetFolder")
            }

            if (!sourceDestinationFolder.isDirectory && !sourceDestinationFolder.mkdirs()) {
                throw RuntimeException("Failed to create folder: $sourceDestinationFolder")
            }

            try {
                prepareWasmTestsuite(
                    testSuiteRepo = testSuiteRepo,
                    testSuiteRepoRef = testSuiteRepoRef,
                    archiveSha256 = archiveSha256,
                    testSuiteFolder = testsuiteFolder,
                    offline = offline,
                    forceExtract = false,
                )

                validateWastClassification(
                    testsuiteFolder = testsuiteFolder,
                    includedWasts = includedWasts,
                    excludedMalformedWasts = excludedMalformedWasts,
                    excludedInvalidWasts = excludedInvalidWasts,
                    excludedUninstantiableWasts = excludedUninstantiableWasts,
                    excludedUnlinkableWasts = excludedUnlinkableWasts,
                    excludedWasts = excludedWasts,
                    excludedRuntimeWasts = excludedRuntimeWasts,
                )

                val testGenerator =
                    TestGenerator(
                        testGen,
                        sourceDestinationFolder,
                        testsuiteFolder,
                        compiledWastTargetFolder,
                )

                includedWasts.parallelStream().forEach(testGenerator::generateTests)
                testGen.validateExcludedTestsMatched()
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

        private fun validateDisjoint(
            first: List<String>,
            firstName: String,
            second: List<String>,
            secondName: String,
        ) {
            val overlap = (first.toSet() intersect second.toSet()).sorted()
            check(overlap.isEmpty()) {
                "$firstName and $secondName must be disjoint: $overlap"
            }
        }
    }

    private class TestGenerator(
        private val testGen: KotlinTestGen,
        private val sourceDestinationFolder: File,
        private val testsuiteFolder: File,
        private val compiledWastTargetFolder: File,
    ) {
        private val generatedTypes = ConcurrentHashMap.newKeySet<String>()

        fun generateTests(spec: String) {
            val normalizedSpec = spec.replace('\\', '/')
            val wastFile = testsuiteFolder.toPath().resolve(normalizedSpec).toFile()
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
            val generated =
                testGen.generate(
                    name = name,
                    spec = normalizedSpec,
                    wast = readWast(specFile),
                    wasmClasspath = "/$plainName",
                )
            val qualifiedTypeName = "${generated.packageName}.${generated.typeName}"
            check(generatedTypes.add(qualifiedTypeName)) {
                "Multiple WebAssembly wast files generate the same test type: $qualifiedTypeName"
            }
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

internal fun validateWastClassification(
    testsuiteFolder: File,
    includedWasts: List<String>,
    excludedMalformedWasts: List<String>,
    excludedInvalidWasts: List<String>,
    excludedUninstantiableWasts: List<String>,
    excludedUnlinkableWasts: List<String>,
    excludedWasts: List<String>,
    excludedRuntimeWasts: List<String>,
) {
    val suiteWasts =
        try {
            Files.walk(testsuiteFolder.toPath()).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".wast") }
                    .map { path ->
                        testsuiteFolder
                            .toPath()
                            .relativize(path)
                            .joinToString("/")
                    }
                    .collect(Collectors.toCollection(::LinkedHashSet))
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to list wast files recursively in $testsuiteFolder", e)
        }
    val included = includedWasts.toSet()
    val parserExcluded = excludedWasts.toSet()
    val runtimeExcluded = excludedRuntimeWasts.toSet()
    val overlappingExclusions = (parserExcluded intersect runtimeExcluded).sorted()
    check(overlappingExclusions.isEmpty()) {
        "excludedWasts and excludedRuntimeWasts must be disjoint: $overlappingExclusions"
    }
    val fullyExcluded = parserExcluded + runtimeExcluded
    val conflictingClassifications = (included intersect fullyExcluded).sorted()
    check(conflictingClassifications.isEmpty()) {
        "WebAssembly wast files cannot be both included and fully excluded: " +
            conflictingClassifications
    }

    val typedModifiers =
        mapOf(
            "excludedMalformedWasts" to excludedMalformedWasts,
            "excludedInvalidWasts" to excludedInvalidWasts,
            "excludedUninstantiableWasts" to excludedUninstantiableWasts,
            "excludedUnlinkableWasts" to excludedUnlinkableWasts,
        )
    typedModifiers.forEach { (name, entries) ->
        val outsideIncluded = (entries.toSet() - included).sorted()
        check(outsideIncluded.isEmpty()) {
            "$name entries must also be present in includedWasts: $outsideIncluded"
        }
    }

    val classifiedWasts = included + fullyExcluded
    val unclassified = (suiteWasts - classifiedWasts).sorted()
    val stale = (classifiedWasts - suiteWasts).sorted()
    check(unclassified.isEmpty() && stale.isEmpty()) {
        buildString {
            append("WebAssembly wast classification must exactly match the pinned testsuite.")
            if (unclassified.isNotEmpty()) {
                append(" Unclassified suite files: ")
                append(unclassified)
                append('.')
            }
            if (stale.isNotEmpty()) {
                append(" Configured files missing from the suite: ")
                append(stale)
                append('.')
            }
        }
    }
}
