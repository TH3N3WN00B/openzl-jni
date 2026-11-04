package io.github.hybledav;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Small helper that hands out reusable direct {@link ByteBuffer}s.
 * <p>
 * Compress/decompress calls are most efficient when users provide direct
 * buffers. This manager keeps a per-thread cache so callers can obtain
 * a buffer of at least the desired capacity without constantly allocating.
 */
public final class OpenZLBufferManager implements AutoCloseable {
    private final ThreadLocal<PoolState> pool;
    private final int alignment;
    private final int minimumCapacity;

    private OpenZLBufferManager(int minimumCapacity, int alignment) {
        this.minimumCapacity = Math.max(1, minimumCapacity);
        this.alignment = Math.max(1, alignment);
        this.pool = ThreadLocal.withInitial(PoolState::new);
    }

    /**
     * Starts configuring a manager (default minimum capacity 64 KiB, alignment 64 bytes).
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Acquire a direct {@link ByteBuffer} with the given minimum capacity.
     * The buffer is cleared before returning.
     *
     * @param minCapacity smallest capacity the caller needs, in bytes
     * @return a cleared direct buffer from this thread's pool, or a newly allocated one
     */
    public ByteBuffer acquire(int minCapacity) {
        return pool.get().borrow(minCapacity);
    }

    /**
     * Acquire a buffer large enough for compressing an input of {@code inputSize} bytes.
     *
     * @param inputSize size of the data that will be compressed, in bytes
     * @return a cleared direct buffer of at least {@link OpenZLCompressor#maxCompressedSize(int)} bytes
     * @throws IllegalArgumentException if {@code inputSize} is negative or the bound
     *         exceeds the maximum ByteBuffer size
     */
    public ByteBuffer acquireForCompression(int inputSize) {
        if (inputSize < 0) {
            throw new IllegalArgumentException("inputSize must be non-negative");
        }
        long required = OpenZLCompressor.maxCompressedSize(inputSize);
        return acquire(checkedCapacity(required));
    }

    /**
     * Acquire a buffer large enough to hold a payload with {@code decompressedSize} bytes.
     *
     * @param decompressedSize size of the decompressed payload, in bytes
     * @return a cleared direct buffer of at least {@code decompressedSize} bytes
     * @throws IllegalArgumentException if {@code decompressedSize} is negative or exceeds
     *         the maximum ByteBuffer size
     */
    public ByteBuffer acquireForDecompression(long decompressedSize) {
        if (decompressedSize < 0) {
            throw new IllegalArgumentException("decompressedSize must be non-negative");
        }
        return acquire(checkedCapacity(decompressedSize));
    }

    /**
     * Release the buffer so it can be reused by subsequent {@link #acquire(int)} calls.
     * Buffers not originally allocated by this manager are ignored.
     *
     * @param buffer a buffer obtained from this manager on the calling thread
     */
    public void release(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        pool.get().release(buffer);
    }

    /**
     * Drops the buffers pooled for the calling thread. Pools of other threads are released
     * when those threads end.
     */
    @Override
    public void close() {
        PoolState state = pool.get();
        state.clear();
        pool.remove();
    }

    private int roundedCapacity(int requested) {
        long rounded = ((long) requested + alignment - 1) / alignment * alignment;
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested capacity too large: " + requested);
        }
        return (int) rounded;
    }

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Required capacity cannot be negative: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    private static ByteBuffer allocateDirect(int capacity) {
        return ByteBuffer.allocateDirect(capacity);
    }

    private final class PoolState {
        private final List<ArrayDeque<ByteBuffer>> buckets = new ArrayList<>();

        private PoolState() {
            // Pre-create a few buckets for common sizes. We use powers of two starting from
            // the rounded minimum capacity, capped at 16 buckets (~order of gigabytes).
            int bucketCapacity = roundedCapacity(minimumCapacity);
            for (int i = 0; i < 16 && bucketCapacity > 0; ++i) {
                buckets.add(new ArrayDeque<>());
                if (bucketCapacity >= Integer.MAX_VALUE / 2) {
                    break;
                }
                bucketCapacity = Math.min(Integer.MAX_VALUE, bucketCapacity << 1);
            }
        }

        ByteBuffer borrow(int minCapacity) {
            int required = Math.max(minCapacity, minimumCapacity);
            int capacity = roundedCapacity(required);
            int bucketIndex = bucketIndex(capacity);
            ByteBuffer buffer = pollFromBucket(bucketIndex, capacity);
            if (buffer == null) {
                buffer = allocateDirect(capacity);
            }
            buffer.clear();
            return buffer;
        }

        void release(ByteBuffer buffer) {
            if (buffer == null || !buffer.isDirect()) {
                return;
            }
            buffer.clear();
            int bucketIndex = bucketIndex(buffer.capacity());
            if (bucketIndex < buckets.size()) {
                buckets.get(bucketIndex).addLast(buffer);
            }
        }

        void clear() {
            for (ArrayDeque<ByteBuffer> bucket : buckets) {
                bucket.clear();
            }
        }

        private ByteBuffer pollFromBucket(int bucketIndex, int capacity) {
            ArrayDeque<ByteBuffer> bucket = buckets.get(bucketIndex);
            for (java.util.Iterator<ByteBuffer> it = bucket.iterator(); it.hasNext();) {
                ByteBuffer candidate = it.next();
                if (candidate.capacity() >= capacity) {
                    it.remove();
                    return candidate;
                }
            }
            for (int i = bucketIndex + 1; i < buckets.size(); ++i) {
                ArrayDeque<ByteBuffer> largerBucket = buckets.get(i);
                if (!largerBucket.isEmpty()) {
                    return largerBucket.pollFirst();
                }
            }
            return null;
        }

        private int bucketIndex(int capacity) {
            int rounded = roundedCapacity(capacity);
            int base = roundedCapacity(minimumCapacity);
            int index = 0;
            while (base < rounded && index + 1 < buckets.size()) {
                base = Math.min(Integer.MAX_VALUE, base << 1);
                index++;
            }
            return index;
        }
    }

    /** Configures an {@link OpenZLBufferManager}. */
    public static final class Builder {
        private int minimumCapacity = 64 * 1024;
        private int alignment = 64;

        private Builder() {}

        /**
         * Smallest capacity of any buffer the manager allocates.
         *
         * @param value capacity in bytes (values below 1 are treated as 1)
         * @return this builder
         */
        public Builder minimumCapacity(int value) {
            this.minimumCapacity = value;
            return this;
        }

        /**
         * Capacities are rounded up to a multiple of this value.
         *
         * @param value alignment in bytes (values below 1 are treated as 1)
         * @return this builder
         */
        public Builder alignment(int value) {
            this.alignment = value;
            return this;
        }

        /**
         * Creates the manager.
         *
         * @return a new buffer manager
         */
        public OpenZLBufferManager build() {
            return new OpenZLBufferManager(minimumCapacity, alignment);
        }
    }
}
