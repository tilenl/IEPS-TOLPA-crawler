package si.uni_lj.fri.wier.error;

import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

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
        if (log.isWarnEnabled()) {
            log.warn(
                    "processingFailure category={} pageId={} url={} domain={} attemptCount={} parserRetryCount={} msg={}",
                    cat.name(),
                    ctx.pageId(),
                    ctx.canonicalUrl(),
                    ctx.domain(),
                    ctx.attemptCount(),
                    ctx.parserRetryCount(),
                    ctx.message(),
                    ctx.cause());
        }
        RecoveryDecision decision =
                RecoveryPolicy.decide(cat, ctx.attemptCount(), ctx.parserRetryCount(), config, clock);
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
}
