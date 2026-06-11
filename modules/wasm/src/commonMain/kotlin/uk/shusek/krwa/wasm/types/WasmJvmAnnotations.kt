package uk.shusek.krwa.wasm.types

import kotlin.ExperimentalMultiplatform
import kotlin.OptionalExpectation

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
expect annotation class WasmJvmStatic()
