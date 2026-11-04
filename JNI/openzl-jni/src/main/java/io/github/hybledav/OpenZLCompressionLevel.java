package io.github.hybledav;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compression levels accepted by {@link OpenZLCompressor#setCompressionLevel}. Higher
 * levels spend more CPU time looking for a smaller output; OpenZL's default is
 * {@link #LEVEL_6}.
 */
public enum OpenZLCompressionLevel {
    /** Compression level 1. Fastest, lowest ratio. */
    LEVEL_1(1),
    /** Compression level 2. */
    LEVEL_2(2),
    /** Compression level 3. */
    LEVEL_3(3),
    /** Compression level 4. */
    LEVEL_4(4),
    /** Compression level 5. */
    LEVEL_5(5),
    /** Compression level 6. OpenZL's default. */
    LEVEL_6(6),
    /** Compression level 7. */
    LEVEL_7(7),
    /** Compression level 8. */
    LEVEL_8(8),
    /** Compression level 9. */
    LEVEL_9(9),
    /** Compression level 10. */
    LEVEL_10(10),
    /** Compression level 11. */
    LEVEL_11(11),
    /** Compression level 12. */
    LEVEL_12(12),
    /** Compression level 13. */
    LEVEL_13(13),
    /** Compression level 14. */
    LEVEL_14(14),
    /** Compression level 15. */
    LEVEL_15(15),
    /** Compression level 16. */
    LEVEL_16(16),
    /** Compression level 17. */
    LEVEL_17(17),
    /** Compression level 18. */
    LEVEL_18(18),
    /** Compression level 19. */
    LEVEL_19(19),
    /** Compression level 20. */
    LEVEL_20(20),
    /** Compression level 21. */
    LEVEL_21(21),
    /** Compression level 22. Slowest, highest ratio. */
    LEVEL_22(22);

    private final int level;

    OpenZLCompressionLevel(int level) {
        this.level = level;
    }

    /**
     * Numeric value passed to OpenZL ({@code ZL_CParam_compressionLevel}).
     *
     * @return the level, 1 to 22
     */
    public int level() {
        return level;
    }

    private static final Map<Integer, OpenZLCompressionLevel> BY_LEVEL = Map.copyOf(
            Stream.of(values()).collect(Collectors.toMap(OpenZLCompressionLevel::level, level -> level)));

    /**
     * Maps a numeric level to its constant.
     *
     * @param level numeric compression level
     * @return the matching constant
     * @throws IllegalArgumentException if the level is not between 1 and 22
     */
    public static OpenZLCompressionLevel fromLevel(int level) {
        OpenZLCompressionLevel mapped = BY_LEVEL.get(level);
        if (mapped == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Unknown compression level %d. Supported levels: %s",
                    level,
                    BY_LEVEL.keySet()));
        }
        return mapped;
    }
}