package uk.shusek.krwa.component

import kotlin.ExperimentalMultiplatform
import kotlin.OptionalExpectation

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION)
expect annotation class ComponentModelJvmStatic()

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.CONSTRUCTOR)
expect annotation class ComponentModelJvmOverloads()

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.TYPE)
expect annotation class ComponentModelJvmSuppressWildcards()
