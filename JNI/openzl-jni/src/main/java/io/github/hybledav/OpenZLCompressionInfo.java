package io.github.hybledav;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Metadata describing a compressed frame. Instances are produced by
 * {@link OpenZLCompressor#inspect(byte[])} or its direct-buffer variant.
 */
public final class OpenZLCompressionInfo {
    /**
     * High level classification of the payload encoded in the frame.
     */
    public enum DataFlavor {
        /** Untyped bytes (OpenZL {@code ZL_Type_serial}). */
        SERIAL,
        /** Fixed-width records (OpenZL {@code ZL_Type_struct}). */
        STRUCT,
        /** Numeric array (OpenZL {@code ZL_Type_numeric}). */
        NUMERIC,
        /** Sequence of variable-length strings (OpenZL {@code ZL_Type_string}). */
        STRING,
        /** The frame did not report a known output type. */
        UNKNOWN;

        static DataFlavor fromNative(int code) {
            switch (code) {
                case 1:
                    return SERIAL;
                case 2:
                    return STRUCT;
                case 4:
                    return NUMERIC;
                case 8:
                    return STRING;
                default:
                    return UNKNOWN;
            }
        }
    }

    private final long originalSize;
    private final long compressedSize;
    private final OpenZLGraph graph;
    private final DataFlavor flavor;
    private final long elementCount;
    private final int formatVersion;

    OpenZLCompressionInfo(
            long originalSize,
            long compressedSize,
            OpenZLGraph graph,
            DataFlavor flavor,
            long elementCount,
            int formatVersion) {
        this.originalSize = originalSize;
        this.compressedSize = compressedSize;
        this.graph = Objects.requireNonNull(graph, "graph");
        this.flavor = Objects.requireNonNull(flavor, "flavor");
        this.elementCount = elementCount;
        this.formatVersion = formatVersion;
    }

    /**
     * Size of the data once decompressed, as recorded in the frame header.
     *
     * @return decompressed size in bytes
     */
    public long originalSize() {
        return originalSize;
    }

    /**
     * Size of the inspected frame.
     *
     * @return compressed size in bytes
     */
    public long compressedSize() {
        return compressedSize;
    }

    /**
     * Best-effort guess of the graph that produced the frame. OpenZL frames do not
     * record the graph, so it is inferred from the output type and sizes; treat it as
     * a hint, not as an exact description.
     *
     * @return the inferred graph, or {@link OpenZLGraph#AUTO} if it cannot be inferred
     */
    public OpenZLGraph graph() {
        return graph;
    }

    /**
     * Type of the data stored in the frame.
     *
     * @return the output type reported by the frame
     */
    public DataFlavor flavor() {
        return flavor;
    }

    /**
     * Number of elements of the decompressed output (e.g. numbers of a numeric array),
     * when the frame reports it.
     *
     * @return the element count, or empty when unknown
     */
    public OptionalLong elementCount() {
        return elementCount >= 0 ? OptionalLong.of(elementCount) : OptionalLong.empty();
    }

    /**
     * OpenZL wire format version the frame was written with.
     *
     * @return the frame format version
     */
    public int formatVersion() {
        return formatVersion;
    }

    /**
     * Ratio of compressed bytes to original bytes. Zero when the original size is zero.
     *
     * @return {@code compressedSize / originalSize}
     */
    public double compressionRatio() {
        return originalSize == 0 ? 0.0d : (double) compressedSize / (double) originalSize;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "OpenZLCompressionInfo{original=%d, compressed=%d, ratio=%.2f%%, flavor=%s, graph=%s, elements=%s, version=%d}",
                originalSize,
                compressedSize,
                compressionRatio() * 100.0d,
                flavor,
                graph,
                elementCount >= 0 ? elementCount : "n/a",
                formatVersion);
    }
}
