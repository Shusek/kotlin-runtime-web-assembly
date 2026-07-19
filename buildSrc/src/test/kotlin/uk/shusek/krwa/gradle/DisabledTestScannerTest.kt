package uk.shusek.krwa.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DisabledTestScannerTest {
    @Test
    fun `reports disabled and ignored tests without tracking references`() {
        val findings =
            scan(
                """
                class ExampleTest {
                    @Disabled
                    fun disabled() = Unit

                    @Ignore("temporarily flaky")
                    fun ignored() = Unit
                }
                """,
            )

        assertEquals(listOf("@Disabled", "@Ignore"), findings.map(DisabledTestFinding::annotation))
        assertEquals(listOf(2, 5), findings.map(DisabledTestFinding::line))
    }

    @Test
    fun `accepts issue URLs numeric references and tracker IDs`() {
        val findings =
            scan(
                """
                @Disabled("Blocked by https://github.com/example/project/issues/123")
                fun withUrl() = Unit

                @Ignore("Flaky on CI; see #456")
                fun withNumber() = Unit

                @org.junit.jupiter.api.Disabled("Waiting for KRWA-789")
                fun withTrackerId() = Unit
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `reports a tracking reference without a useful reason`() {
        val findings =
            scan(
                """
                @Disabled("#123")
                fun disabled() = Unit
                """,
            )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports TestNG tests disabled without a tracked reason`() {
        val findings =
            scan(
                """
                @Test(description = "temporarily disabled", enabled = false)
                fun disabled() = Unit
                """,
            )

        assertEquals(1, findings.size)
        assertEquals("@Test(enabled = false)", findings.single().annotation)
    }

    @Test
    fun `accepts tracked TestNG disablement`() {
        val findings =
            scan(
                """
                @org.testng.annotations.Test(
                    enabled = false,
                    description = "Blocked by #42",
                )
                fun disabled() = Unit
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `accepts the tracked generated WebAssembly spec exclusion`() {
        val findings =
            scan(
                """
                @Disabled("KRWA-1: WebAssembly spec exclusion tracked in docs/testing-exclusions.md")
                @Test
                fun excludedSpecCase() = Unit
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `reports Gradle task disablement without a tracked reason`() {
        val findings =
            scanBuildScript(
                """
                tasks.named<Test>("wasmJsNodeTest") {
                    enabled = false
                }
                """,
            )

        assertEquals(listOf("enabled = false"), findings.map(DisabledTestFinding::annotation))
        assertEquals(listOf(2), findings.map(DisabledTestFinding::line))
    }

    @Test
    fun `accepts Gradle task disablement with a nearby tracked explanation`() {
        val findings =
            scanBuildScript(
                """
                // KRWA-321: Node cannot provide the required component resource imports yet.
                tasks.named<Test>("wasmJsNodeTest") {
                    enabled = false
                }
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `ignores Gradle disablement text in comments and strings`() {
        val findings =
            scanBuildScript(
                """
                // enabled = false
                val example = "enabled = false"
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `ignores annotations inside comments strings and character literals`() {
        val findings =
            scan(
                """
                // @Disabled
                /* @Ignore("no reason") */
                val annotationText = "@Disabled"
                val multiline = ${"\"\"\""}
                    @Ignore
                ${"\"\"\""}
                val annotationPrefix = '@'
                """,
            )

        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `reports the source path and multiline annotation line`() {
        val findings =
            DisabledTestScanner.scan(
                listOf(
                    TestSource(
                        "module/src/test/ExampleTest.kt",
                        """

                        @Disabled(
                            "waiting for a fix",
                        )
                        fun disabled() = Unit
                        """.trimIndent(),
                    ),
                ),
            )

        assertEquals("module/src/test/ExampleTest.kt", findings.single().path)
        assertEquals(2, findings.single().line)
    }

    private fun scan(contents: String): List<DisabledTestFinding> =
        DisabledTestScanner.scan(
            listOf(TestSource("ExampleTest.kt", contents.trimIndent())),
        )

    private fun scanBuildScript(contents: String): List<DisabledTestFinding> =
        DisabledTestScanner.scanBuildScripts(
            listOf(TestSource("build.gradle.kts", contents.trimIndent())),
        )
}
