package uk.shusek.krwa.wasm

/** Resource limits applied while decoding an untrusted WebAssembly binary. */
data class WasmParserLimits(
    val maxModuleBytes: Long = 64L * 1024 * 1024,
    val maxSectionBytes: Int = 32 * 1024 * 1024,
    val maxCustomSectionBytes: Int = 4 * 1024 * 1024,
    val maxNameBytes: Int = 64 * 1024,
    val maxVectorElements: Int = 1_000_000,
    val maxTypes: Int = 100_000,
    val maxRecGroupTypes: Int = 100_000,
    val maxSupertypes: Int = 1,
    val maxStructFields: Int = 10_000,
    val maxImports: Int = 10_000,
    val maxFunctions: Int = 100_000,
    val maxTables: Int = 1_024,
    val maxMemories: Int = 16,
    val maxGlobals: Int = 100_000,
    val maxExports: Int = 100_000,
    val maxElementSegments: Int = 100_000,
    val maxDataSegments: Int = 100_000,
    val maxTags: Int = 10_000,
    val maxFunctionParams: Int = 1_000,
    val maxFunctionResults: Int = 1_000,
    val maxFunctionBytes: Int = 8 * 1024 * 1024,
    val maxFunctionLocals: Int = 50_000,
    val maxInstructionsPerFunction: Int = 1_000_000,
    val maxControlDepth: Int = 1_024,
) {
    init {
        require(maxModuleBytes in 0..Int.MAX_VALUE.toLong()) {
            "maxModuleBytes must be between 0 and ${Int.MAX_VALUE}"
        }
        requireNonNegative("maxSectionBytes", maxSectionBytes)
        requireNonNegative("maxCustomSectionBytes", maxCustomSectionBytes)
        requireNonNegative("maxNameBytes", maxNameBytes)
        requireNonNegative("maxVectorElements", maxVectorElements)
        requireNonNegative("maxTypes", maxTypes)
        requireNonNegative("maxRecGroupTypes", maxRecGroupTypes)
        requireNonNegative("maxSupertypes", maxSupertypes)
        requireNonNegative("maxStructFields", maxStructFields)
        requireNonNegative("maxImports", maxImports)
        requireNonNegative("maxFunctions", maxFunctions)
        requireNonNegative("maxTables", maxTables)
        requireNonNegative("maxMemories", maxMemories)
        requireNonNegative("maxGlobals", maxGlobals)
        requireNonNegative("maxExports", maxExports)
        requireNonNegative("maxElementSegments", maxElementSegments)
        requireNonNegative("maxDataSegments", maxDataSegments)
        requireNonNegative("maxTags", maxTags)
        requireNonNegative("maxFunctionParams", maxFunctionParams)
        requireNonNegative("maxFunctionResults", maxFunctionResults)
        requireNonNegative("maxFunctionBytes", maxFunctionBytes)
        requireNonNegative("maxFunctionLocals", maxFunctionLocals)
        requireNonNegative("maxInstructionsPerFunction", maxInstructionsPerFunction)
        requireNonNegative("maxControlDepth", maxControlDepth)
    }

    private fun requireNonNegative(name: String, value: Int) {
        require(value >= 0) { "$name must be non-negative" }
    }
}
