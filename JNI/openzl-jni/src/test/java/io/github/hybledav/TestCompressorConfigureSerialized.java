package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestCompressorConfigureSerialized {

    private static byte[][] serialSamples() {
        byte[][] samples = new byte[8][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = ("configure-serialized-" + i + "-" + "abcdefgh".repeat(32))
                    .getBytes(StandardCharsets.UTF_8);
        }
        return samples;
    }

    private static byte[][] csvSamples() {
        byte[][] samples = new byte[12][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = ("col1,col2,col3\nrow" + i + ",val" + i + ",x\nrow" + (i + 1) + ",val" + i + ",y\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return samples;
    }

    private static TrainOptions quickTraining() {
        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        opts.threads = 1;
        return opts;
    }

    @Test
    void loadedTrainedCompressorMatchesStaticPathAndIsReusable() {
        byte[][] samples = serialSamples();
        byte[] trained = OpenZLCompressor.train("serial", samples, quickTraining())[0];

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureSerialized(OpenZLProfile.SERIAL, trained);
            for (byte[] sample : samples) {
                byte[] expected = OpenZLCompressor.compressWithSerializedNative("serial", trained, sample);
                byte[] actual = compressor.compress(sample);
                assertArrayEquals(expected, actual,
                        "Instance compression must equal the one-shot serialized path");
                assertArrayEquals(sample, compressor.decompress(actual));
            }
        }
    }

    @Test
    void csvCompressorLoadsWithoutNamingTheProfile() {
        byte[][] samples = csvSamples();
        byte[] trained = OpenZLCompressor.train("csv", samples, quickTraining())[0];

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureSerialized(trained);
            byte[] sample = samples[1];
            byte[] expected = OpenZLCompressor.compressWithSerializedNative("csv", trained, sample);
            byte[] actual = compressor.compress(sample);
            assertArrayEquals(expected, actual);
            assertArrayEquals(sample, compressor.decompress(actual));
        }
    }

    @Test
    void invalidSerializedCompressorIsRejectedAndKeepsPreviousConfiguration() {
        byte[] payload = "keep the previous configuration ".repeat(64).getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] before = compressor.compress(payload);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> compressor.configureSerialized(new byte[] {1, 2, 3, 4}));
            assertTrue(thrown.getMessage().contains("serialized compressor"), thrown::getMessage);

            assertArrayEquals(before, compressor.compress(payload),
                    "A failed load must leave the compressor unchanged");
        }
    }

    @Test
    void argumentsAreValidated() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.configureSerialized(null));
            assertThrows(NullPointerException.class,
                    () -> compressor.configureSerialized(null, new byte[] {1}));
            assertThrows(IllegalArgumentException.class, () -> compressor.configureSerialized(new byte[0]));
        }
    }
}
