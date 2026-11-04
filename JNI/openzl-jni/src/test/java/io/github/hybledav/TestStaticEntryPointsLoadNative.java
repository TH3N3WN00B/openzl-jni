package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every public static entry point must load the native library itself. Inside this test
 * JVM the library is already loaded by other tests, so each case runs in a fresh JVM
 * where the static call is the very first use of OpenZL.
 */
class TestStaticEntryPointsLoadNative {

    @ParameterizedTest
    @ValueSource(strings = {
            "compressWithProfileNative",
            "compressWithSerializedNative",
            "maxCompressedSize",
            "listProfiles",
            "sddlCompile"
    })
    void staticEntryPointWorksAsFirstNativeCall(String entryPoint) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(List.of(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(),
                entryPoint))
                .redirectErrorStream(true)
                .start();
        String output = readAll(process);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "probe JVM did not finish");

        assertFalse(output.contains("UnsatisfiedLinkError"),
                () -> entryPoint + " did not load the native library:\n" + output);
        assertEquals(0, process.exitValue(), () -> entryPoint + " probe failed:\n" + output);
        assertTrue(output.contains("PROBE-OK " + entryPoint), () -> output);
    }

    private static String readAll(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Runs in the child JVM: calls exactly one static entry point before anything else. */
    public static final class Probe {
        public static void main(String[] args) {
            String entryPoint = args[0];
            byte[] input = "hello openzl hello openzl hello openzl".getBytes(StandardCharsets.US_ASCII);
            try {
                switch (entryPoint) {
                    case "compressWithProfileNative" ->
                            OpenZLCompressor.compressWithProfileNative("serial", input);
                    case "compressWithSerializedNative" -> {
                        try {
                            // Not a valid compressor: a native error is fine, a link error is not.
                            OpenZLCompressor.compressWithSerializedNative("serial", new byte[] {1, 2, 3}, input);
                        } catch (IllegalStateException | IllegalArgumentException expected) {
                            System.out.println("native rejected bogus compressor: " + expected.getClass().getSimpleName());
                        }
                    }
                    case "maxCompressedSize" -> OpenZLCompressor.maxCompressedSize(input.length);
                    case "listProfiles" -> OpenZLCompressor.listProfiles();
                    case "sddlCompile" -> OpenZLSddl.compile(": Byte[_rem];", false, 0);
                    default -> throw new IllegalArgumentException("unknown entry point " + entryPoint);
                }
                System.out.println("PROBE-OK " + entryPoint);
            } catch (UnsatisfiedLinkError e) {
                System.out.println("UnsatisfiedLinkError: " + e.getMessage());
                System.exit(3);
            }
        }
    }
}
