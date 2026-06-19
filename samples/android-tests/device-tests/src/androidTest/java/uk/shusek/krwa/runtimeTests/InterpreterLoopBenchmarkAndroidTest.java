package uk.shusek.krwa.runtimeTests;

import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import uk.shusek.krwa.runtime.ExperimentalFastInterpreterMachineKt;
import uk.shusek.krwa.runtime.Instance;
import uk.shusek.krwa.wasm.WasmParser;

public final class InterpreterLoopBenchmarkAndroidTest {
    private static final String TAG = "KRWA-BENCH";

    @Test
    public void benchmarksInterpreterLoop() {
        int iterations = configuredInt("krwaRuntimeBenchmarkIterations", 500_000);
        int repetitions = configuredInt("krwaRuntimeBenchmarkRepetitions", 5);
        int warmups = configuredInt("krwaRuntimeBenchmarkWarmups", 2);

        log(run(iterations, repetitions, warmups, Backend.STANDARD));
        log(run(iterations, repetitions, warmups, Backend.EXPERIMENTAL_FAST));
    }

    private static Result run(int iterations, int repetitions, int warmups, Backend backend) {
        Instance.Builder builder = Instance.builder(WasmParser.Companion.parse(LOOP_WASM));
        if (backend == Backend.EXPERIMENTAL_FAST) {
            ExperimentalFastInterpreterMachineKt.withExperimentalFastInterpreter(builder);
        }
        Instance instance = builder.build();
        var run = instance.export("run");
        int expected = triangularI32(iterations);

        for (int i = 0; i < warmups; i++) {
            Assertions.assertEquals(expected, (int) run.apply((long) iterations)[0]);
        }

        long checksum = 0;
        long started = System.nanoTime();
        for (int i = 0; i < repetitions; i++) {
            int result = (int) run.apply((long) iterations)[0];
            Assertions.assertEquals(expected, result);
            checksum += result;
        }
        long elapsedNs = System.nanoTime() - started;
        return new Result(backend, iterations, repetitions, elapsedNs, checksum);
    }

    private static int triangularI32(int n) {
        return (int) ((long) n * ((long) n + 1L) / 2L);
    }

    private static int configuredInt(String key, int defaultValue) {
        String value = InstrumentationRegistry.getArguments().getString(key);
        if (value == null || value.isEmpty()) return defaultValue;
        return Integer.parseInt(value);
    }

    private static void log(Result result) {
        long totalIterations = (long) result.iterations * (long) result.repetitions;
        long nsPerIteration = totalIterations == 0 ? 0 : result.elapsedNs / totalIterations;
        String line =
            "KRWA interpreter loop benchmark: platform=android" +
                ", backend=" + result.backend.label +
                ", iterations=" + result.iterations +
                ", repetitions=" + result.repetitions +
                ", elapsedMs=" + (result.elapsedNs / 1_000_000L) +
                ", nsPerIteration=" + nsPerIteration +
                ", checksum=" + result.checksum;
        System.out.println(line);
        Log.i(TAG, line);
    }

    private enum Backend {
        STANDARD("standard"),
        EXPERIMENTAL_FAST("experimentalFast");

        final String label;

        Backend(String label) {
            this.label = label;
        }
    }

    private static final class Result {
        final Backend backend;
        final int iterations;
        final int repetitions;
        final long elapsedNs;
        final long checksum;

        Result(Backend backend, int iterations, int repetitions, long elapsedNs, long checksum) {
            this.backend = backend;
            this.iterations = iterations;
            this.repetitions = repetitions;
            this.elapsedNs = elapsedNs;
            this.checksum = checksum;
        }
    }

    private static final byte[] LOOP_WASM = new byte[] {
        0x00, 0x61, 0x73, 0x6D,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x06,
        0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
        0x03, 0x02,
        0x01, 0x00,
        0x05, 0x03,
        0x01, 0x00, 0x01,
        0x07, 0x07,
        0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
        0x0A, 0x1F,
        0x01,
        0x1D, 0x01, 0x01, 0x7F,
        0x41, 0x00,
        0x21, 0x01,
        0x03, 0x40,
        0x20, 0x01,
        0x20, 0x00,
        0x6A,
        0x21, 0x01,
        0x20, 0x00,
        0x41, 0x01,
        0x6B,
        0x22, 0x00,
        0x0D, 0x00,
        0x0B,
        0x20, 0x01,
        0x0B,
    };
}
