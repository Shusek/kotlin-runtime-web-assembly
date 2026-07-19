package uk.shusek.krwa.testgen

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.testgen.wast.Command
import uk.shusek.krwa.testgen.wast.CommandType
import uk.shusek.krwa.testgen.wast.Wast

class KotlinTestGenTest {
    @Test
    fun staleTestExclusionIsRejected() {
        val generator =
            KotlinTestGen(
                excludedTests = listOf("SpecV1RemovedTest.test7"),
                excludedMalformedWasts = emptyList(),
                excludedInvalidWasts = emptyList(),
                excludedUninstantiableWasts = emptyList(),
                excludedUnlinkableWasts = emptyList(),
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                generator.validateExcludedTestsMatched()
            }

        assertTrue(error.message.orEmpty().contains("SpecV1RemovedTest.test7"))
    }

    @Test
    fun staleRuntimeTestExclusionIsReportedSeparately() {
        val generator =
            KotlinTestGen(
                excludedTests = emptyList(),
                excludedMalformedWasts = emptyList(),
                excludedInvalidWasts = emptyList(),
                excludedUninstantiableWasts = emptyList(),
                excludedUnlinkableWasts = emptyList(),
                excludedRuntimeTests = listOf("SpecV1RemovedRuntimeTest.test3"),
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                generator.validateExcludedTestsMatched()
            }

        assertTrue(error.message.orEmpty().contains("excludedRuntimeTests"))
        assertTrue(error.message.orEmpty().contains("SpecV1RemovedRuntimeTest.test3"))
    }

    @Test
    fun parserAndRuntimeMethodExclusionsMustBeDisjoint() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                KotlinTestGen(
                    excludedTests = listOf("SpecV1AddressTest.test1"),
                    excludedMalformedWasts = emptyList(),
                    excludedInvalidWasts = emptyList(),
                    excludedUninstantiableWasts = emptyList(),
                    excludedUnlinkableWasts = emptyList(),
                    excludedRuntimeTests = listOf("SpecV1AddressTest.test1"),
                )
            }

        assertTrue(error.message.orEmpty().contains("must be disjoint"))
        assertTrue(error.message.orEmpty().contains("SpecV1AddressTest.test1"))
    }

    @Test
    fun typedExclusionsUseTheOriginalProposalPathAndDisableOnlyMatchingAssertions() {
        val spec = "proposals/gc/binary.wast"
        val generator =
            KotlinTestGen(
                excludedTests = emptyList(),
                excludedMalformedWasts = listOf(spec),
                excludedInvalidWasts = listOf(spec),
                excludedUninstantiableWasts = listOf(spec),
                excludedUnlinkableWasts = listOf(spec),
            )
        val commands =
            arrayOf(
                command(CommandType.MODULE, "spec.0.wasm", 1),
                command(CommandType.ASSERT_MALFORMED, "spec.1.wasm", 2),
                command(CommandType.ASSERT_INVALID, "spec.2.wasm", 3),
                command(CommandType.ASSERT_UNINSTANTIABLE, "spec.3.wasm", 4),
                command(CommandType.ASSERT_UNLINKABLE, "spec.4.wasm", 5),
            )
        val wast =
            object : Wast() {
                override fun sourceFilename(): File = File("spec.wast")

                override fun commands(): Array<Command> = commands
            }

        val generated =
            generator.generate(
                name = "GcBinary",
                spec = spec,
                wast = wast,
                wasmClasspath = "/GcBinary",
            )

        assertTrue(generated.source.contains(".withTypeValidation(false)"))
        assertEquals(4, generated.source.lineSequence().count { "@Disabled(" in it })
        assertTrue(generated.source.contains("fun instantiate_testModule0Instance()"))
    }

    private fun command(
        commandType: CommandType,
        filename: String,
        line: Int,
    ): Command =
        object : Command() {
            override fun type(): CommandType = commandType

            override fun filename(): String = filename

            override fun line(): Int = line
        }
}
