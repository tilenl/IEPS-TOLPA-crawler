/*
 * TS-02 worker loop: claim, rate-limit gate, robots, fetch, parse, persist (single {@code persistFetchOutcomeWithLinks}
 * per successful lease).
 *
 * Callers: {@link si.uni_lj.fri.wier.scheduler.VirtualThreadCrawlerScheduler} on virtual threads (TS-14).
 *
 * Created: 2026-03. Major revision: full TS-02 pipeline (replaces UnsupportedOperationException stub).
 */

package si.uni_lj.fri.wier.downloader.worker;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.Fetcher;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.Parser;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.contracts.Worker;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.FailureContext;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.error.RecoveryPathExecutor;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;

/**
 * One worker's claim-driven pipeline. Uses {@link ProcessingFailureHandler} for TS-12 transitions; wraps durable
 * writes with {@link RecoveryPathExecutor} (TS-02 recovery-path retries).
 */
public final class WorkerLoop implements Worker {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    private final String workerId;
    private final Frontier frontier;
    private final RobotsTxtCache robotsTxtCache;
    private final Fetcher fetcher;
    private final Parser parser;
    private final Storage storage;
    private final ProcessingFailureHandler failureHandler;
    private final RecoveryPathExecutor recoveryPath;
    private final RuntimeConfig config;
    private final Clock clock;
    private final AtomicBoolean shutdown;
    /** Optional TS-15 metrics (rate-limit deferrals without sleeping in this path). */
    private final CrawlerMetrics observabilityMetrics;

    public WorkerLoop(
            String workerId,
            Frontier frontier,
            RobotsTxtCache robotsTxtCache,
            Fetcher fetcher,
            Parser parser,
            Storage storage,
            ProcessingFailureHandler failureHandler,
            RecoveryPathExecutor recoveryPath,
            RuntimeConfig config,
            Clock clock,
            AtomicBoolean shutdown) {
        this(
                workerId,
                frontier,
                robotsTxtCache,
                fetcher,
                parser,
                storage,
                failureHandler,
                recoveryPath,
                config,
                clock,
                shutdown,
                null);
    }

