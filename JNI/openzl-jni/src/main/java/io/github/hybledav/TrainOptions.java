package io.github.hybledav;

/**
 * Options for {@link OpenZLCompressor#train} and {@link OpenZLCompressor#trainFromDirectory}.
 * Fields are public and mutable; a value of {@code 0} means "let OpenZL decide".
 */
public final class TrainOptions {
    /** Upper bound on training time in seconds ({@code 0} = no explicit limit). */
    public int maxTimeSecs = 1; // small default for tests

    /** Number of worker threads used by the trainer ({@code 0} = OpenZL's default). */
    public int threads = 1;

    /** Number of samples to train on ({@code 0} = use all of them). */
    public int numSamples = 0;

    /**
     * When {@code true}, return the Pareto frontier of candidate compressors (trading
     * speed for ratio) instead of a single best candidate.
     */
    public boolean paretoFrontier = false;

    /** Creates options with the defaults above. */
    public TrainOptions() {}
}
