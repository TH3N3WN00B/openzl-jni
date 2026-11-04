package io.github.hybledav;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * OpenZL compressor and decompressor bound to a native state.
 *
 * <p>Each instance owns a native compression context, decompression context and compressor,
 * taken from a native pool on construction and returned to it by {@link #close()} (or by a
 * {@link Cleaner} if the instance becomes unreachable without being closed). Compression starts
 * from the graph given to the constructor; {@link #configureProfile(OpenZLProfile)},
 * {@link #configureSddl(byte[])} and {@link #configureSerialized(byte[])} replace it.
 *
 * <p>Instances are not thread-safe. After {@link #close()}, most methods throw
 * {@link IllegalStateException}, but the plain native methods ({@link #compress(byte[])},
 * {@link #decompress(byte[])}, {@link #setParameter(int, int)}, {@link #getParameter(int)},
 * {@link #serialize()}, {@link #serializeToJson()}) and {@link #reset()} return {@code null},
 * {@code 0} or an empty string, or do nothing.
 */
public class OpenZLCompressor implements AutoCloseable {
    private static final class CleanerHolder {
        private static final Cleaner INSTANCE = Cleaner.create();
    }

    private static Cleaner cleaner() {
        return CleanerHolder.INSTANCE;
    }

    private final OpenZLGraph graph;
    private long nativeHandle;
    private final Cleaner.Cleanable cleanable;
    private final NativeHandleCleaner nativeHandleCleaner;
    private static final int META_ORIGINAL_SIZE = 0;
    private static final int META_COMPRESSED_SIZE = 1;
    private static final int META_OUTPUT_TYPE = 2;
    private static final int META_GRAPH_ID = 3;
    private static final int META_ELEMENT_COUNT = 4;
    private static final int META_FORMAT_VERSION = 5;
    private static final int META_LENGTH = 6;
    private static final int CPARAM_COMPRESSION_LEVEL = 2;

    /**
     * Creates a compressor that starts from the {@link OpenZLGraph#ZSTD} graph.
     *
     * @throws IllegalStateException if the native state cannot be created
     */
    public OpenZLCompressor() {
        this(OpenZLGraph.ZSTD);
    }

    /**
     * Creates a compressor that starts from {@code graph}. Loads the native library if needed.
     *
     * @param graph starting compression graph; {@link OpenZLGraph#AUTO} selects zstd
     * @throws NullPointerException if {@code graph} is null
     * @throws IllegalStateException if the native state cannot be created
     */
    public OpenZLCompressor(OpenZLGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        OpenZLNative.load();
        nativeHandle = nativeCreate(graph.id());
        if (nativeHandle == 0) {
            throw new IllegalStateException("Unable to initialise OpenZL native compressor");
        }
        nativeHandleCleaner = new NativeHandleCleaner(nativeHandle);
        cleanable = cleaner().register(this, nativeHandleCleaner);
    }

    /**
     * Returns the graph this compressor was created with. It is not updated by
     * {@code configureProfile}, {@code configureSddl} or {@code configureSerialized}.
     *
     * @return the graph passed to the constructor
     */
    public OpenZLGraph graph() {
        return graph;
    }

    private void ensureOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Compressor already closed");
        }
    }

    private native long nativeCreate(int graphId);
    /**
     * Sets a raw OpenZL compression parameter on the underlying compressor; it is kept across
     * {@link #reset()}. {@code param} is a {@code ZL_CParam} value from {@code zl_compress.h}
     * (for example 2 is the compression level and 4 the format version). Does nothing if the
     * compressor is closed.
     *
     * @param param {@code ZL_CParam} identifier
     * @param value parameter value
     */
    public native void setParameter(int param, int value);
    /**
     * Returns a raw OpenZL compression parameter of the underlying compressor.
     *
     * @param param {@code ZL_CParam} identifier, as for {@link #setParameter(int, int)}
     * @return the parameter value, or 0 if it is unset or the compressor is closed
     */
    public native int getParameter(int param);
    /**
     * Returns the serialized (CBOR) form of the current compressor configuration.
     *
     * <p>The native bytes are converted with JNI {@code NewStringUTF}, which stops at the first
     * zero byte and expects modified UTF-8, so the string is not a faithful copy of the binary
     * data and cannot be turned back into input for {@link #configureSerialized(byte[])}. Use
     * {@link #serializeToJson()} for a readable view.
     *
     * @return the serialized configuration as a string, or an empty string if the compressor
     *         is closed
     */
    public native String serialize();
    /**
     * Returns a JSON representation of the current compressor configuration, including its
     * parameters, mainly for inspection and debugging.
     *
     * @return the configuration as JSON, or an empty string if the compressor is closed
     */
    public native String serializeToJson();
    /**
     * Returns the maximum compressed size ({@code ZL_compressBound}) of an input of
     * {@code inputSize} bytes, for sizing output buffers. Loads the native library if needed.
     *
     * @param inputSize input size in bytes
     * @return upper bound of the compressed size in bytes
     * @throws IllegalArgumentException if {@code inputSize} is negative
     * @throws IllegalStateException if the bound cannot be computed
     */
    public static long maxCompressedSize(int inputSize) {
        if (inputSize < 0) {
            throw new IllegalArgumentException("inputSize must be non-negative");
        }
        OpenZLNative.load();
        long bound = maxCompressedSizeNative(inputSize);
        if (bound < 0) {
            throw new IllegalStateException("Failed to query compression bound");
        }
        return bound;
    }
    /**
     * Compresses {@code input} into a new OpenZL frame.
     *
     * @param input bytes to compress
     * @return the compressed frame, or {@code null} if compression fails or the compressor is
     *         closed
     * @throws NullPointerException if {@code input} is null
     */
    public native byte[] compress(byte[] input);
    /**
     * Decompresses a complete OpenZL frame into a new array sized from the frame header.
     *
     * @param input compressed frame
     * @return the decompressed bytes, or {@code null} if the frame is invalid, decompression
     *         fails or the compressor is closed
     * @throws NullPointerException if {@code input} is null
     */
    public native byte[] decompress(byte[] input);
    private static native long maxCompressedSizeNative(int inputSize);
    private native int compressDirect(ByteBuffer src, int srcPos, int srcLen,
                                      ByteBuffer dst, int dstPos, int dstLen);
    private native int decompressDirect(ByteBuffer src, int srcPos, int srcLen,
                                        ByteBuffer dst, int dstPos, int dstLen);
    private native long getDecompressedSizeNative(byte[] input);
    private native long getDecompressedSizeDirect(ByteBuffer src, int srcPos, int srcLen);
    private native void resetNative();
    private native int compressIntoNative(byte[] src, int srcOffset, int srcLength,
                                          byte[] dst, int dstOffset, int dstLength);
    private native int decompressIntoNative(byte[] src, int srcOffset, int srcLength,
                                            byte[] dst, int dstOffset, int dstLength);
    private native void destroyCompressor();
    private native void configureSddlNative(byte[] compiledDescription);
    private native void configureProfileNative(String profileName, String[] keys, String[] values);
    private native void configureSerializedNative(String profileName, byte[] serializedCompressor);

    /**
     * Compresses the remaining bytes of {@code src} into {@code dst}, starting at the position
     * of {@code dst}. On success the position of {@code src} is set to its limit and the
     * position of {@code dst} is advanced by the number of bytes written.
     *
     * @param src direct buffer holding the input
     * @param dst direct buffer receiving the frame; size it with {@link #maxCompressedSize(int)}
     * @return number of bytes written to {@code dst}
     * @throws NullPointerException if {@code src} or {@code dst} is null
     * @throws IllegalArgumentException if {@code src} or {@code dst} is not direct
     * @throws IllegalStateException if compression fails (for example because {@code dst} has
     *         too little room) or the compressor is closed
     */
    public int compress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = compressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Compression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    /**
     * Compresses the remaining bytes of {@code src} into a buffer acquired from
     * {@code buffers} with at least {@link #maxCompressedSize(int)} bytes of capacity. The
     * position of {@code src} is set to its limit.
     *
     * @param src direct buffer holding the input
     * @param buffers pool that supplies the output buffer
     * @return the output buffer with position 0 and limit at the end of the frame; it can be
     *         handed back with {@link OpenZLBufferManager#release(ByteBuffer)}
     * @throws NullPointerException if {@code src} or {@code buffers} is null
     * @throws IllegalArgumentException if {@code src} is not direct or the compression bound
     *         exceeds {@code Integer.MAX_VALUE}
     * @throws IllegalStateException if compression fails or the compressor is closed
     */
    public ByteBuffer compress(ByteBuffer src, OpenZLBufferManager buffers) {
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        long bound = maxCompressedSize(src.remaining());
        ByteBuffer dst = buffers.acquire(checkedCapacity(bound));
        int written = compress(src, dst);
        dst.limit(written);
        dst.position(0);
        return dst;
    }

    /**
     * Decompresses the frame held in the remaining bytes of {@code src} into {@code dst},
     * starting at the position of {@code dst}. On success the position of {@code src} is set
     * to its limit and the position of {@code dst} is advanced by the number of bytes written.
     *
     * @param src direct buffer holding the compressed frame
     * @param dst direct buffer receiving the decompressed bytes; size it with
     *        {@link #getDecompressedSize(ByteBuffer)}
     * @return number of bytes written to {@code dst}
     * @throws NullPointerException if {@code src} or {@code dst} is null
     * @throws IllegalArgumentException if {@code src} or {@code dst} is not direct
     * @throws IllegalStateException if decompression fails (for example because the frame is
     *         invalid or {@code dst} has too little room) or the compressor is closed
     */
    public int decompress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = decompressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    /**
     * Decompresses the frame held in the remaining bytes of {@code src} into a buffer acquired
     * from {@code buffers}, sized with {@link #getDecompressedSize(ByteBuffer)}. The position
     * of {@code src} is set to its limit.
     *
     * @param src direct buffer holding the compressed frame
     * @param buffers pool that supplies the output buffer
     * @return the output buffer with position 0 and limit at the end of the decompressed data;
     *         it can be handed back with {@link OpenZLBufferManager#release(ByteBuffer)}
     * @throws NullPointerException if {@code src} or {@code buffers} is null
     * @throws IllegalArgumentException if {@code src} is not direct or the decompressed size
     *         exceeds {@code Integer.MAX_VALUE}
     * @throws IllegalStateException if the decompressed size cannot be read, decompression
     *         fails or the compressor is closed
     */
    public ByteBuffer decompress(ByteBuffer src, OpenZLBufferManager buffers) {
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        ByteBuffer srcView = src.duplicate();
        long expected = getDecompressedSize(srcView);
        ByteBuffer dst = buffers.acquire(checkedCapacity(expected));
        int written = decompress(srcView, dst);
        src.position(src.limit());
        dst.limit(written);
        dst.position(0);
        return dst;
    }

    private static void requireDirect(ByteBuffer buffer, String name) {
        if (buffer == null) {
            throw new NullPointerException(name + " buffer is null");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException(name + " buffer must be direct");
        }
    }

    /**
     * Returns the decompressed size recorded in the header of a single-output frame.
     *
     * @param input compressed frame, or at least its complete header
     * @return decompressed size in bytes
     * @throws NullPointerException if {@code input} is null
     * @throws IllegalStateException if the size cannot be read from {@code input} or the
     *         compressor is closed
     */
    public long getDecompressedSize(byte[] input) {
        Objects.requireNonNull(input, "input");
        long result = getDecompressedSizeNative(input);
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    /**
     * Same as {@link #getDecompressedSize(byte[])} for the remaining bytes of a direct buffer.
     * The position of {@code input} is not changed.
     *
     * @param input direct buffer holding the compressed frame, or at least its complete header
     * @return decompressed size in bytes
     * @throws NullPointerException if {@code input} is null
     * @throws IllegalArgumentException if {@code input} is not direct
     * @throws IllegalStateException if the size cannot be read from {@code input} or the
     *         compressor is closed
     */
    public long getDecompressedSize(ByteBuffer input) {
        requireDirect(input, "input");
        long result = getDecompressedSizeDirect(input, input.position(), input.remaining());
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    /**
     * Resets the compression and decompression contexts to their defaults and selects the
     * current starting graph again. The compressor itself is kept, including its compression
     * level, parameters set with {@link #setParameter(int, int)} and any configured profile,
     * SDDL description or serialized compressor. Does nothing if the compressor is closed.
     */
    public void reset() {
        resetNative();
    }

    /**
     * Configures this compressor with a compiled SDDL description (see
     * {@link OpenZLSddl#compile}) and makes the resulting graph the starting graph.
     *
     * @param compiledDescription compiled SDDL description
     * @throws NullPointerException if {@code compiledDescription} is null
     * @throws IllegalArgumentException if {@code compiledDescription} is empty
     * @throws IllegalStateException if the compressor is closed or OpenZL rejects the
     *         description
     */
    public void configureSddl(byte[] compiledDescription) {
        ensureOpen();
        Objects.requireNonNull(compiledDescription, "compiledDescription");
        if (compiledDescription.length == 0) {
            throw new IllegalArgumentException("compiledDescription must not be empty");
        }
        configureSddlNative(compiledDescription);
    }

    /**
     * Loads a serialized compressor, typically one produced by {@link #train} or
     * {@link #trainFromDirectory}, so that this instance compresses with it.
     *
     * <p>Unlike {@link #compressWithSerializedNative}, which rebuilds the compressor and a
     * compression context on every call, the loaded compressor and context are reused for
     * every subsequent {@code compress} call on this instance.
     *
     * <p>The serialized compressor replaces the current configuration, including the
     * compression level it was trained with; call {@link #setCompressionLevel} afterwards
     * to override it. Components of the built-in CSV and Parquet parsers are registered
     * automatically; use {@link #configureSerialized(OpenZLProfile, byte[])} when the
     * compressor was trained from another profile with custom components.
     *
     * @param serializedCompressor serialized compressor bytes
     * @throws IllegalArgumentException if the bytes are not a loadable compressor; the
     *         previous configuration is kept
     */
    public void configureSerialized(byte[] serializedCompressor) {
        ensureOpen();
        Objects.requireNonNull(serializedCompressor, "serializedCompressor");
        configureSerializedNative(null, serializedCompressor);
    }

    /**
     * Same as {@link #configureSerialized(byte[])}, registering the components of
     * {@code profile} first (the profile the compressor was trained from).
     *
     * @param profile profile the serialized compressor was trained from
     * @param serializedCompressor serialized compressor bytes
     * @throws IllegalArgumentException if the bytes are not a loadable compressor; the
     *         previous configuration is kept
     */
    public void configureSerialized(OpenZLProfile profile, byte[] serializedCompressor) {
        ensureOpen();
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(serializedCompressor, "serializedCompressor");
        configureSerializedNative(profile.profileName(), serializedCompressor);
    }

    /**
     * Configures this compressor with a built-in profile and no profile argument; same as
     * {@code configureProfile(profile, Map.of())}.
     *
     * @param profile profile to apply
     * @throws NullPointerException if {@code profile} is null
     * @throws IllegalArgumentException if the native library does not know the profile or the
     *         profile requires an argument
     * @throws IllegalStateException if the compressor is closed or the profile cannot be built
     */
    public void configureProfile(OpenZLProfile profile) {
        configureProfile(profile, Map.of());
    }

    /**
     * Configures this compressor with a built-in profile: the profile's graphs are registered
     * on the current compressor and its graph becomes the starting graph. The compression
     * level and other parameters already set are kept.
     *
     * <p>As in the OpenZL CLI, a profile takes a single argument: only the value under the key
     * {@code "TBD"} is forwarded (as {@code --profile-arg}); other entries are ignored.
     *
     * @param profile profile to apply
     * @param arguments profile arguments, or {@code null} for none
     * @throws NullPointerException if {@code profile}, a key or a value is null
     * @throws IllegalArgumentException if the native library does not know the profile or the
     *         profile rejects its argument
     * @throws IllegalStateException if the compressor is closed or the profile cannot be built
     */
    public void configureProfile(OpenZLProfile profile, Map<String, String> arguments) {
        ensureOpen();
        Objects.requireNonNull(profile, "profile");

        Map<String, String> args = arguments == null ? Map.of() : arguments;
        String[] keys = new String[args.size()];
        String[] values = new String[args.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "arguments key");
            String value = Objects.requireNonNull(entry.getValue(),
                    () -> "arguments value for key " + key);
            keys[index] = key;
            values[index] = value;
            index++;
        }
        configureProfileNative(profile.profileName(), keys, values);
    }

    /**
     * Sets the compression level of the underlying compressor. The level is kept across
     * {@link #reset()} and {@link #configureProfile(OpenZLProfile)}, but replaced by
     * {@link #configureSerialized(byte[])}.
     *
     * @param level compression level
     * @throws NullPointerException if {@code level} is null
     * @throws IllegalStateException if the compressor is closed
     */
    public void setCompressionLevel(OpenZLCompressionLevel level) {
        ensureOpen();
        Objects.requireNonNull(level, "level");
        setParameter(CPARAM_COMPRESSION_LEVEL, level.level());
    }

    /**
     * Returns the compression level of the underlying compressor.
     *
     * @return the current compression level
     * @throws IllegalStateException if the compressor is closed
     * @throws IllegalArgumentException if the native level has no
     *         {@link OpenZLCompressionLevel} constant (for example one set with
     *         {@link #setParameter(int, int)})
     */
    public OpenZLCompressionLevel getCompressionLevel() {
        ensureOpen();
        int value = getParameter(CPARAM_COMPRESSION_LEVEL);
        return OpenZLCompressionLevel.fromLevel(value);
    }

    /**
     * Compresses all of {@code input} into {@code output}, starting at index 0.
     *
     * @param input bytes to compress
     * @param output destination array; size it with {@link #maxCompressedSize(int)}
     * @return number of bytes written to {@code output}
     * @throws NullPointerException if {@code input} or {@code output} is null
     * @throws IllegalStateException if compression fails (for example because {@code output}
     *         is too small) or the compressor is closed
     */
    public int compress(byte[] input, byte[] output) {
        Objects.requireNonNull(output, "output");
        return compress(input, 0, input.length, output, 0, output.length);
    }

    /**
     * Compresses {@code inputLength} bytes of {@code input} starting at {@code inputOffset}
     * into {@code output}, starting at {@code outputOffset}.
     *
     * @param input source array
     * @param inputOffset index of the first input byte
     * @param inputLength number of input bytes
     * @param output destination array
     * @param outputOffset index at which the frame is written
     * @param outputLength room available in {@code output}; size it with
     *        {@link #maxCompressedSize(int)}
     * @return number of bytes written to {@code output}
     * @throws NullPointerException if {@code input} or {@code output} is null
     * @throws IndexOutOfBoundsException if a range lies outside its array
     * @throws IllegalStateException if compression fails (for example because the output range
     *         is too small) or the compressor is closed
     */
    public int compress(byte[] input, int inputOffset, int inputLength,
            byte[] output, int outputOffset, int outputLength) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        checkRange(input.length, inputOffset, inputLength, "input");
        checkRange(output.length, outputOffset, outputLength, "output");
        int written = compressIntoNative(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (written < 0) {
            throw new IllegalStateException("Compression failed");
        }
        return written;
    }

    /**
     * Decompresses the frame in {@code input} into {@code output}, starting at index 0.
     *
     * @param input compressed frame
     * @param output destination array; size it with {@link #getDecompressedSize(byte[])}
     * @return number of bytes written to {@code output}
     * @throws NullPointerException if {@code input} or {@code output} is null
     * @throws IllegalStateException if decompression fails (for example because the frame is
     *         invalid or {@code output} is too small) or the compressor is closed
     */
    public int decompress(byte[] input, byte[] output) {
        Objects.requireNonNull(output, "output");
        return decompress(input, 0, input.length, output, 0, output.length);
    }

    /**
     * Decompresses the frame stored in {@code inputLength} bytes of {@code input} starting at
     * {@code inputOffset} into {@code output}, starting at {@code outputOffset}.
     *
     * @param input array holding the compressed frame
     * @param inputOffset index of the first byte of the frame
     * @param inputLength length of the frame in bytes
     * @param output destination array
     * @param outputOffset index at which the decompressed bytes are written
     * @param outputLength room available in {@code output}; size it with
     *        {@link #getDecompressedSize(byte[])}
     * @return number of bytes written to {@code output}
     * @throws NullPointerException if {@code input} or {@code output} is null
     * @throws IndexOutOfBoundsException if a range lies outside its array
     * @throws IllegalStateException if decompression fails (for example because the frame is
     *         invalid or the output range is too small) or the compressor is closed
     */
    public int decompress(byte[] input, int inputOffset, int inputLength,
            byte[] output, int outputOffset, int outputLength) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        checkRange(input.length, inputOffset, inputLength, "input");
        checkRange(output.length, outputOffset, outputLength, "output");
        int written = decompressIntoNative(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        return written;
    }

    /**
     * Compresses {@code data} as one numeric input of 32-bit elements, starting from the
     * current graph. An empty array yields an empty byte array, which is not an OpenZL frame
     * and is rejected by {@link #decompressInts(byte[])}.
     *
     * @param data values to compress
     * @return the compressed frame, or an empty array if {@code data} is empty
     * @throws NullPointerException if {@code data} is null
     * @throws IllegalStateException if the compressor is closed or compression fails
     */
    public byte[] compressInts(int[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressIntsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress int array");
        }
        return result;
    }

    /**
     * Compresses {@code data} as one numeric input of 64-bit elements, starting from the
     * current graph. An empty array yields an empty byte array, which is not an OpenZL frame
     * and is rejected by {@link #decompressLongs(byte[])}.
     *
     * @param data values to compress
     * @return the compressed frame, or an empty array if {@code data} is empty
     * @throws NullPointerException if {@code data} is null
     * @throws IllegalStateException if the compressor is closed or compression fails
     */
    public byte[] compressLongs(long[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressLongsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress long array");
        }
        return result;
    }

    /**
     * Compresses {@code data} as one numeric input of 32-bit elements, starting from the
     * current graph. An empty array yields an empty byte array, which is not an OpenZL frame
     * and is rejected by {@link #decompressFloats(byte[])}.
     *
     * @param data values to compress
     * @return the compressed frame, or an empty array if {@code data} is empty
     * @throws NullPointerException if {@code data} is null
     * @throws IllegalStateException if the compressor is closed or compression fails
     */
    public byte[] compressFloats(float[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressFloatsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress float array");
        }
        return result;
    }

    /**
     * Compresses {@code data} as one numeric input of 64-bit elements, starting from the
     * current graph. An empty array yields an empty byte array, which is not an OpenZL frame
     * and is rejected by {@link #decompressDoubles(byte[])}.
     *
     * @param data values to compress
     * @return the compressed frame, or an empty array if {@code data} is empty
     * @throws NullPointerException if {@code data} is null
     * @throws IllegalStateException if the compressor is closed or compression fails
     */
    public byte[] compressDoubles(double[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressDoublesNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress double array");
        }
        return result;
    }

    /**
     * Decompresses a frame whose output is numeric with 32-bit elements, such as one produced
     * by {@link #compressInts(int[])}.
     *
     * @param compressed compressed frame
     * @return the decompressed values
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is empty
     * @throws IllegalStateException if the compressor is closed, decompression fails or the
     *         output is not numeric with 32-bit elements
     */
    public int[] decompressInts(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        int[] result = decompressIntsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress int array");
        }
        return result;
    }

    /**
     * Decompresses a frame whose output is numeric with 64-bit elements, such as one produced
     * by {@link #compressLongs(long[])}.
     *
     * @param compressed compressed frame
     * @return the decompressed values
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is empty
     * @throws IllegalStateException if the compressor is closed, decompression fails or the
     *         output is not numeric with 64-bit elements
     */
    public long[] decompressLongs(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        long[] result = decompressLongsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress long array");
        }
        return result;
    }

    /**
     * Decompresses a frame whose output is numeric with 32-bit elements, such as one produced
     * by {@link #compressFloats(float[])}; the element bits are read as {@code float} values.
     *
     * @param compressed compressed frame
     * @return the decompressed values
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is empty
     * @throws IllegalStateException if the compressor is closed, decompression fails or the
     *         output is not numeric with 32-bit elements
     */
    public float[] decompressFloats(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        float[] result = decompressFloatsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress float array");
        }
        return result;
    }

    /**
     * Decompresses a frame whose output is numeric with 64-bit elements, such as one produced
     * by {@link #compressDoubles(double[])}; the element bits are read as {@code double} values.
     *
     * @param compressed compressed frame
     * @return the decompressed values
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is empty
     * @throws IllegalStateException if the compressor is closed, decompression fails or the
     *         output is not numeric with 64-bit elements
     */
    public double[] decompressDoubles(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        double[] result = decompressDoublesNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress double array");
        }
        return result;
    }

    /**
     * Reads metadata of a compressed frame without decompressing it. Only the first output of
     * the frame is described, the compressed size is the length of {@code compressed}, and the
     * reported graph is a heuristic guess ({@link OpenZLGraph#STORE} when both sizes are equal,
     * otherwise derived from the output type), not the graph that produced the frame.
     *
     * @param compressed compressed frame
     * @return metadata of the frame
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is empty
     * @throws IllegalStateException if the compressor is closed or the frame cannot be parsed
     */
    public OpenZLCompressionInfo inspect(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        long[] meta = describeFrameNative(compressed);
        return toCompressionInfo(meta);
    }

    /**
     * Same as {@link #inspect(byte[])} for the remaining bytes of a direct buffer. The position
     * of {@code compressed} is not changed.
     *
     * @param compressed direct buffer holding the compressed frame
     * @return metadata of the frame
     * @throws NullPointerException if {@code compressed} is null
     * @throws IllegalArgumentException if {@code compressed} is not direct
     * @throws IllegalStateException if the compressor is closed or the frame cannot be parsed
     */
    public OpenZLCompressionInfo inspect(ByteBuffer compressed) {
        ensureOpen();
        requireDirect(compressed, "compressed");
        ByteBuffer view = compressed.duplicate();
        long[] meta = describeFrameDirectNative(view, view.position(), view.remaining());
        return toCompressionInfo(meta);
    }

    private OpenZLCompressionInfo toCompressionInfo(long[] meta) {
        if (meta == null || meta.length < META_LENGTH) {
            throw new IllegalStateException("Unable to inspect compressed frame");
        }
        OpenZLCompressionInfo.DataFlavor flavor =
                OpenZLCompressionInfo.DataFlavor.fromNative((int) meta[META_OUTPUT_TYPE]);
        OpenZLGraph inferredGraph = OpenZLGraph.fromNativeId((int) meta[META_GRAPH_ID]);
        long elementCount = meta[META_ELEMENT_COUNT];
        int formatVersion = (int) meta[META_FORMAT_VERSION];
        return new OpenZLCompressionInfo(
                meta[META_ORIGINAL_SIZE],
                meta[META_COMPRESSED_SIZE],
                inferredGraph,
                flavor,
                elementCount,
                formatVersion);
    }

    private native byte[] compressIntsNative(int[] data);
    private native byte[] compressLongsNative(long[] data);
    private native byte[] compressFloatsNative(float[] data);
    private native byte[] compressDoublesNative(double[] data);
    private native int[] decompressIntsNative(byte[] data);
    private native long[] decompressLongsNative(byte[] data);
    private native float[] decompressFloatsNative(byte[] data);
    private native double[] decompressDoublesNative(byte[] data);
    private native long[] describeFrameNative(byte[] data);
    private native long[] describeFrameDirectNative(ByteBuffer data, int position, int length);

    private static void checkRange(int arrayLength, int offset, int length, String name) {
        if (offset < 0 || length < 0 || offset > arrayLength - length) {
            throw new IndexOutOfBoundsException(
                    String.format(Locale.ROOT, "%s range [%d, %d) out of bounds", name, offset, offset + length));
        }
    }

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalStateException("Native reported negative capacity: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    /**
     * Allocation strategy for the data buffers OpenZL creates while compressing; see
     * {@link #setDataArena(OpenZLDataArena)}.
     */
    public enum OpenZLDataArena {
        /** Allocates each buffer on the heap; OpenZL's default. */
        HEAP,
        /**
         * Carves buffers out of one primary buffer that is resized between compressions,
         * falling back to the heap when it is full.
         */
        STACK
    }

    /**
     * Selects the data arena of the compression context, an advanced (experimental) OpenZL
     * setting; decompression is not affected. Switching frees the previous arena.
     *
     * @param arena arena type
     * @throws NullPointerException if {@code arena} is null
     * @throws IllegalStateException if the compressor is closed or OpenZL rejects the setting
     */
    public void setDataArena(OpenZLDataArena arena) {
        ensureOpen();
        Objects.requireNonNull(arena, "arena");
        setDataArenaNative(arena.ordinal());
    }

    private native void setDataArenaNative(int arenaOrdinal);
    private static native String[] listProfilesNative();

    /**
     * Lists the compression profiles built into the native library. Loads the native library
     * if needed.
     *
     * @return one {@code "name:description"} entry per profile; the part before the first
     *         {@code ':'} is the profile name accepted by the profile-based methods
     */
    public static String[] listProfiles() {
        OpenZLNative.load();
        String[] arr = listProfilesNative();
        return arr == null ? new String[0] : arr;
    }

    private static native byte[][] trainNative(String profileName, byte[][] inputs,
            int maxTimeSecs, int threads, int numSamples, boolean pareto);

    /**
     * One-shot compression of {@code input} with the default compressor of a profile.
     * Loads the native library itself, so it can be the first OpenZL call in the JVM.
     *
     * @param profileName name of an OpenZL profile (see {@link #listProfiles()})
     * @param input bytes to compress
     * @return an OpenZL frame
     * @throws NullPointerException if {@code profileName} or {@code input} is null
     * @throws IllegalArgumentException if the profile is unknown
     * @throws IllegalStateException if the profile cannot be built or compression fails
     */
    public static byte[] compressWithProfileNative(String profileName, byte[] input) {
        OpenZLNative.load();
        return compressWithProfileNative0(profileName, input);
    }

    /**
     * One-shot compression of {@code input} with a serialized (e.g. trained) compressor.
     * Loads the native library itself, so it can be the first OpenZL call in the JVM.
     *
     * @param profileName profile whose components the serialized compressor depends on; an
     *        unknown name is ignored
     * @param serializedCompressor a compressor as returned by {@link #train} or {@link #trainFromDirectory}
     * @param input bytes to compress
     * @return an OpenZL frame
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if the serialized compressor cannot be loaded or
     *         compression fails
     */
    public static byte[] compressWithSerializedNative(String profileName, byte[] serializedCompressor, byte[] input) {
        OpenZLNative.load();
        return compressWithSerializedNative0(profileName, serializedCompressor, input);
    }

    // Public static entry points must call OpenZLNative.load() before reaching a native method.
    private static native byte[] compressWithProfileNative0(String profileName, byte[] input);
    private static native byte[] compressWithSerializedNative0(String profileName, byte[] serializedCompressor, byte[] input);

    /**
     * Trains compressors for a profile from in-memory samples. The samples are written to a
     * temporary directory, which is deleted afterwards, and passed to
     * {@link #trainFromDirectory(String, String, TrainOptions)}.
     *
     * @param profileName name of an OpenZL profile (see {@link #listProfiles()})
     * @param inputs training samples, one per element; {@code null} elements are empty samples
     * @param opts training options, or {@code null} for the defaults of {@link TrainOptions}
     * @return serialized compressors for {@link #configureSerialized(byte[])} or
     *         {@link #compressWithSerializedNative}: one, or the Pareto frontier when
     *         {@code paretoFrontier} is set
     * @throws NullPointerException if {@code profileName} or {@code inputs} is null
     * @throws IllegalArgumentException if the profile is unknown or cannot be built without a
     *         profile argument
     * @throws IllegalStateException if the samples cannot be written or training fails
     */
    public static byte[][] train(String profileName, byte[][] inputs, TrainOptions opts) {
        OpenZLNative.load();
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(inputs, "inputs");
        if (opts == null) {
            opts = new TrainOptions();
        }
        // Write inputs into a temporary directory and call native directory-based trainer.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("openzl-train");
            for (int i = 0; i < inputs.length; ++i) {
                byte[] b = inputs[i];
                java.nio.file.Path p = tmp.resolve(Integer.toString(i));
                java.nio.file.Files.write(p, b == null ? new byte[0] : b);
            }
            try {
                return trainFromDirectory(profileName, tmp.toString(), opts);
            } finally {
                // best-effort cleanup
                for (java.nio.file.Path p : java.nio.file.Files.newDirectoryStream(tmp)) {
                    try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                }
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to prepare training inputs", ex);
        }
    }

    private static native byte[][] trainFromDirectoryNative(String profileName, String dirPath,
            int maxTimeSecs, int threads, int numSamples, boolean pareto);

    /**
     * Trains compressors for a profile, using each regular file directly inside
     * {@code dirPath} as one sample (subdirectories are not scanned). Loads the native library
     * if needed.
     *
     * @param profileName name of an OpenZL profile (see {@link #listProfiles()})
     * @param dirPath directory holding the training samples
     * @param opts training options, or {@code null} for the defaults of {@link TrainOptions};
     *        a {@code maxTimeSecs}, {@code threads} or {@code numSamples} of 0 or less keeps
     *        OpenZL's default
     * @return serialized compressors for {@link #configureSerialized(byte[])} or
     *         {@link #compressWithSerializedNative}: one, or the Pareto frontier when
     *         {@code paretoFrontier} is set
     * @throws NullPointerException if {@code profileName} or {@code dirPath} is null
     * @throws IllegalArgumentException if the profile is unknown or cannot be built without a
     *         profile argument
     * @throws IllegalStateException if reading the samples or training fails
     */
    public static byte[][] trainFromDirectory(String profileName, String dirPath, TrainOptions opts) {
        OpenZLNative.load();
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(dirPath, "dirPath");
        if (opts == null) {
            opts = new TrainOptions();
        }
        return trainFromDirectoryNative(profileName, dirPath,
                opts.maxTimeSecs, opts.threads, opts.numSamples, opts.paretoFrontier);
    }

    /**
     * Returns the native state to the pool. Calling it again has no effect; after the first
     * call the compressor can no longer be used.
     */
    @Override
    public void close() {
        cleanable.clean();
        nativeHandle = 0;
    }

    private static synchronized void destroyCompressorHandle(long handle) {
        if (handle == 0) {
            return;
        }
        destroyCompressorHandleNative(handle);
    }

    private static native void destroyCompressorHandleNative(long handle);

    private static final class NativeHandleCleaner implements Runnable {
        private long handle;

        private NativeHandleCleaner(long handle) {
            this.handle = handle;
        }

        @Override
        public synchronized void run() {
            if (handle == 0) {
                return;
            }
            destroyCompressorHandle(handle);
            handle = 0;
        }
    }
}
