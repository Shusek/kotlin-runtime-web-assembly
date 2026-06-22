import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

apply(plugin = "org.jetbrains.kotlin.kapt")

dependencies {
    add("implementation", libs.jmhCore)
    add("implementation", libs.chasmJvm)
    add("implementation", krwa("compiler"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wabt"))
    add("implementation", krwa("wasm"))
    add("implementation", krwa("wasm-corpus"))
    add("kapt", libs.jmhGeneratorAnnprocess)
}

fun JavaExec.forwardCoremarkSystemProperties() {
    System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("krwa.coremark.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
}

fun JavaExec.defaultCoremarkSystemProperty(name: String, value: String) {
    if (System.getProperty(name) == null) {
        systemProperty(name, value)
    }
}

fun coremarkJmhArgument(name: String, defaultValue: String): String =
    System.getProperty(name) ?: defaultValue

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs JMH benchmarks."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("org.openjdk.jmh.Main")
    classpath = mainSourceSet().runtimeClasspath
}

tasks.register<JavaExec>("coremarkKrwa") {
    group = "benchmark"
    description = "Runs Chasm's CoreMark wasm benchmark on selected backends."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    System.getProperty("krwa.coremark.jfr")?.takeIf { it.isNotBlank() }?.let { recording ->
        jvmArgs("-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true")
    }
}

tasks.register<JavaExec>("coremarkChasmInterpreterReport") {
    group = "benchmark"
    description = "Runs KRWA interpreter and upstream Chasm JVM interpreter on the same CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter,chasm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
    defaultCoremarkSystemProperty("krwa.coremark.interleave", "false")
}

tasks.register<JavaExec>("coremarkChasmBackendReport") {
    group = "benchmark"
    description = "Runs only the JVM Chasm execution backend on the CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
}

tasks.register<JavaExec>("coremarkChasmDirectReport") {
    group = "benchmark"
    description = "Runs direct Chasm embedding on the CoreMark wasm fixture without KRWA runtime adapters."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
}

tasks.register<JavaExec>("coremarkChasmAdapterDirectReport") {
    group = "benchmark"
    description = "Compares the KRWA Chasm backend adapter against direct Chasm embedding."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter,chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
}

tasks.register<JavaExec>("coremarkChasmAdapterDirectFairReport") {
    group = "benchmark"
    description = "Compares the KRWA Chasm backend adapter against direct Chasm with rotated interleaving."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter,chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "4")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
    defaultCoremarkSystemProperty("krwa.coremark.interleave", "true")
    defaultCoremarkSystemProperty("krwa.coremark.rotateInterleave", "true")
    defaultCoremarkSystemProperty("krwa.coremark.referenceBackend", "chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "false")
}

tasks.register<JavaExec>("coremarkChasmAdapterDirectJmhReport") {
    group = "benchmark"
    description = "Measures KRWA's Chasm adapter and direct Chasm embedding with JMH wall-clock timing."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("org.openjdk.jmh.Main")
    classpath = mainSourceSet().runtimeClasspath
    args(
        "uk.shusek.krwa.bench.BenchmarkChasmCoremarkExecution.coremark",
        "-p",
        "backendName=CHASM_INTERPRETER,CHASM_DIRECT",
        "-wi",
        coremarkJmhArgument("krwa.coremark.jmh.warmups", "1"),
        "-i",
        coremarkJmhArgument("krwa.coremark.jmh.measurements", "2"),
        "-f",
        coremarkJmhArgument("krwa.coremark.jmh.forks", "1"),
    )
}

tasks.register<JavaExec>("coremarkChasmAdapterDirectWallClockGate") {
    group = "verification"
    description = "Fails if the KRWA Chasm backend adapter is slower than direct Chasm by median wall-clock time."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter,chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "4")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
    defaultCoremarkSystemProperty("krwa.coremark.interleave", "true")
    defaultCoremarkSystemProperty("krwa.coremark.rotateInterleave", "true")
    defaultCoremarkSystemProperty("krwa.coremark.referenceBackend", "chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "ms_p50")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMinRatio", "1.0")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}

tasks.register<JavaExec>("coremarkChasmAdapterDirectGate") {
    group = "verification"
    description = "Fails if the KRWA Chasm backend adapter is below direct Chasm embedding in the same CoreMark run."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter,chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
    defaultCoremarkSystemProperty("krwa.coremark.referenceBackend", "chasm_direct")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMinRatio", "1.0")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}

tasks.register<JavaExec>("coremarkChasmBackendGate") {
    group = "verification"
    description = "Fails if the JVM Chasm execution backend is below the measured upstream Chasm interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "chasm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.printRuns", "true")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}

tasks.register<JavaExec>("coremarkChasmGate") {
    group = "verification"
    description = "Legacy compiled-backend sanity gate against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "compiled")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "0")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "1")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}

tasks.register<JavaExec>("coremarkChasmReport") {
    group = "benchmark"
    description = "Legacy mixed KRWA interpreter/compiled CoreMark report against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter,compiled")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "0")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "1")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "false")
}

