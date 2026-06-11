package uk.shusek.krwa.testing

import java.util.function.Function
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine

internal fun Instance.Builder.withMachineFactory(
    machineFactory: Function<Instance, Machine>
): Instance.Builder = withMachineFactory { instance -> machineFactory.apply(instance) }
