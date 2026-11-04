package io.github.hybledav;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridging helpers for OpenZL's Protobuf support. The native implementation mirrors the
 * behaviour of the {@code protobuf_cli} utility: payloads can be converted between raw
 * protobuf, OpenZL, and JSON representations, and compressors can be trained directly
 * from protobuf samples.
 *
 * <p>Message types are resolved by fully-qualified name against schemas registered with
 * {@link #registerSchema(Descriptors.FileDescriptor)} or its overloads; the overloads taking a
 * {@link Descriptors.Descriptor} register the schema automatically.
 */
public final class OpenZLProtobuf {
    private OpenZLProtobuf() {}

    private static final Set<String> REGISTERED_FILE_NAMES = ConcurrentHashMap.newKeySet();

    /**
     * Representation of a protobuf message payload.
     */
    public enum Protocol {
        /** Standard protobuf binary wire format. */
        PROTO(0),
        /** OpenZL-compressed frame produced by the protobuf-aware OpenZL graph. */
        ZL(1),
        /** Protobuf JSON mapping, encoded as UTF-8. */
        JSON(2);

        private final int id;

        Protocol(int id) {
            this.id = id;
        }

        int id() {
            return id;
        }
    }

    /**
     * Converts the payload using the default compressor for the message type. Equivalent to
     * {@link #convert(byte[], Protocol, Protocol, byte[], String)} with a {@code null} compressor.
     *
     * @param payload       input data, interpreted by {@code inputProtocol}
     * @param inputProtocol protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param messageType   fully-qualified protobuf message name; its schema must be registered
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code messageType} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty or a PROTO or JSON payload
     *         cannot be parsed
     * @throws IllegalStateException if the message type is unknown or the native conversion fails
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            String messageType) {
        return convert(payload, inputProtocol, outputProtocol, null, messageType);
    }

    /**
     * Converts the payload, optionally using a trained compressor for ZL output. Without an explicit
     * compressor a per-message-type default is used, and PROTO payloads of at least 1 KiB are
     * sampled to train that default automatically (see {@link #configureTraining(String, int)}).
     *
     * @param payload        input data, interpreted by {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param compressor     serialized compressor, such as one returned by
     *                       {@link #train(byte[][], Protocol, TrainOptions, String)}, used when
     *                       {@code outputProtocol} is {@link Protocol#ZL}; {@code null} for the default
     * @param messageType    fully-qualified protobuf message name; its schema must be registered
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code messageType} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty or a PROTO or JSON payload
     *         cannot be parsed
     * @throws IllegalStateException if the message type is unknown, {@code compressor} cannot be
     *         deserialized, or the native conversion fails
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            String messageType) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        byte[] converted = convertNative(payload,
                inputProtocol.id(),
                outputProtocol.id(),
                compressor,
                messageType);
        if (converted == null) {
            throw new IllegalStateException("Native protobuf conversion failed");
        }
        return converted;
    }

    /**
     * Converts the payload using the default compressor for the descriptor's message type. The
     * descriptor's defining file and its dependencies are registered with the native bridge first.
     *
     * @param payload        input data, interpreted by {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a PROTO or JSON payload cannot be parsed
     * @throws IllegalStateException if schema registration or the native conversion fails
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        return convert(payload, inputProtocol, outputProtocol, null, descriptor);
    }

    /**
     * Overrides the minimum number of samples collected before the default compressor for the
     * descriptor's message type is trained automatically, registering the descriptor's schema
     * first. See {@link #configureTraining(String, int)}.
     *
     * @param descriptor descriptor of the protobuf message type
     * @param minSamples minimum number of samples; values &lt;= 0 are ignored and nothing is
     *                   registered
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if schema registration fails
     */
    public static void configureTraining(Descriptors.Descriptor descriptor, int minSamples) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (minSamples <= 0) {
            return;
        }
        registerSchema(descriptor.getFile());
        configureTraining(descriptor.getFullName(), minSamples);
    }

    /**
     * Overrides the minimum number of samples (default 4) collected before the default compressor
     * for the message type is trained automatically. Samples are PROTO payloads of at least 1 KiB
     * converted without an explicit compressor; training runs on the converting thread and the
     * setting applies process-wide.
     *
     * @param messageType fully-qualified protobuf message name
     * @param minSamples  minimum number of samples; values &lt;= 0 leave the current setting
     *                    unchanged
     * @throws NullPointerException if {@code messageType} is {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty and {@code minSamples} is
     *         positive
     */
    public static void configureTraining(String messageType, int minSamples) {
        Objects.requireNonNull(messageType, "messageType");
        if (minSamples <= 0) {
            return;
        }
        OpenZLNative.load();
        configureTrainingNative(messageType, minSamples);
    }
    /**
     * Converts {@code length} bytes of {@code payload} starting at {@code offset}, so the caller
     * need not copy the range into a new array. The default compressor for the message type is
     * used, and the descriptor's schema is registered first.
     *
     * @param payload        array holding the input data
     * @param offset         index of the first input byte
     * @param length         number of input bytes
     * @param inputProtocol  protocol describing the input bytes
     * @param outputProtocol target protocol
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code descriptor} is
     *         {@code null}
     * @throws IndexOutOfBoundsException if {@code offset} or {@code length} is negative or the
     *         range extends past the end of {@code payload}
     * @throws IllegalArgumentException if PROTO or JSON input cannot be parsed
     * @throws IllegalStateException if schema registration or the native conversion fails
     */
    public static byte[] convert(byte[] payload,
            int offset,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(descriptor, "descriptor");
        if (offset < 0 || length < 0 || offset + length > payload.length) {
            throw new IndexOutOfBoundsException(
                    "Invalid offset/length: offset=" + offset + ", length=" + length
                            + ", payloadLength=" + payload.length);
        }
        registerSchema(descriptor.getFile());
        return convertSliceNative(payload,
                offset,
                length,
                inputProtocol.id(),
                outputProtocol.id(),
                null,
                descriptor.getFullName());
    }

    /**
     * Converts a PROTO payload held in a direct {@link ByteBuffer} without copying it into a Java
     * array, using the default compressor. Equivalent to
     * {@link #convert(ByteBuffer, int, Protocol, Protocol, byte[], Descriptors.Descriptor)} with a
     * {@code null} compressor.
     *
     * @param payload        direct buffer whose first {@code length} bytes hold the input
     * @param length         number of input bytes, between 0 and the buffer's capacity
     * @param inputProtocol  protocol describing the input; must be {@link Protocol#PROTO}
     * @param outputProtocol target protocol
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code descriptor} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code payload} is not direct, {@code length} is out of
     *         bounds, {@code inputProtocol} is not PROTO, or the payload cannot be parsed
     * @throws IllegalStateException if schema registration or the native conversion fails
     */
    public static byte[] convert(ByteBuffer payload,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        return convert(payload, length, inputProtocol, outputProtocol, null, descriptor);
    }

    /**
     * Converts a PROTO payload held in a direct {@link ByteBuffer}, optionally using a trained
     * compressor. Input is read from index 0 of the buffer regardless of its position and limit,
     * and the buffer's position is not changed. The descriptor's schema is registered first.
     *
     * @param payload        direct buffer whose first {@code length} bytes hold the input
     * @param length         number of input bytes, between 0 and the buffer's capacity
     * @param inputProtocol  protocol describing the input; must be {@link Protocol#PROTO}
     * @param outputProtocol target protocol
     * @param compressor     serialized compressor used for ZL output, or {@code null} for the
     *                       default
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code descriptor} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code payload} is not direct, {@code length} is out of
     *         bounds, {@code inputProtocol} is not PROTO, or the payload cannot be parsed
     * @throws IllegalStateException if schema registration fails, {@code compressor} cannot be
     *         deserialized, or the native conversion fails
     */
    public static byte[] convert(ByteBuffer payload,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!payload.isDirect()) {
            throw new IllegalArgumentException("payload must be a direct ByteBuffer");
        }
        if (length < 0 || length > payload.capacity()) {
            throw new IllegalArgumentException("length out of bounds: " + length);
        }
        registerSchema(descriptor.getFile());
        return convertDirectNative(payload,
                length,
                inputProtocol.id(),
                outputProtocol.id(),
                compressor,
                descriptor.getFullName());
    }

    /**
     * Converts a PROTO payload held in a direct buffer into a caller-provided direct output buffer
     * using the default compressor. Equivalent to
     * {@link #convertInto(ByteBuffer, int, Protocol, Protocol, byte[], Descriptors.Descriptor, ByteBuffer)}
     * with a {@code null} compressor; note that {@code output} comes before {@code descriptor} here.
     *
     * @param payload        direct buffer whose first {@code length} bytes hold the input
     * @param length         number of input bytes, between 0 and the buffer's capacity
     * @param inputProtocol  protocol describing the input; must be {@link Protocol#PROTO}
     * @param outputProtocol target protocol
     * @param output         direct buffer that receives the result between its position and limit
     * @param descriptor     descriptor of the protobuf message type
     * @return the number of bytes written, or {@code -(required + 1)} if {@code output} has fewer
     *         than {@code required} bytes remaining
     * @throws NullPointerException if a buffer, a protocol or {@code descriptor} is {@code null}
     * @throws IllegalArgumentException if a buffer is not direct, {@code length} is out of bounds,
     *         {@code inputProtocol} is not PROTO, or the payload cannot be parsed
     * @throws IllegalStateException if schema registration or the native conversion fails
     */
    public static int convertInto(ByteBuffer payload,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            ByteBuffer output,
            Descriptors.Descriptor descriptor) {
        return convertInto(payload, length, inputProtocol, outputProtocol, null, descriptor, output);
    }

    /**
     * Converts a PROTO payload held in a direct buffer into a caller-provided direct output buffer,
     * optionally using a trained compressor. Input is read from index 0 of {@code payload}
     * regardless of its position; output is written at {@code output}'s position. On success the
     * output limit is set to the end of the written bytes and its position is left unchanged, so
     * the result is ready to read; if the output is too small, it is left unmodified.
     *
     * @param payload        direct buffer whose first {@code length} bytes hold the input
     * @param length         number of input bytes, between 0 and the buffer's capacity
     * @param inputProtocol  protocol describing the input; must be {@link Protocol#PROTO}
     * @param outputProtocol target protocol
     * @param compressor     serialized compressor used for ZL output, or {@code null} for the
     *                       default
     * @param descriptor     descriptor of the protobuf message type
     * @param output         direct buffer that receives the result between its position and limit
     * @return the number of bytes written, or {@code -(required + 1)} if {@code output} has fewer
     *         than {@code required} bytes remaining
     * @throws NullPointerException if a buffer, a protocol or {@code descriptor} is {@code null}
     * @throws IllegalArgumentException if a buffer is not direct, {@code length} is out of bounds,
     *         {@code inputProtocol} is not PROTO, or the payload cannot be parsed
     * @throws IllegalStateException if schema registration fails, {@code compressor} cannot be
     *         deserialized, or the native conversion fails
     */
    public static int convertInto(ByteBuffer payload,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            Descriptors.Descriptor descriptor,
            ByteBuffer output) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(output, "output");
        if (!payload.isDirect()) {
            throw new IllegalArgumentException("payload must be a direct ByteBuffer");
        }
        if (!output.isDirect()) {
            throw new IllegalArgumentException("output must be a direct ByteBuffer");
        }
        if (length < 0 || length > payload.capacity()) {
            throw new IllegalArgumentException("length out of bounds: " + length);
        }
        registerSchema(descriptor.getFile());
        int outputPosition = output.position();
        int written = convertDirectIntoNative(payload,
                length,
                inputProtocol.id(),
                outputProtocol.id(),
                compressor,
                descriptor.getFullName(),
                output,
                outputPosition,
                output.remaining());
        if (written >= 0) {
            output.limit(outputPosition + written);
            output.position(outputPosition);
        }
        return written;
    }

    /**
     * Converts the payload for the descriptor's message type, optionally using a trained
     * compressor. The descriptor's defining file and its dependencies are registered first.
     *
     * @param payload        input data, interpreted by {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param compressor     serialized compressor used for ZL output, or {@code null} for the
     *                       default
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload
     * @throws NullPointerException if {@code payload}, a protocol or {@code descriptor} is
     *         {@code null}
     * @throws IllegalArgumentException if a PROTO or JSON payload cannot be parsed
     * @throws IllegalStateException if schema registration fails, {@code compressor} cannot be
     *         deserialized, or the native conversion fails
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return convert(payload,
                inputProtocol,
                outputProtocol,
                compressor,
                descriptor.getFullName());
    }

    /**
     * Converts a text payload, encoding it as UTF-8 and decoding the result as UTF-8. Intended for
     * JSON input and output; binary protocols do not survive the round trip through a
     * {@link String}.
     *
     * @param payload        input text, interpreted by {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param messageType    fully-qualified protobuf message name; its schema must be registered
     * @return the converted payload decoded as UTF-8
     * @throws NullPointerException if {@code payload}, a protocol or {@code messageType} is
     *         {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty or a PROTO or JSON payload
     *         cannot be parsed
     * @throws IllegalStateException if the message type is unknown or the native conversion fails
     */
    public static String convertJson(String payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            String messageType) {
        Objects.requireNonNull(payload, "payload");
        byte[] bytes = convert(payload.getBytes(StandardCharsets.UTF_8),
                inputProtocol,
                outputProtocol,
                messageType);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Converts a text payload for the descriptor's message type, registering the descriptor's
     * schema first. See {@link #convertJson(String, Protocol, Protocol, String)}.
     *
     * @param payload        input text, interpreted by {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param descriptor     descriptor of the protobuf message type
     * @return the converted payload decoded as UTF-8
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a PROTO or JSON payload cannot be parsed
     * @throws IllegalStateException if schema registration or the native conversion fails
     */
    public static String convertJson(String payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return convertJson(payload,
                inputProtocol,
                outputProtocol,
                descriptor.getFullName());
    }

    /**
     * Trains compressors for the message type from sample payloads. The results can be passed as
     * the {@code compressor} argument of the conversion methods or inspected with
     * {@link #graphJsonFromCompressor(byte[])}.
     *
     * @param samples       sample payloads, each interpreted by {@code inputProtocol}; must not be
     *                      empty
     * @param inputProtocol protocol describing each sample
     * @param options       training options, or {@code null} for {@code new TrainOptions()};
     *                      non-positive numeric options fall back to OpenZL defaults
     * @param messageType   fully-qualified protobuf message name; its schema must be registered
     * @return serialized trained compressors; Pareto-frontier training may return more than one
     * @throws NullPointerException if {@code samples}, any sample, {@code inputProtocol} or
     *         {@code messageType} is {@code null}
     * @throws IllegalArgumentException if {@code samples} is empty, {@code messageType} is empty,
     *         or a PROTO or JSON sample cannot be parsed
     * @throws IllegalStateException if the message type is unknown or training fails
     */
    public static byte[][] train(byte[][] samples,
            Protocol inputProtocol,
            TrainOptions options,
            String messageType) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(messageType, "messageType");
        for (int i = 0; i < samples.length; ++i) {
            if (samples[i] == null) {
                throw new NullPointerException("samples[" + i + "]");
            }
        }

        TrainOptions opts = options == null ? new TrainOptions() : options;
        OpenZLNative.load();
        byte[][] trained = trainNative(samples,
                inputProtocol.id(),
                opts.maxTimeSecs,
                opts.threads,
                opts.numSamples,
                opts.paretoFrontier,
                messageType);
        if (trained == null) {
            throw new IllegalStateException("Native protobuf training failed");
        }
        return trained;
    }

    /**
     * Trains compressors for the descriptor's message type, registering the descriptor's schema
     * first. See {@link #train(byte[][], Protocol, TrainOptions, String)}.
     *
     * @param samples       sample payloads, each interpreted by {@code inputProtocol}; must not be
     *                      empty
     * @param inputProtocol protocol describing each sample
     * @param options       training options, or {@code null} for {@code new TrainOptions()}
     * @param descriptor    descriptor of the protobuf message type
     * @return serialized trained compressors; Pareto-frontier training may return more than one
     * @throws NullPointerException if {@code samples}, any sample, {@code inputProtocol} or
     *         {@code descriptor} is {@code null}
     * @throws IllegalArgumentException if {@code samples} is empty or a PROTO or JSON sample cannot
     *         be parsed
     * @throws IllegalStateException if schema registration or training fails
     */
    public static byte[][] train(byte[][] samples,
            Protocol inputProtocol,
            TrainOptions options,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return train(samples,
                inputProtocol,
                options,
                descriptor.getFullName());
    }

    /**
     * Returns the graph of the default compressor that the calling thread uses for the message
     * type, as pretty-printed JSON; this reflects an automatically trained compressor once one has
     * been applied. Numeric object keys emitted by OpenZL are quoted so the result is valid JSON.
     *
     * @param messageType fully-qualified protobuf message name
     * @return JSON description of the compressor graph
     * @throws NullPointerException if {@code messageType} is {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty
     * @throws IllegalStateException if the graph cannot be exported
     */
    public static String graphJson(String messageType) {
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        String json = graphJsonNative(messageType);
        if (json == null) {
            throw new IllegalStateException("Native protobuf graph export failed");
        }
        return sanitizeGraphJson(json);
    }

    /**
     * Returns the graph of a serialized compressor, such as one returned by
     * {@link #train(byte[][], Protocol, TrainOptions, String)}, as pretty-printed JSON.
     *
     * @param compressorBytes serialized compressor
     * @return JSON description of the compressor graph
     * @throws NullPointerException if {@code compressorBytes} is {@code null}
     * @throws IllegalStateException if the compressor cannot be deserialized or exported
     */
    public static String graphJsonFromCompressor(byte[] compressorBytes) {
        Objects.requireNonNull(compressorBytes, "compressorBytes");
        OpenZLNative.load();
        String json = graphJsonFromCompressorNative(compressorBytes);
        if (json == null) {
            throw new IllegalStateException("Native compressor graph export failed");
        }
        return sanitizeGraphJson(json);
    }

    /**
     * Returns the default compressor graph for the descriptor's message type, registering the
     * descriptor's schema first. See {@link #graphJson(String)}.
     *
     * @param descriptor descriptor of the protobuf message type
     * @return JSON description of the compressor graph
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if schema registration or the graph export fails
     */
    public static String graphJson(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return graphJson(descriptor.getFullName());
    }

    /**
     * Returns the topology of the default compressor graph for the message type as JSON. The
     * object holds the {@code start} graph and a {@code graphs} array, in breadth-first order from
     * it, giving each graph's id, type, optional base graph, successors, custom graphs and custom
     * nodes.
     *
     * @param messageType fully-qualified protobuf message name
     * @return JSON description of the graph topology
     * @throws NullPointerException if {@code messageType} is {@code null}
     * @throws IllegalArgumentException if {@code messageType} is empty
     * @throws IllegalStateException if the compressor has no starting graph or the export fails
     */
    public static String graphDetailJson(String messageType) {
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        String json = graphDetailJsonNative(messageType);
        if (json == null) {
            throw new IllegalStateException("Native protobuf graph detail export failed");
        }
        return sanitizeGraphJson(json);
    }

    /**
     * Returns the graph topology for the descriptor's message type, registering the descriptor's
     * schema first. See {@link #graphDetailJson(String)}.
     *
     * @param descriptor descriptor of the protobuf message type
     * @return JSON description of the graph topology
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if schema registration or the graph export fails
     */
    public static String graphDetailJson(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return graphDetailJson(descriptor.getFullName());
    }

    private static String sanitizeGraphJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        // OpenZL emits numeric object keys without quotes; make it valid JSON.
        String normalized = json.replaceAll("(?m)^(\\s*)(-?\\d+)(\\s*:)", "$1\"$2\"$3");
        normalized = normalized.replaceAll("([\\{,]\\s*)(-?\\d+)(\\s*:)", "$1\"$2\"$3");
        return prettyPrintJson(normalized);
    }

    private static String prettyPrintJson(String json) {
        StringBuilder out = new StringBuilder(json.length() + 128);
        int indent = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c).append('\n');
                    indent++;
                    appendIndent(out, indent);
                    break;
                case '}':
                case ']':
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c).append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString().trim();
    }

    private static void appendIndent(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }

    /**
     * Registers the file that defines {@code descriptor}, together with its transitive
     * dependencies, with the native bridge. See
     * {@link #registerSchema(Descriptors.FileDescriptor)}.
     *
     * @param descriptor descriptor of a protobuf message type
     * @throws NullPointerException if {@code descriptor} is {@code null}
     * @throws IllegalStateException if the native registry cannot load the descriptors
     */
    public static void registerSchema(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
    }

    /**
     * Registers a protobuf file and its transitive dependencies with the native bridge so that its
     * message types can be referenced by name. Registration is skipped when every file name has
     * already been registered, so a changed definition under the same file name is ignored.
     *
     * @param fileDescriptor protobuf file to register
     * @throws NullPointerException if {@code fileDescriptor} is {@code null}
     * @throws IllegalStateException if the native registry cannot load the descriptors
     */
    public static void registerSchema(Descriptors.FileDescriptor fileDescriptor) {
        Objects.requireNonNull(fileDescriptor, "fileDescriptor");
        Set<String> fileNames = new HashSet<>();
        collectFileNames(fileDescriptor, fileNames);
        if (fileNames.isEmpty()) {
            return;
        }
        synchronized (REGISTERED_FILE_NAMES) {
            if (REGISTERED_FILE_NAMES.containsAll(fileNames)) {
                return;
            }
        }
        DescriptorProtos.FileDescriptorSet descriptorSet = buildDescriptorSet(fileDescriptor);
        registerSchemaInternal(descriptorSet, fileNames);
    }

    /**
     * Registers the files in a serialized {@code FileDescriptorSet}, such as the output of
     * {@code protoc --descriptor_set_out --include_imports}. An empty set is ignored, and
     * registration is skipped when every file name has already been registered.
     *
     * @param descriptorSet serialized {@code google.protobuf.FileDescriptorSet}
     * @throws NullPointerException if {@code descriptorSet} is {@code null}
     * @throws IllegalArgumentException if {@code descriptorSet} is not a valid
     *         {@code FileDescriptorSet}
     * @throws IllegalStateException if the native registry cannot load a file, for example because
     *         a dependency is missing
     */
    public static void registerSchema(byte[] descriptorSet) {
        Objects.requireNonNull(descriptorSet, "descriptorSet");
        DescriptorProtos.FileDescriptorSet set;
        try {
            set = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorSet);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Invalid descriptor set", e);
        }
        if (set.getFileCount() == 0) {
            return;
        }
        Set<String> fileNames = new HashSet<>();
        for (DescriptorProtos.FileDescriptorProto file : set.getFileList()) {
            fileNames.add(file.getName());
        }
        registerSchemaInternal(set, fileNames);
    }

    private static DescriptorProtos.FileDescriptorSet buildDescriptorSet(
            Descriptors.FileDescriptor file) {
        DescriptorProtos.FileDescriptorSet.Builder builder =
                DescriptorProtos.FileDescriptorSet.newBuilder();
        Set<String> seen = new HashSet<>();
        collectFileDescriptors(file, builder, seen);
        return builder.build();
    }

    private static void collectFileDescriptors(Descriptors.FileDescriptor file,
            DescriptorProtos.FileDescriptorSet.Builder builder,
            Set<String> seen) {
        if (file == null) {
            return;
        }
        if (!seen.add(file.getName())) {
            return;
        }
        builder.addFile(file.toProto());
        for (Descriptors.FileDescriptor dependency : file.getDependencies()) {
            collectFileDescriptors(dependency, builder, seen);
        }
    }

    private static void collectFileNames(Descriptors.FileDescriptor file, Set<String> sink) {
        if (file == null) {
            return;
        }
        if (!sink.add(file.getName())) {
            return;
        }
        for (Descriptors.FileDescriptor dependency : file.getDependencies()) {
            collectFileNames(dependency, sink);
        }
    }

    /**
     * Returns the raw direct-into profiling counters as
     * {@code [enabled, parseNanos, serializeNanos, writeNanos, calls]}, where {@code enabled} is 1
     * or 0. See {@link #directIntoProfile()}.
     *
     * @return a new array holding the counter values
     * @throws IllegalStateException if the native snapshot is unavailable
     */
    public static long[] directIntoProfileValues() {
        OpenZLNative.load();
        long[] values = directIntoProfileNative();
        if (values == null || values.length < 5) {
            throw new IllegalStateException("Native direct-into profile snapshot failed");
        }
        return values;
    }

    /**
     * Returns the cumulative timing counters of the {@code convertInto} methods. Counters are only
     * collected when the environment variable {@code OPENZL_JNI_DIRECT_INTO_PROFILE} is set to a
     * non-empty value other than {@code 0}; the native side then also prints averages to standard
     * error every 1000 calls.
     *
     * @return a snapshot of the current counters
     * @throws IllegalStateException if the native snapshot is unavailable
     */
    public static DirectIntoProfileSnapshot directIntoProfile() {
        long[] values = directIntoProfileValues();
        return new DirectIntoProfileSnapshot(
                values[0] != 0L,
                values[1],
                values[2],
                values[3],
                values[4]);
    }

    /**
     * Returns the raw structured profiling counters as
     * {@code [enabled, pinNanos, buildNanos, compressNanos, outNanos, calls]}, where
     * {@code enabled} is 1 or 0. See {@link #structuredProfile()}.
     *
     * @return a new array holding the counter values
     * @throws IllegalStateException if the native snapshot is unavailable
     */
    public static long[] structuredProfileValues() {
        OpenZLNative.load();
        long[] values = structuredProfileNative();
        if (values == null || values.length < 6) {
            throw new IllegalStateException("Native structured profile snapshot failed");
        }
        return values;
    }

    /**
     * Returns the cumulative timing counters of the native structured-protobuf compress-into path
     * ({@code OpenZLStructuredProtoBridge}). Counters are only collected when the environment
     * variable {@code OPENZL_JNI_STRUCTURED_PROFILE} is set to a non-empty value other than
     * {@code 0}.
     *
     * @return a snapshot of the current counters
     * @throws IllegalStateException if the native snapshot is unavailable
     */
    public static StructuredProfileSnapshot structuredProfile() {
        long[] values = structuredProfileValues();
        return new StructuredProfileSnapshot(
                values[0] != 0L,
                values[1],
                values[2],
                values[3],
                values[4],
                values[5]);
    }

    /**
     * Immutable snapshot of the direct-into profiling counters. Times are cumulative nanoseconds
     * over all profiled calls since the process started.
     */
    public static final class DirectIntoProfileSnapshot {
        private final boolean enabled;
        private final long parseNanos;
        private final long serializeNanos;
        private final long writeNanos;
        private final long calls;

        /**
         * Creates a snapshot from explicit counter values.
         *
         * @param enabled        whether profiling is enabled
         * @param parseNanos     total time spent parsing protobuf input, in nanoseconds
         * @param serializeNanos total time spent serializing to the output protocol, in nanoseconds
         * @param writeNanos     total time spent copying results into output buffers, in
         *                       nanoseconds
         * @param calls          number of profiled calls
         */
        public DirectIntoProfileSnapshot(boolean enabled,
                                         long parseNanos,
                                         long serializeNanos,
                                         long writeNanos,
                                         long calls) {
            this.enabled = enabled;
            this.parseNanos = parseNanos;
            this.serializeNanos = serializeNanos;
            this.writeNanos = writeNanos;
            this.calls = calls;
        }

        /**
         * Returns whether direct-into profiling is enabled.
         *
         * @return {@code true} if counters are being collected
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * Returns the total time spent parsing protobuf input.
         *
         * @return cumulative parse time in nanoseconds
         */
        public long parseNanos() {
            return parseNanos;
        }

        /**
         * Returns the total time spent serializing to the output protocol.
         *
         * @return cumulative serialization time in nanoseconds
         */
        public long serializeNanos() {
            return serializeNanos;
        }

        /**
         * Returns the total time spent copying results into output buffers.
         *
         * @return cumulative write time in nanoseconds
         */
        public long writeNanos() {
            return writeNanos;
        }

        /**
         * Returns the number of profiled calls.
         *
         * @return number of calls included in the totals
         */
        public long calls() {
            return calls;
        }
    }

    /**
     * Immutable snapshot of the structured profiling counters. Times are cumulative nanoseconds
     * over all profiled calls since the process started.
     */
    public static final class StructuredProfileSnapshot {
        private final boolean enabled;
        private final long pinNanos;
        private final long buildNanos;
        private final long compressNanos;
        private final long outNanos;
        private final long calls;

        /**
         * Creates a snapshot from explicit counter values.
         *
         * @param enabled       whether profiling is enabled
         * @param pinNanos      total time spent pinning Java input buffers, in nanoseconds
         * @param buildNanos    total time spent building OpenZL inputs, in nanoseconds
         * @param compressNanos total time spent compressing, in nanoseconds
         * @param outNanos      total time spent writing results into output buffers, in nanoseconds
         * @param calls         number of profiled calls
         */
        public StructuredProfileSnapshot(boolean enabled,
                                         long pinNanos,
                                         long buildNanos,
                                         long compressNanos,
                                         long outNanos,
                                         long calls) {
            this.enabled = enabled;
            this.pinNanos = pinNanos;
            this.buildNanos = buildNanos;
            this.compressNanos = compressNanos;
            this.outNanos = outNanos;
            this.calls = calls;
        }

        /**
         * Returns whether structured profiling is enabled.
         *
         * @return {@code true} if counters are being collected
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * Returns the total time spent pinning Java input buffers.
         *
         * @return cumulative pinning time in nanoseconds
         */
        public long pinNanos() {
            return pinNanos;
        }

        /**
         * Returns the total time spent building OpenZL inputs.
         *
         * @return cumulative build time in nanoseconds
         */
        public long buildNanos() {
            return buildNanos;
        }

        /**
         * Returns the total time spent compressing.
         *
         * @return cumulative compression time in nanoseconds
         */
        public long compressNanos() {
            return compressNanos;
        }

        /**
         * Returns the total time spent writing results into output buffers.
         *
         * @return cumulative output time in nanoseconds
         */
        public long outNanos() {
            return outNanos;
        }

        /**
         * Returns the number of profiled calls.
         *
         * @return number of calls included in the totals
         */
        public long calls() {
            return calls;
        }
    }

    private static void registerSchemaInternal(
            DescriptorProtos.FileDescriptorSet descriptorSet,
            Set<String> fileNames) {
        if (descriptorSet == null || descriptorSet.getFileCount() == 0 || fileNames.isEmpty()) {
            return;
        }
        synchronized (REGISTERED_FILE_NAMES) {
            if (REGISTERED_FILE_NAMES.containsAll(fileNames)) {
                return;
            }
            OpenZLNative.load();
            registerSchemaNative(descriptorSet.toByteArray());
            REGISTERED_FILE_NAMES.addAll(fileNames);
        }
    }

    private static native byte[] convertNative(byte[] payload,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);
    private static native byte[] convertSliceNative(byte[] payload,
            int offset,
            int length,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);

    private static native byte[] convertDirectNative(ByteBuffer payload,
            int length,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);

    private static native int convertDirectIntoNative(ByteBuffer payload,
            int length,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType,
            ByteBuffer output,
            int outputPosition,
            int outputLength);

    private static native long[] directIntoProfileNative();
    private static native long[] structuredProfileNative();

    private static native byte[][] trainNative(byte[][] samples,
            int inputProtocol,
            int maxTimeSecs,
            int threads,
            int numSamples,
            boolean pareto,
            String messageType);

    private static native void configureTrainingNative(String messageType, int minSamples);

    private static native void registerSchemaNative(byte[] descriptorSet);

    private static native String graphJsonNative(String messageType);
    private static native String graphJsonFromCompressorNative(byte[] compressorBytes);

    private static native String graphDetailJsonNative(String messageType);
}
