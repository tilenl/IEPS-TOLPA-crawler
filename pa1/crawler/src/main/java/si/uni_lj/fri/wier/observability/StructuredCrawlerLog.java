/*
 * Shared TS-15 key-value log fragments for SLF4J (no JSON encoder): keeps field names aligned across components.
 *
 * Callers: observability-sensitive paths; prefer these constants in message patterns to ease log grepping.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.observability;

/**
 * Normative {@code event=} names and message pattern snippets for structured operational logs (TS-15).
 */
public final class StructuredCrawlerLog {

    public static final String EVENT_PROCESSING_FAILURE = "PROCESSING_FAILURE";
    /** Emitted once per crawl shutdown (see {@link RunSummaryReporter}). */
    public static final String EVENT_CRAWLER_RUN_SUMMARY = "CRAWLER_RUN_SUMMARY";
    /**
     * Durable queue transitions (TS-15): claim, lease recovery batches, and related state changes. Use {@code
     * transition=} to distinguish subtypes.
     */
    public static final String EVENT_QUEUE_STATE_TRANSITION = "QUEUE_STATE_TRANSITION";

    private StructuredCrawlerLog() {}
}
