package si.uni_lj.fri.wier.observability;

/**
 * TS-15 run-summary metadata for seed insertion vs skip (empty table bootstrap only).
 *
 * @param configuredNonEmpty number of non-blank seed URL entries in configuration
 * @param inserted FRONTIER rows inserted during this bootstrap attempt
 * @param rejected seeds rejected before insert (e.g. canonicalization failure)
 * @param skippedNonEmptyTable when {@code true}, {@code crawldb.page} already had rows so bootstrap did not run
 */
public record SeedBootstrapStats(
        int configuredNonEmpty, int inserted, int rejected, boolean skippedNonEmptyTable) {}
