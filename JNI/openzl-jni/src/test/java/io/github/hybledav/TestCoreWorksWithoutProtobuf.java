package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * protobuf-java is an optional dependency: only {@link OpenZLProtobuf} needs it. Plain
 * compression must work in a JVM whose class path has openzl-jni and nothing else.
 */
class TestCoreWorksWithoutProtobuf {

    @Test
    void compressionWorksWithoutProtobufOnTheClassPath() throws Exception {
        String classPath = String.join(File.pathSeparator,
                location(OpenZLCompressor.class), // main classes + bundled native library
                location(Probe.class));           // this probe only
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(List.of(
                java, "--enable-native-access=ALL-UNNAMED", "-cp", classPath, Probe.class.getName()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "probe JVM did not finish");
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("protobuf absent"), output);
        assertTrue(output.contains("round trip ok"), output);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Child JVM entry point; must not touch anything outside openzl-jni's core classes. */
    public static final class Probe {
        public static void main(String[] args) {
            try {
                Class.forName("com.google.protobuf.Message");
                System.out.println("protobuf unexpectedly present");
                System.exit(2);
            } catch (ClassNotFoundException expected) {
                System.out.println("protobuf absent");
            }
            byte[] payload = "no protobuf needed ".repeat(200).getBytes(StandardCharsets.UTF_8);
            try (OpenZLCompressor compressor = new OpenZLCompressor()) {
                compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_3);
                byte[] restored = compressor.decompress(compressor.compress(payload));
                if (!Arrays.equals(payload, restored)) {
                    System.exit(3);
                }
                System.out.println("round trip ok");
            }
        }
    }
}
