package uk.shusek.krwa.bench

import java.util.Locale
import java.util.zip.CRC32
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val module = ChasmCoremark.loadModule()
    val cases = directCases()
    var failures = 0

    println("CoreMark slot-plan self-check")
    println("direct_function_cases=${cases.size}")
    for (case in cases) {
        val standard = runDirect(module, CoremarkBackend.INTERPRETER, case)
        val slotPlan = runDirect(module, CoremarkBackend.SLOT_PLAN_PROBE, case)
        val matches = standard == slotPlan
        if (!matches) failures++
        println(
            String.format(
                Locale.US,
                "case=%s match=%s interpreter_result=%s slot_plan_result=%s interpreter_mem_crc=%08x slot_plan_mem_crc=%08x",
                case.name,
                matches,
                standard.result.contentToString(),
                slotPlan.result.contentToString(),
                standard.memoryCrc,
                slotPlan.memoryCrc,
            )
        )
    }

    val clockStepMs = longProperty("krwa.coremark.selfcheck.clockStepMs", 10_000L).coerceAtLeast(1L)
    val standardRun = runExport(module, CoremarkBackend.INTERPRETER, clockStepMs)
    val slotPlanRun = runExport(module, CoremarkBackend.SLOT_PLAN_PROBE, clockStepMs)
    val runMatches = standardRun == slotPlanRun
    if (!runMatches) failures++
    println(
        String.format(
            Locale.US,
            "case=export_run clock_step_ms=%d match=%s interpreter_result=%s slot_plan_result=%s interpreter_mem_crc=%08x slot_plan_mem_crc=%08x",
            clockStepMs,
            runMatches,
            standardRun.result.contentToString(),
            slotPlanRun.result.contentToString(),
            standardRun.memoryCrc,
            slotPlanRun.memoryCrc,
        )
    )

    if (failures > 0) {
        error("CoreMark slot-plan self-check failed: $failures mismatches")
    }
}

private fun directCases(): List<DirectCase> =
    listOf(
        DirectCase("func2_forward_seed", 2, longArrayOf(432, 1)),
        DirectCase("func2_reverse_seed", 2, longArrayOf(432, -1)),
        DirectCase("func10_crc_a", 10, longArrayOf(49_034, 36_740)),
        DirectCase("func10_crc_b", 10, longArrayOf(32_822, 29_700)),
    )

private fun runDirect(module: WasmModule, backend: CoremarkBackend, case: DirectCase): CheckResult {
    val instance = newSelfCheckInstance(module, backend, clockStepMs = 1_000L)
    val result = instance.getMachine().call(case.funcId, case.args)
    return CheckResult(result, memoryCrc(instance))
}

private fun runExport(module: WasmModule, backend: CoremarkBackend, clockStepMs: Long): CheckResult {
    val instance = newSelfCheckInstance(module, backend, clockStepMs)
    val result = instance.export("run").apply()
    return CheckResult(result, memoryCrc(instance))
}

private fun newSelfCheckInstance(
    module: WasmModule,
    backend: CoremarkBackend,
    clockStepMs: Long,
): Instance {
    var now = 0L
    val clock =
        HostFunction(
            "env",
            "clock_ms",
            FunctionType.returning(ValType.I64),
        ) { _, _ ->
            now += clockStepMs
            longArrayOf(now)
        }

    val builder =
        Instance.builder(module)
            .withImportValues(ImportValues.builder().addFunction(clock).build())

    when (backend) {
        CoremarkBackend.INTERPRETER ->
            builder.withMachineFactory { instance -> standardMachine(instance) }
        CoremarkBackend.SLOT_PLAN_PROBE ->
            builder.withMachineFactory(::SlotPlanProbeMachine)
        else -> error("Unsupported self-check backend: $backend")
    }

    return builder.build()
}

private fun standardMachine(instance: Instance): Machine =
    object : InterpreterMachine(instance) {
        override fun isInterrupted(): Boolean = false
    }

private fun memoryCrc(instance: Instance): Long {
    val memory = instance.memory(0)
    val crc = CRC32()
    val byteCount = Memory.bytes(memory.pages())
    if (byteCount > 0) {
        crc.update(memory.readBytes(0, byteCount))
    }
    return crc.value
}

private fun longProperty(name: String, defaultValue: Long): Long =
    System.getProperty(name)?.toLongOrNull() ?: defaultValue

private data class DirectCase(
    val name: String,
    val funcId: Int,
    val args: LongArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DirectCase &&
            name == other.name &&
            funcId == other.funcId &&
            args.contentEquals(other.args)

    override fun hashCode(): Int =
        ((name.hashCode() * 31) + funcId) * 31 + args.contentHashCode()
}

private data class CheckResult(
    val result: LongArray,
    val memoryCrc: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CheckResult &&
            result.contentEquals(other.result) &&
            memoryCrc == other.memoryCrc

    override fun hashCode(): Int = 31 * result.contentHashCode() + memoryCrc.hashCode()
}
