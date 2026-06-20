package com.paiagent.service.document;

/**
 * Configuration options for the data cleaning pipeline.
 *
 * <p>Each boolean flag controls whether a cleaning step is executed.
 * When all defaults are used, all steps are enabled with sensible thresholds.
 */
public record CleaningOptions(
        /** Enable HTML tag stripping */
        boolean htmlStrip,
        /** Enable paragraph deduplication */
        boolean dedup,
        /** SimHash similarity threshold for dedup (0.0-1.0) */
        double dedupThreshold,
        /** Enable quality/noise filtering */
        boolean filter,
        /** Minimum character length for a segment to keep */
        int minLength,
        /** Enable PII redaction */
        boolean piiRedact
) {
    /** Sensible defaults: all steps enabled. */
    public static CleaningOptions defaults() {
        return new CleaningOptions(true, true, 0.85, true, 50, true);
    }

    /** All cleaning disabled — pass-through only (normalize still applied). */
    public static CleaningOptions none() {
        return new CleaningOptions(false, false, 0.85, false, 50, false);
    }

    /** Only HTML strip + PII redact, no dedup or quality filter. */
    public static CleaningOptions htmlAndPiiOnly() {
        return new CleaningOptions(true, false, 0.85, false, 50, true);
    }
}
