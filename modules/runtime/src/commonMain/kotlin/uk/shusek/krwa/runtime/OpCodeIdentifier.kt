package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.OpCode

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class OpCodeIdentifier(val value: OpCode)
