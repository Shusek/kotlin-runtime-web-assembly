package uk.shusek.krwa.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WitParserLimitsTest {
    @Test
    fun exposesFiniteDefaultLimits() {
        val limits = WitParserLimits()

        assertEquals(4 * 1024 * 1024, limits.maxSourceChars)
        assertEquals(500_000, limits.maxTokens)
        assertEquals(50_000, limits.maxDeclarations)
        assertEquals(10_000, limits.maxMembersPerDeclaration)
        assertEquals(128, limits.maxTypeNesting)
        assertEquals(128, limits.maxPackageNesting)
        assertEquals(128, limits.maxIncludeDepth)
        assertEquals(100_000, limits.maxExpandedWorldItems)
    }

    @Test
    fun enforcesSourceCharacterBoundaryBeforeTokenization() {
        val source = "interface api {}"
        WitPackage.parse(source, DEFAULT_LIMITS.copy(maxSourceChars = source.length))

        assertLimit("maxSourceChars", source.length - 1L, source.length.toLong()) {
            WitPackage.parse(source, DEFAULT_LIMITS.copy(maxSourceChars = source.length - 1))
        }
    }

    @Test
    fun enforcesTokenBoundaryBeforeAddingTheNextToken() {
        val source = "interface api {}"
        WitPackage.parse(source, DEFAULT_LIMITS.copy(maxTokens = 4))

        assertLimit("maxTokens", 3, 4) {
            WitPackage.parse(source, DEFAULT_LIMITS.copy(maxTokens = 3))
        }
    }

    @Test
    fun enforcesDeclarationAndMemberBoundaries() {
        val emptyInterface = "interface api {}"
        WitPackage.parse(emptyInterface, DEFAULT_LIMITS.copy(maxDeclarations = 1))
        assertLimit("maxDeclarations", 0, 1) {
            WitPackage.parse(emptyInterface, DEFAULT_LIMITS.copy(maxDeclarations = 0))
        }

        val oneMember = "interface api { run: func(); }"
        WitPackage.parse(oneMember, DEFAULT_LIMITS.copy(maxMembersPerDeclaration = 1))
        assertLimit("maxMembersPerDeclaration", 0, 1) {
            WitPackage.parse(oneMember, DEFAULT_LIMITS.copy(maxMembersPerDeclaration = 0))
        }
    }

    @Test
    fun enforcesTypeNestingBoundaryBeforeRecursing() {
        val source = "type value = option<string>;"
        WitPackage.parse(source, DEFAULT_LIMITS.copy(maxTypeNesting = 2))

        assertLimit("maxTypeNesting", 1, 2) {
            WitPackage.parse(source, DEFAULT_LIMITS.copy(maxTypeNesting = 1))
        }
    }

    @Test
    fun enforcesPackageNestingBoundaryBeforeRecursing() {
        val source = nestedPackages(depth = 3)
        WitPackage.parse(source, DEFAULT_LIMITS.copy(maxPackageNesting = 3))

        assertLimit("maxPackageNesting", 2, 3) {
            WitPackage.parse(source, DEFAULT_LIMITS.copy(maxPackageNesting = 2))
        }
    }

    @Test
    fun enforcesDeepIncludeBoundaryAndAllowsTheExactLimit() {
        WitPackage.parse(
            worldIncludeChain(edgeCount = 128),
            DEFAULT_LIMITS.copy(maxIncludeDepth = 128),
        )

        assertLimit("maxIncludeDepth", 128, 129) {
            WitPackage.parse(
                worldIncludeChain(edgeCount = 129),
                DEFAULT_LIMITS.copy(maxIncludeDepth = 128),
            )
        }
    }

    @Test
    fun rejectsCyclicIncludesWithoutUnboundedRecursion() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                WitPackage.parse(
                    """
                    world a {
                      include b;
                    }
                    world b {
                      include a;
                    }
                    """
                        .trimIndent()
                )
            }

        assertFalse(failure is WitParseLimitException)
        assertTrue(failure.message!!.contains("cyclic WIT world include"))
    }

    @Test
    fun enforcesTheAggregateExpandedWorldItemBoundary() {
        val source =
            """
            world base {
              import input;
            }
            world derived {
              include base;
            }
            """
                .trimIndent()
        WitPackage.parse(source, DEFAULT_LIMITS.copy(maxExpandedWorldItems = 2))

        assertLimit("maxExpandedWorldItems", 1, 2) {
            WitPackage.parse(source, DEFAULT_LIMITS.copy(maxExpandedWorldItems = 1))
        }
    }

    private fun worldIncludeChain(edgeCount: Int): String =
        buildString {
            for (index in 0..edgeCount) {
                append("world w").append(index).append(" {")
                if (index < edgeCount) {
                    append(" include w").append(index + 1).append(';')
                }
                append(" }\n")
            }
        }

    private fun nestedPackages(depth: Int): String =
        buildString {
            repeat(depth) { index ->
                append("package p").append(index).append(" {")
            }
            append("interface api {}")
            repeat(depth) {
                append('}')
            }
        }

    private fun assertLimit(
        name: String,
        configured: Long,
        actual: Long,
        block: () -> Unit,
    ) {
        val failure = assertThrows(WitParseLimitException::class.java, block)
        assertEquals(name, failure.limitName)
        assertEquals(configured, failure.configuredLimit)
        assertEquals(actual, failure.actual)
    }

    private companion object {
        val DEFAULT_LIMITS = WitParserLimits()
    }
}
