package uk.shusek.krwa.runtime

import kotlin.ExperimentalMultiplatform
import kotlin.OptionalExpectation

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION)
expect annotation class RuntimeJvmStatic()
