package io.github.hybledav;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Built-in OpenZL compression profiles (the ones the {@code zli} CLI exposes with
 * {@code --profile}). A profile builds a compressor graph tuned for one kind of input;
 * use it with {@link OpenZLCompressor#configureProfile(OpenZLProfile)} or by
 * {@link #profileName() name} with the static helpers of {@link OpenZLCompressor}.
 */
public enum OpenZLProfile {
    /** Serial data, i.e. raw bytes without known structure. */
    SERIAL("serial"),
    /** PyTorch models written by {@code torch.save()}; training is not supported. */
    PYTORCH("pytorch"),
    /** CSV text (comma separated by default). */
    CSV("csv"),
    /** Array of little-endian signed 16-bit integers. */
    LITTLE_ENDIAN_SIGNED_16("le-i16"),
    /** Array of little-endian unsigned 16-bit integers. */
    LITTLE_ENDIAN_UNSIGNED_16("le-u16"),
    /** Array of little-endian signed 32-bit integers. */
    LITTLE_ENDIAN_SIGNED_32("le-i32"),
    /** Array of little-endian unsigned 32-bit integers. */
    LITTLE_ENDIAN_UNSIGNED_32("le-u32"),
    /** Array of little-endian signed 64-bit integers. */
    LITTLE_ENDIAN_SIGNED_64("le-i64"),
    /** Array of little-endian unsigned 64-bit integers. */
    LITTLE_ENDIAN_UNSIGNED_64("le-u64"),
    /** Parquet files in the canonical layout (no compression, plain encoding). */
    PARQUET("parquet"),
    /**
     * Data described with the Simple Data Description Language; for a compiled
     * description use {@link OpenZLCompressor#configureSddl(byte[])}.
     */
    SDDL("sddl"),
    /** The SAO star-catalog format of the Silesia corpus. */
    SAO("sao");

    private final String profileName;

    OpenZLProfile(String profileName) {
        this.profileName = profileName;
    }

    /**
     * Name of the profile as known to OpenZL (e.g. {@code "serial"}).
     *
     * @return the OpenZL profile name
     */
    public String profileName() {
        return profileName;
    }

    private static final Map<String, OpenZLProfile> BY_NAME = Map.copyOf(
        Stream.of(values()).collect(Collectors.toMap(OpenZLProfile::profileName, p -> p)));

    /**
     * Looks a profile up by its OpenZL name.
     *
     * @param profileName OpenZL profile name, e.g. {@code "csv"}
     * @return the matching profile
     * @throws NullPointerException if {@code profileName} is null
     * @throws IllegalArgumentException if no profile has that name
     */
    public static OpenZLProfile fromName(String profileName) {
        Objects.requireNonNull(profileName, "profileName");
        OpenZLProfile profile = BY_NAME.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Unknown compression profile '%s'. Available profiles: %s",
                    profileName,
                    BY_NAME.keySet()));
        }
        return profile;
    }
}
