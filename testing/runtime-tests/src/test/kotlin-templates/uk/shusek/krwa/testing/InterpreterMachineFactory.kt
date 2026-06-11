package uk.shusek.krwa.testing

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine

object InterpreterMachineFactory {
    @JvmStatic fun create(instance: Instance): InterpreterMachine = InterpreterMachine(instance)
}
