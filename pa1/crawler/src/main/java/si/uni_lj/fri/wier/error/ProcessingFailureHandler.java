package si.uni_lj.fri.wier.error;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import si.uni_lj.fri.wier.config.ConfigRemediation;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.observability.StructuredCrawlerLog;

/**
 * Applies TS-12 {@link RecoveryPolicy} to durable queue transitions via {@link Frontier} and {@link Storage}.
 *
 * <p>Worker orchestration (TS-02) should invoke this after classifying a failure so retries stay transactional
 * and diagnostics land on {@code crawldb.page}. Created: 2026-03.
 */
public final class ProcessingFailureHandler {

    private final Frontier frontier;
    private final Storage storage;
    private final RuntimeConfig config;
    private final Clock clock;
    private final Logger log;
    private final CrawlerMetrics metrics;

    public ProcessingFailureHandler(
            Frontier frontier,
            Storage storage,
            RuntimeConfig config,
            Clock clock,
            Logger log,
            CrawlerMetrics metrics) {
        this.frontier = Objects.requireNonNull(frontier, "frontier");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Logs the failure (TS-12 fields), records metrics, then reschedules or marks terminal ERROR.
     *
     * @param ctx failure context for the current lease (must still be {@code PROCESSING} in DB)
     */
    public void handleProcessingFailure(FailureContext ctx) {
        CrawlerErrorCategory cat = ctx.category();
        metrics.recordFailure(cat);
        RecoveryDecision decision =
                RecoveryPolicy.decide(cat, ctx.attemptCount(), ctx.parserRetryCount(), config, clock);
        if (log.isWarnEnabled()) {
            logProcessingFailure(ctx, cat, decision);
        }
        if (decision instanceof RecoveryDecision.Reschedule r) {
            metrics.recordRetryScheduled();
            frontier.reschedule(ctx.pageId(), r.nextAttemptAt(), cat.name(), ctx.message());
            return;
        }
        if (decision instanceof RecoveryDecision.Terminal) {
            metrics.recordTerminalFailure();
            storage.markPageAsError(ctx.pageId(), cat.name(), ctx.message());
        }
    }

    /**
     * Emits TS-15 key-value fields; adds {@code configKey} / {@code remediationHint} only when the failure is
     * terminal after retry budgets (parameter-linked per TS-13).
     */
    private void logProcessingFailure(FailureContext ctx, CrawlerErrorCategory cat, RecoveryDecision decision) {
        boolean exhausted =
                decision instanceof RecoveryDecision.Terminal && RecoveryPolicy.isRetryable(cat);
        String result =
                decision instanceof RecoveryDecision.Reschedule
                        ? "RESCHEDULED"
                        : (exhausted ? "TERMINAL_EXHAUSTED" : "TERMINAL");
        if (exhausted) {
            ConfigRemediation.Remediation rem = remediationForRetryExhausted(cat);
            log.warn(
                    "event={} result={} workerId={} category={} pageId={} url={} domain={} attemptCount={}"
                            + " parserRetryCount={} configKey={} remediationHint={} msg={}",
                    StructuredCrawlerLog.EVENT_PROCESSING_FAILURE,
                    result,
                    ctx.workerId(),
                    cat.name(),
                    ctx.pageId(),
                    ctx.canonicalUrl(),
                    ctx.domain(),
                    ctx.attemptCount(),
                    ctx.parserRetryCount(),
                    rem.configKey(),
                    rem.remediationHint(),
                    ctx.message(),
                    ctx.cause());
            return;
        }
        log.warn(
                "event={} result={} workerId={} category={} pageId={} url={} domain={} attemptCount={}"
                        + " parserRetryCount={} msg={}",
                StructuredCrawlerLog.EVENT_PROCESSING_FAILURE,
                result,
                ctx.workerId(),
                cat.name(),
                ctx.pageId(),
                ctx.canonicalUrl(),
                ctx.domain(),
                ctx.attemptCount(),
                ctx.parserRetryCount(),
                ctx.message(),
                ctx.cause());
    }

    private static ConfigRemediation.Remediation remediationForRetryExhausted(CrawlerErrorCategory cat) {
        return switch (cat) {
            case FETCH_TIMEOUT -> ConfigRemediation.fetchTimeoutExhausted();
            case FETCH_HTTP_OVERLOAD -> ConfigRemediation.fetchOverloadExhausted();
            case FETCH_CAPACITY_EXHAUSTED -> ConfigRemediation.fetchCapacityExhausted();
            case DB_TRANSIENT -> ConfigRemediation.dbTransientExhausted();
            case ROBOTS_TRANSIENT -> ConfigRemediation.robotsTemporaryDeny();
            case PARSER_FAILURE -> ConfigRemediation.recoveryPathExhausted();
            default -> throw new IllegalStateException("exhausted branch for non-retryable category: " + cat);
        };
    }
}