    public WorkerLoop(
            String workerId,
            Frontier frontier,
            RobotsTxtCache robotsTxtCache,
            Fetcher fetcher,
            Parser parser,
            Storage storage,
            ProcessingFailureHandler failureHandler,
            RecoveryPathExecutor recoveryPath,
            RuntimeConfig config,
            Clock clock,
            AtomicBoolean shutdown,
            CrawlerMetrics observabilityMetrics) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.frontier = Objects.requireNonNull(frontier, "frontier");
        this.robotsTxtCache = Objects.requireNonNull(robotsTxtCache, "robotsTxtCache");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.recoveryPath = Objects.requireNonNull(recoveryPath, "recoveryPath");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.observabilityMetrics = observabilityMetrics;
    }

    /** Delegates to {@link SchedulingPolicy#newWorkerId()} (TS-14). */
    public static String generateWorkerId() {
        return SchedulingPolicy.newWorkerId();
    }

    @Override
    public void runLoop() {
        while (!shutdown.get()) {
            Optional<FrontierRow> rowOpt = frontier.claimNextFrontier();
            if (rowOpt.isEmpty()) {
                if (shutdown.get()) {
                    return;
                }
                try {
                    Thread.sleep(Math.max(1, config.frontierPollMs()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            processClaimedRow(rowOpt.get());
        }
    }

    private void processClaimedRow(FrontierRow row) {
        String url = row.url();
        String domain;
        try {
            domain = HostKeys.domainKey(url);
        } catch (RuntimeException e) {
            handleFailure(
                    row,
                    "",
                    CrawlerErrorCategory.INVALID_URL,
                    "cannot derive host: " + e.getMessage(),
                    e);
            return;
        }

        RateLimitDecision rl =
                robotsTxtCache instanceof RateLimiterRegistry reg
                        ? reg.tryAcquire(domain)
                        : RateLimitDecision.allowed();
        if (rl.delayed()) {
            if (observabilityMetrics != null) {
                observabilityMetrics.recordRateLimitWait(
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(rl.waitNs())));
            }
            Instant next = clock.instant().plusNanos(rl.waitNs());
            recoveryPath.runWithRetries(
                    workerId,
                    row.pageId(),
                    row.claimExpiresAt(),
                    CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                    () ->
                            frontier.reschedule(
                                    row.pageId(),
                                    next,
                                    CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                                    "rate_limited"));
            return;
        }

        try {
            robotsTxtCache.ensureLoaded(domain);
        } catch (RuntimeException e) {
            handleFailure(
                    row,
                    domain,
                    CrawlerErrorCategory.FETCH_HTTP_CLIENT,
                    "robots ensureLoaded: " + e.getMessage(),
                    e);
            return;
        }

        RobotDecision robot = robotsTxtCache.evaluate(url);
        if (robot.type() == RobotDecisionType.DISALLOWED) {
            recoveryPath.runWithRetries(
                    workerId,
                    row.pageId(),
                    row.claimExpiresAt(),
                    CrawlerErrorCategory.ROBOTS_DISALLOWED.name(),
                    () ->
                            storage.markPageAsError(
                                    row.pageId(),
                                    CrawlerErrorCategory.ROBOTS_DISALLOWED.name(),
                                    "Disallowed after robots refresh for claimed URL"));
            return;
        }
        if (robot.type() == RobotDecisionType.TEMPORARY_DENY) {
            recoveryPath.runWithRetries(
                    workerId,
                    row.pageId(),
                    row.claimExpiresAt(),
                    CrawlerErrorCategory.ROBOTS_TRANSIENT.name(),
                    () ->
                            frontier.reschedule(
                                    row.pageId(),
                                    robot.denyUntilOrDefault(),
                                    CrawlerErrorCategory.ROBOTS_TRANSIENT.name(),
                                    "temporary robots deny"));
            return;
        }

        FetchRequest request =
                new FetchRequest(url, workerId, row.claimExpiresAt(), true);
        FetchResult fetched;
        try {
            fetched = fetcher.fetch(request);
        } catch (FetchException e) {
            handleFailure(
                    row,
                    domain,
                    categoryFromFetch(e),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    e);
            return;
        } catch (RuntimeException e) {
            handleFailure(row, domain, CrawlerErrorCategory.FETCH_HTTP_CLIENT, e.getMessage(), e);
            return;
        }

        ParseResult parsed;
        try {
            String html = fetched.body() == null ? "" : fetched.body();
            parsed = parser.parse(url, html);
        } catch (RuntimeException e) {
            handleFailure(row, domain, CrawlerErrorCategory.PARSER_FAILURE, e.getMessage(), e);
            return;
        }

        ParseResult withSource = attachSourcePage(parsed, row.pageId());
        for (String d : distinctDiscoveryDomains(withSource)) {
            try {
                robotsTxtCache.ensureLoaded(d);
            } catch (RuntimeException e) {
                log.warn(
                        "ensureLoaded failed for discovered domain={} workerId={} pageId={} msg={}",
                        d,
                        workerId,
                        row.pageId(),
                        e.getMessage());
            }
        }

        try {
            storage.persistFetchOutcomeWithLinks(
                    FetchContext.fromClaimedRow(row), fetched, withSource, List.of());
        } catch (RuntimeException e) {
            CrawlerErrorCategory cat = classifyPersistFailure(e);
            handleFailure(row, domain, cat, e.getMessage(), e);
        }
    }

    private static ParseResult attachSourcePage(ParseResult parsed, long sourcePageId) {
        List<DiscoveredUrl> with =
                parsed.discoveredUrls().stream()
                        .map(
                                u ->
                                        new DiscoveredUrl(
                                                u.canonicalUrl(),
                                                u.siteId(),
                                                sourcePageId,
                                                u.anchorText(),
                                                u.contextText(),
                                                u.relevanceScore()))
                        .toList();
        return new ParseResult(with, parsed.extractedImages(), parsed.pageMetadata());
    }

    private static Set<String> distinctDiscoveryDomains(ParseResult parsed) {
        Set<String> domains = new HashSet<>();
        for (DiscoveredUrl u : parsed.discoveredUrls()) {
            try {
                domains.add(HostKeys.domainKey(u.canonicalUrl()));
            } catch (RuntimeException ignore) {
                // Malformed canonical URL: ingest layer will reject; skip robots preload.
            }
        }
        return domains;
    }

    private void handleFailure(
            FrontierRow row,
            String domain,
            CrawlerErrorCategory category,
            String message,
            Throwable cause) {
        FailureContext ctx =
                new FailureContext(
                        row.pageId(),
                        workerId,
                        row.url(),
                        domain,
                        row.attemptCount(),
                        row.parserRetryCount(),
                        category,
                        message == null ? "" : message,
                        cause);
        recoveryPath.runWithRetries(
                workerId,
                row.pageId(),
                row.claimExpiresAt(),
                category.name(),
                () -> failureHandler.handleProcessingFailure(ctx));
    }

    private static CrawlerErrorCategory categoryFromFetch(FetchException e) {
        try {
            return CrawlerErrorCategory.valueOf(e.category());
        } catch (IllegalArgumentException ex) {
            return CrawlerErrorCategory.FETCH_HTTP_CLIENT;
        }
    }

    private static CrawlerErrorCategory classifyPersistFailure(RuntimeException e) {
        Throwable c = e.getCause();
        while (c != null) {
            if (c instanceof SQLException) {
                return CrawlerErrorCategory.DB_TRANSIENT;
            }
            c = c.getCause();
        }
        return CrawlerErrorCategory.DB_CONSTRAINT;
    }
}
