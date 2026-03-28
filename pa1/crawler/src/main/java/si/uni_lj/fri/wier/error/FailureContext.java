package si.uni_lj.fri.wier.error;

/**
 * Inputs for TS-12 failure handling on a leased page (after {@code claimNextFrontier}).
 *
 * @param pageId claimed page primary key
 * @param workerId lease owner ({@code claimed_by}) for TS-15 structured logs
 * @param canonicalUrl URL from the frontier row (logging / diagnostics)
 * @param domain site domain for structured logs (TS-12 observability)
 * @param attemptCount {@code attempt_count} from {@link si.uni_lj.fri.wier.contracts.FrontierRow}
 * @param parserRetryCount {@code parser_retry_count} from {@link si.uni_lj.fri.wier.contracts.FrontierRow}
 * @param category classified failure bucket
 * @param message human-readable failure description for storage and logs
 * @param cause optional underlying exception for stack traces (may be {@code null})
 */
public record FailureContext(
        long pageId,
        String workerId,
        String canonicalUrl,
        String domain,
        int attemptCount,
        int parserRetryCount,
        CrawlerErrorCategory category,
        String message,
        Throwable cause) {

    public FailureContext {
        if (workerId == null) {
            workerId = "";
        }
        if (canonicalUrl == null) {
            canonicalUrl = "";
        }
        if (domain == null) {
            domain = "";
        }
        if (message == null) {
            message = "";
        }
    }
}
