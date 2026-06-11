package uk.shusek.krwa.testing

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.simd.SimdInterpreterMachine

object InterpreterMachineFactory {
    @JvmStatic fun create(instance: Instance): Machine = SimdInterpreterMachine(instance)
}