tasks.register<JavaExec>("coremarkInterpreterChasmReport") {
    group = "benchmark"
    description = "Prints KRWA interpreter CoreMark score against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "0")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "1")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "false")
}

tasks.register<JavaExec>("coremarkInterpreterChasmStableReport") {
    group = "benchmark"
    description = "Runs a less noisy KRWA interpreter-only CoreMark report against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "false")
}

tasks.register<JavaExec>("coremarkInterpreterChasmGate") {
    group = "verification"
    description = "Fails if KRWA interpreter CoreMark does not beat the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm_interpreter")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}

tasks.register<JavaExec>("coremarkOpcodeReport") {
    group = "benchmark"
    description = "Prints static opcode and adjacent-pattern counts for Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkOpcodeReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkDynamicOpcodeReport") {
    group = "benchmark"
    description = "Profiles dynamic opcode and adjacent-pattern counts while running Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkDynamicOpcodeReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkFunctionCallReport") {
    group = "benchmark"
    description = "Profiles dynamic function call targets for Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkFunctionCallReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkLoweredOpcodeReport") {
    group = "benchmark"
    description = "Prints static lowered-dispatch opcode and adjacent-pattern counts for Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkLoweredOpcodeReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkLoweredFunctionReport") {
    group = "benchmark"
    description = "Prints per-function lowered-dispatch counts for Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkLoweredFunctionReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkFrameSlotPlanReport") {
    group = "benchmark"
    description = "Prints a Chasm-style frame-slot lowering plan for Chasm's CoreMark wasm fixture."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkFrameSlotPlanReportKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkSlotPlanSelfCheck") {
    group = "verification"
    description = "Compares the CoreMark slot-plan probe against the standard interpreter with deterministic inputs."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkSlotPlanSelfCheckKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkCompiledClassDump") {
    group = "benchmark"
    description = "Dumps generated compiled CoreMark classes for bytecode inspection."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkCompiledClassDumpKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
}

tasks.register<JavaExec>("coremarkChasmStableReport") {
    group = "benchmark"
    description = "Legacy mixed KRWA interpreter/compiled CoreMark report against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "interpreter,compiled")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.interleave", "true")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "false")
}

tasks.register<JavaExec>("coremarkChasmStableGate") {
    group = "verification"
    description = "Legacy compiled-backend sanity gate against the measured upstream Chasm JVM interpreter score."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("uk.shusek.krwa.bench.CoremarkRunnerKt")
    classpath = mainSourceSet().runtimeClasspath
    forwardCoremarkSystemProperties()
    defaultCoremarkSystemProperty("krwa.coremark.backends", "compiled")
    defaultCoremarkSystemProperty("krwa.coremark.warmups", "1")
    defaultCoremarkSystemProperty("krwa.coremark.repetitions", "3")
    defaultCoremarkSystemProperty("krwa.coremark.referenceName", "chasm_upstream_jvm")
    defaultCoremarkSystemProperty("krwa.coremark.referenceScore", "337.83783")
    defaultCoremarkSystemProperty("krwa.coremark.referenceMetric", "p50")
    defaultCoremarkSystemProperty("krwa.coremark.failBelowReference", "true")
}
