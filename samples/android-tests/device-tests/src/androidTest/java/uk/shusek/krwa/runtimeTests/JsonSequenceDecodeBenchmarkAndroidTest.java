package uk.shusek.krwa.runtimeTests;

import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import uk.shusek.krwa.runtime.HostFunction;
import uk.shusek.krwa.runtime.ImportFunction;
import uk.shusek.krwa.runtime.ImportValues;
import uk.shusek.krwa.runtime.Instance;
import uk.shusek.krwa.runtime.WasmFunctionHandle;
import uk.shusek.krwa.wasi.WasiPreview1;
import uk.shusek.krwa.wasm.WasmParser;
import uk.shusek.krwa.wasm.types.FunctionType;
import uk.shusek.krwa.wasm.types.ValType;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public final class JsonSequenceDecodeBenchmarkAndroidTest {
    private static final String TAG = "KRWA-BENCH";

    @Test
    @Order(1)
    public void benchmarksJsonSequenceGuestSource512KiB() throws Exception {
        runGuestSourceBenchmark(512 * 1024, 1, 3);
    }

    @Test
    @Order(2)
    public void benchmarksJsonSequenceGuestSource2MiB() throws Exception {
        runGuestSourceBenchmark(2 * 1024 * 1024, 1, 3);
    }

    @Test
    @Order(3)
    public void benchmarksRawPublicScanGuestBytes2MiB() throws Exception {
        runGuestExportBenchmark(
            2 * 1024 * 1024,
            1,
            3,
            "raw-public-byte-scan",
            "run_scan_public_guest_bytes",
            "scan"
        );
    }

    @Test
    @Order(4)
    public void benchmarksConfiguredJsonSequenceGuestSource() throws Exception {
        int targetBytes = configuredInt("krwaJsonSequenceBytes", 0);
        Assumptions.assumeTrue(
            targetBytes > 0,
            "Enable with -e krwaJsonSequenceBytes=<bytes>"
        );
        int warmups = configuredInt("krwaJsonSequenceWarmups", 0);
        int repetitions = Math.max(1, configuredInt("krwaJsonSequenceRepetitions", 1));
        String mode = InstrumentationRegistry.getArguments()
            .getString("krwaJsonSequenceMode", "guest-source");
        switch (mode) {
            case "guest-source":
                runGuestSourceBenchmark(targetBytes, warmups, repetitions);
                break;
            case "host-stream":
                runHostStreamBenchmark(targetBytes, warmups, repetitions);
                break;
            case "raw-scan":
                runGuestExportBenchmark(
                    targetBytes,
                    warmups,
                    repetitions,
                    "raw-public-byte-scan",
                    "run_scan_public_guest_bytes",
                    "scan"
                );
                break;
            default:
                throw new IllegalArgumentException("Unsupported krwaJsonSequenceMode=" + mode);
        }
    }

    private void runGuestSourceBenchmark(int targetBytes, int warmups, int repetitions)
        throws Exception {
        runGuestExportBenchmark(
            targetBytes,
            warmups,
            repetitions,
            "guest-bytearray-source",
            "run_decode_filter_guest_source",
            "decode"
        );
    }

    private void runHostStreamBenchmark(int targetBytes, int warmups, int repetitions)
        throws Exception {
        JsonPayload payload = generatedCatalogJsonPayload(targetBytes);
        JsonStreamHost host = new JsonStreamHost(payload.bytes);
        Instance instance = newJsonSequenceInstance(host);

        for (int i = 0; i < warmups; i++) {
            assertHostStreamDecodeResult(instance, host, payload);
        }

        long[] hostNanos = new long[repetitions];
        long[] guestNanos = new long[repetitions];
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long started = System.nanoTime();
            assertHostStreamDecodeResult(instance, host, payload);
            hostNanos[repetition] = System.nanoTime() - started;
            guestNanos[repetition] = host.reportedElapsedNanos;
        }

        logBenchmarkLine(
            "host-stream",
            payload,
            "decode",
            hostNanos,
            guestNanos
        );
    }

    private void runGuestExportBenchmark(
        int targetBytes,
        int warmups,
        int repetitions,
        String mode,
        String exportName,
        String metricPrefix
    )
        throws Exception {
        JsonPayload payload = generatedCatalogJsonPayload(targetBytes);
        JsonStreamHost host = new JsonStreamHost(payload.bytes);
        Instance instance = newJsonSequenceInstance(host);

        for (int i = 0; i < warmups; i++) {
            assertGuestExportResult(instance, host, payload, targetBytes, exportName);
        }

        long[] hostNanos = new long[repetitions];
        long[] guestNanos = new long[repetitions];
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long started = System.nanoTime();
            assertGuestExportResult(instance, host, payload, targetBytes, exportName);
            hostNanos[repetition] = System.nanoTime() - started;
            guestNanos[repetition] = host.reportedElapsedNanos;
        }

        logBenchmarkLine(mode, payload, metricPrefix, hostNanos, guestNanos);
    }

    private static void logBenchmarkLine(
        String mode,
        JsonPayload payload,
        String metricPrefix,
        long[] hostNanos,
        long[] guestNanos
    ) {
        String line =
            "KRWA Kotlin/Wasm JSON sequence: mode=" + mode + ", platform=android" +
                ", buildType=" + benchmarkBuildType() +
                ", bytes=" + payload.bytes.length +
                ", items=" + payload.itemCount +
                ", public=" + payload.publicCount +
                ", " + metricPrefix + "GuestMs=" + (min(guestNanos) / 1_000_000L) +
                ", " + metricPrefix + "HostMs=" + (min(hostNanos) / 1_000_000L) +
                formatSamples(metricPrefix + "HostMsSamples", hostNanos) +
                formatSamples(metricPrefix + "GuestMsSamples", guestNanos) +
                formatAverage(metricPrefix + "HostMsAvg", hostNanos) +
                formatAverage(metricPrefix + "GuestMsAvg", guestNanos);
        System.out.println(line);
        Log.i(TAG, line);
    }

    private static String benchmarkBuildType() {
        return InstrumentationRegistry.getArguments()
            .getString("krwaBenchmarkBuildType", "unknown");
    }

    private static int configuredInt(String key, int defaultValue) {
        String value = InstrumentationRegistry.getArguments().getString(key);
        if (value == null || value.isEmpty()) return defaultValue;
        return Integer.parseInt(value);
    }

    private static Instance newJsonSequenceInstance(JsonStreamHost host) throws Exception {
        ArrayList<ImportFunction> functions = new ArrayList<>();
        for (HostFunction function : WasiPreview1.builder().build().toHostFunctions()) {
            functions.add(function);
        }
        for (HostFunction function : host.functions()) {
            functions.add(function);
        }
        return Instance.builder(WasmParser.Companion.parse(readWasmAsset()))
            .withImportValues(ImportValues.Companion.builder().withFunctions(functions).build())
            .build();
    }

    private static long assertGuestExportResult(
        Instance instance,
        JsonStreamHost host,
        JsonPayload payload,
        int targetBytes,
        String exportName
    ) {
        long publicCount = instance.export(exportName).apply((long) targetBytes)[0];
        Assertions.assertEquals(payload.publicCount, publicCount);
        Assertions.assertEquals(payload.itemCount, host.reportedPrimaryValue);
        Assertions.assertEquals(payload.publicCount, host.reportedPublicCount);
        return publicCount;
    }

    private static long assertHostStreamDecodeResult(
        Instance instance,
        JsonStreamHost host,
        JsonPayload payload
    ) {
        long publicCount = instance.export("run_decode_filter").apply()[0];
        Assertions.assertEquals(payload.publicCount, publicCount);
        Assertions.assertEquals(payload.itemCount, host.reportedPrimaryValue);
        Assertions.assertEquals(payload.publicCount, host.reportedPublicCount);
        return publicCount;
    }

    private static byte[] readWasmAsset() throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("krwa-json-sequence-guest.wasm")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            while (true) {
                int read = input.read(buffer);
                if (read == -1) break;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class JsonStreamHost {
        private final byte[] bytes;
        private int offset;
        int readCalls;
        long reportedPrimaryValue = -1L;
        long reportedPublicCount = -1L;
        long reportedElapsedNanos = -1L;

        JsonStreamHost(byte[] bytes) {
            this.bytes = bytes;
        }

        HostFunction[] functions() {
            return new HostFunction[] {
                new HostFunction(
                    "bench",
                    "read",
                    FunctionType.of(
                        new ValType[] { ValType.getI32(), ValType.getI32() },
                        new ValType[] { ValType.getI32() }
                    ),
                    (WasmFunctionHandle) (instance, args) -> {
                        readCalls += 1;
                        if (offset >= bytes.length) {
                            return new long[] { -1L };
                        }
                        int ptr = (int) args[0];
                        int len = (int) args[1];
                        int count = Math.min(len, bytes.length - offset);
                        instance.memory().write(ptr, bytes, offset, count);
                        offset += count;
                        return new long[] { count };
                    }
                ),
                new HostFunction(
                    "bench",
                    "reset",
                    FunctionType.empty(),
                    (WasmFunctionHandle) (instance, args) -> {
                        offset = 0;
                        readCalls = 0;
                        reportedPrimaryValue = -1L;
                        reportedPublicCount = -1L;
                        reportedElapsedNanos = -1L;
                        return null;
                    }
                ),
                new HostFunction(
                    "bench",
                    "now-nanos",
                    FunctionType.of(new ValType[] {}, new ValType[] { ValType.getI64() }),
                    (WasmFunctionHandle) (instance, args) -> new long[] { System.nanoTime() }
                ),
                new HostFunction(
                    "bench",
                    "report",
                    FunctionType.of(
                        new ValType[] { ValType.getI32(), ValType.getI64() },
                        new ValType[] {}
                    ),
                    (WasmFunctionHandle) (instance, args) -> {
                        switch ((int) args[0]) {
                            case 1:
                                reportedPrimaryValue = args[1];
                                break;
                            case 2:
                                reportedPublicCount = args[1];
                                break;
                            case 3:
                                reportedElapsedNanos = args[1];
                                break;
                            default:
                                break;
                        }
                        return null;
                    }
                ),
            };
        }
    }

    private static final class JsonPayload {
        final byte[] bytes;
        final int itemCount;
        final int publicCount;

        JsonPayload(byte[] bytes, int itemCount, int publicCount) {
            this.bytes = bytes;
            this.itemCount = itemCount;
            this.publicCount = publicCount;
        }
    }

    private static JsonPayload generatedCatalogJsonPayload(int targetBytes) {
        StringBuilder builder = new StringBuilder(targetBytes + 1024);
        int itemCount = 0;
        int publicCount = 0;
        builder.append('[');
        while (builder.length() < targetBytes) {
            if (itemCount > 0) builder.append(',');
            boolean isPublic = itemCount % 5 != 0;
            if (isPublic) publicCount += 1;
            builder.append(catalogItemJson(itemCount, isPublic ? "public" : "premium"));
            itemCount += 1;
        }
        builder.append(']');
        return new JsonPayload(
            builder.toString().getBytes(StandardCharsets.UTF_8),
            itemCount,
            publicCount
        );
    }

    private static String catalogItemJson(int index, String visibility) {
        return "{\"itemId\":\"entry-" + index +
            "\",\"name\":\"Synthetic Catalog Entry " + index +
            "\",\"summary\":\"Generated summary for catalog item " + index +
            "\",\"visibility\":\"" + visibility +
            "\",\"license\":\"standard\",\"lengthSeconds\":" + (1200 + index) +
            ",\"sourceLabel\":\"Synthetic Source\",\"categories\":[\"category-a\",\"category-b\",\"category-c\"]," +
            "\"contributors\":[{\"contributorId\":\"contributor-" + (index % 97) +
            "\",\"displayName\":\"Contributor " + (index % 97) +
            "\"},{\"contributorId\":\"contributor-" + (index % 53) +
            "\",\"displayName\":\"Alias " + (index % 53) + "\"}]}";
    }

    private static long min(long[] values) {
        long result = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] < result) result = values[i];
        }
        return result;
    }

    private static String formatSamples(String label, long[] nanos) {
        if (nanos.length <= 1) return "";
        StringBuilder builder = new StringBuilder(", ").append(label).append("=[");
        for (int i = 0; i < nanos.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(nanos[i] / 1_000_000L);
        }
        return builder.append(']').toString();
    }

    private static String formatAverage(String label, long[] nanos) {
        if (nanos.length <= 1) return "";
        long total = 0L;
        for (long value : nanos) total += value;
        return ", " + label + "=" + ((total / nanos.length) / 1_000_000L);
    }
}
