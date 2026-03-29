/*
 * Application shell: TS-13 preflight logging and TS-02 crawl lifecycle (seed bootstrap + scheduler).
 *
 * Callers: Main. Owned by TS-13 / TS-02.
 *
 * Created: 2026-03. Major revisions: TS-02 runCrawlToCompletion, seed bootstrap when page table empty.
 */

package si.uni_lj.fri.wier.app;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.CrawlScopes;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.RelevanceScorer;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.downloader.extract.HtmlParser;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;
import si.uni_lj.fri.wier.downloader.fetch.HttpFetcher;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;
import si.uni_lj.fri.wier.downloader.worker.WorkerLoop;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.error.RecoveryPathExecutor;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.observability.SeedBootstrapStats;
import si.uni_lj.fri.wier.scheduler.VirtualThreadCrawlerScheduler;
import si.uni_lj.fri.wier.scheduler.WorkerLoopFactory;
import si.uni_lj.fri.wier.storage.frontier.ContractFrontier;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.PostgresStorage;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Preflight logging for merged runtime configuration (TS-13) and TS-02 worker orchestration entrypoint.
 */
public final class PreferentialCrawler {

    private static final Logger log = LoggerFactory.getLogger(PreferentialCrawler.class);

    private final RuntimeConfig config;

    public PreferentialCrawler(RuntimeConfig config) {
        this.config = config;
    }

    /**
     * Logs effective config without secrets (TS-13). Callers must invoke {@link RuntimeConfig#validate()}
     * before any database access that depends on validated bounds; {@link si.uni_lj.fri.wier.cli.Main} does so
     * before constructing the datasource and startup lease recovery.
     */
    public void preflightAndLogEffectiveConfig() {
        log.info(
                "effectiveConfig workersFrontierDb nCrawlers={} frontierPollMs={} frontierLeaseSeconds={}"
                        + " frontierLeaseRecoveryBatchSize={} frontierStartupLeaseRecoveryBatchSize={}"
                        + " frontierTerminationGraceMs={} dbPoolSize={} expectedSchemaVersion={}",
                config.nCrawlers(),
                config.frontierPollMs(),
                config.frontierLeaseSeconds(),
                config.frontierLeaseRecoveryBatchSize(),
                config.frontierStartupLeaseRecoveryBatchSize(),
                config.frontierTerminationGraceMs(),
                config.dbPoolSize(),
                config.dbExpectedSchemaVersion());
        log.info(
                "effectiveConfig fetchRateLimit connectTimeoutMs={} readTimeoutMs={} maxHeadlessSessions={}"
                        + " headlessAcquireTimeoutMs={} headlessCircuitOpenThreshold={} maxRedirects={}"
                        + " rateLimitMinDelayMs={} rateLimitMaxBackoffMs={} jdbcUrl={}",
                config.fetchConnectTimeoutMs(),
                config.fetchReadTimeoutMs(),
                config.fetchMaxHeadlessSessions(),
                config.fetchHeadlessAcquireTimeoutMs(),
                config.fetchHeadlessCircuitOpenThreshold(),
                config.fetchMaxRedirects(),
                config.rateLimitMinDelayMs(),
                config.rateLimitMaxBackoffMs(),
                safeJdbcUrlForLog(config.dbUrl()));
        log.info(
                "effectiveConfig retryBudget jitterMs={} recoveryPathMaxAttempts={} recoveryPathBaseBackoffMs={}"
                        + " retryFetchTimeout={} retryFetchOverload={} retryFetchCapacity={} retryDbTransient={}"
                        + " budgetMaxTotalPages={} budgetMaxFrontierRows={} seedUrlCount={} crawlScope={}",
                config.retryJitterMs(),
                config.recoveryPathMaxAttempts(),
                config.recoveryPathBaseBackoffMs(),
                config.retryMaxAttemptsFetchTimeout(),
                config.retryMaxAttemptsFetchOverload(),
                config.retryMaxAttemptsFetchCapacity(),
                config.retryMaxAttemptsDbTransient(),
                config.budgetMaxTotalPages(),
                config.budgetMaxFrontierRows(),
                config.seedUrls().size(),
                config.crawlScope());
        log.info(
                "effectiveConfig robotsBucketsHealthScoring robotsCacheTtlHours={} robotsCacheMaxEntries={}"
                        + " temporaryDenyMaxMinutes={} temporaryDenyRetryMinutes={} bucketsCacheTtlHours={}"
                        + " bucketsCacheMaxEntries={} healthHeartbeatIntervalMs={} scoringKeywordConfigPath={}"
                        + " scoringPrimaryWeight={} scoringSecondaryWeight={} scoringMaxOccurrencesPerKeyword={}"
                        + " scoringSeedRelevanceScore={}",
                config.robotsCacheTtlHours(),
                config.robotsCacheMaxEntries(),
                config.robotsTemporaryDenyMaxMinutes(),
                config.robotsTemporaryDenyRetryMinutes(),
                config.bucketsCacheTtlHours(),
                config.bucketsCacheMaxEntries(),
                config.healthHeartbeatIntervalMs(),
                config.scoringKeywordConfig(),
                config.scoringPrimaryWeight(),
                config.scoringSecondaryWeight(),
                config.scoringMaxOccurrencesPerKeyword(),
                config.scoringSeedRelevanceScore());
    }

    /**
     * Strips user/password from JDBC URL for logs (TS-13); falls back to {@code [redacted]} if parsing fails.
     */
    public static String safeJdbcUrlForLog(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        // postgresql://user:pass@host:port/db — hide credentials
        int idx = jdbcUrl.indexOf("://");
        if (idx < 0) {
            return "[redacted]";
        }
        String rest = jdbcUrl.substring(idx + 3);
        int at = rest.lastIndexOf('@');
        if (at > 0) {
            rest = rest.substring(at + 1);
        }
        return "jdbc:postgresql://" + rest;
    }

    public RuntimeConfig config() {
        return config;
    }

    /**
     * Inserts configured seed URLs as {@code FRONTIER} rows when {@code crawldb.page} is empty (TS-02); otherwise
     * returns a skip snapshot. Each seed is canonicalized (TS-05) and inserted with {@code relevance_score} from
     * {@code crawler.scoring.seedRelevanceScore} (validated at startup to exceed any possible keyword-based score).
     *
     * <p>Seeds are not scored with {@link KeywordRelevanceScorer}: the operator explicitly chose them. We do not
     * HTTP-fetch seed URLs during bootstrap (robots crawl-delay / politeness would slow startup).
     */
    public SeedBootstrapStats bootstrapSeedsIfEmpty(PageRepository pageRepository, Storage storage) throws IOException {
        int configuredNonEmpty = 0;
        for (String raw : config.seedUrls()) {
            if (raw != null && !raw.trim().isEmpty()) {
                configuredNonEmpty++;
            }
        }
        if (pageRepository.countPagesTotal() > 0) {
            log.info("seedBootstrap skipped: crawldb.page already has rows");
            return new SeedBootstrapStats(configuredNonEmpty, 0, 0, true);
        }
        UrlCanonicalizer canonicalizer = new UrlCanonicalizer();
        int inserted = 0;
        int rejected = 0;
        for (String raw : config.seedUrls()) {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            CanonicalizationResult cr = canonicalizer.canonicalize(trimmed, null);
            if (!cr.accepted()) {
                rejected++;
                log.warn("seedBootstrap rejected seed={} reason={}", trimmed, cr.reasonCode());
                continue;
            }
            String canonical = cr.canonicalUrl();
            String domain = HostKeys.githubSiteRegistryKey(HostKeys.domainKey(canonical));
            long siteId =
                    storage.ensureSite(domain)
                            .orElseThrow(
                                    () -> new IllegalStateException("ensureSite returned empty for seed domain=" + domain));
            double score = config.scoringSeedRelevanceScore();
            InsertFrontierResult ins = storage.insertFrontierIfAbsent(canonical, siteId, score);
            if (ins.inserted()) {
                inserted++;
                log.info("seedBootstrap inserted FRONTIER url={} siteId={} score={}", canonical, siteId, score);
            } else {
                log.info("seedBootstrap seed already present url={} siteId={} pageId={}", canonical, siteId, ins.pageId());
            }
        }
        return new SeedBootstrapStats(configuredNonEmpty, inserted, rejected, false);
    }

    /**
     * Runs virtual-thread workers until the TS-02 termination supervisor observes an empty queue for the grace window,
     * then returns. Caller should {@code System.exit(0)} after heartbeat teardown when the crawl finished normally.
     *
     * @param shutdown shared with the JVM shutdown hook; when set, workers and supervisor stop cooperatively
     * @param schedulerRef set to the live {@link VirtualThreadCrawlerScheduler} before {@code await} so shutdown
     *     hooks can call {@link VirtualThreadCrawlerScheduler#stopGracefully(Duration)}
     * @param heartbeatWorkerIdSink first worker id for TS-15 heartbeat alignment (may be {@code null})
     * @param seedStatsSink when non-null, receives bootstrap stats immediately for shutdown-hook summaries
     * @param onSchedulerStarted invoked immediately after {@link VirtualThreadCrawlerScheduler#start(int)} so
     *     heartbeats wait for a real {@code workerId} (TS-15 {@code claimed_by} alignment)
     * @param frontierWakeRegistration set by the scheduler to {@link
     *     si.uni_lj.fri.wier.scheduler.DomainFrontierPump#scheduleDomainForWork}; the
     *     repository wake hook reads this reference so Stage A can notify after committed inserts
     */
    public SeedBootstrapStats runCrawlToCompletion(
            PageRepository pageRepository,
            PolitenessGate politenessGate,
            PostgresStorage storage,
            FrontierStore frontierStore,
            CrawlerMetrics metrics,
            AtomicBoolean shutdown,
            AtomicReference<VirtualThreadCrawlerScheduler> schedulerRef,
            AtomicReference<String> heartbeatWorkerIdSink,
            AtomicReference<SeedBootstrapStats> seedStatsSink,
            Runnable onSchedulerStarted,
            AtomicReference<Consumer<String>> frontierWakeRegistration)
            throws IOException, InterruptedException {
        SeedBootstrapStats seeds = bootstrapSeedsIfEmpty(pageRepository, storage);
        if (seedStatsSink != null) {
            seedStatsSink.set(seeds);
        }
        UrlCanonicalizer canonicalizer = new UrlCanonicalizer();
        RelevanceScorer scorer =
                new KeywordRelevanceScorer(
                        config.scoringKeywordConfig(),
                        config.scoringPrimaryWeight(),
                        config.scoringSecondaryWeight(),
                        config.scoringMaxOccurrencesPerKeyword());
        HtmlParser parser =
                new HtmlParser(
                        canonicalizer,
                        scorer,
                        domain -> {
                            String registry = HostKeys.githubSiteRegistryKey(domain);
                            return storage.ensureSite(registry)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "ensureSite missing for domain=" + registry));
                        },
                        CrawlScopes.persistencePredicate(config.crawlScope()));
        HttpFetcher fetcher = HttpFetcher.from(config, politenessGate, metrics);
        Clock clock = Clock.systemUTC();

        WorkerLoopFactory factory =
                (workerId, shut) -> {
                    Frontier frontier =
                            new ContractFrontier(
                                    frontierStore,
                                    workerId,
                                    Duration.ofSeconds(config.frontierLeaseSeconds()),
                                    config.frontierLeaseRecoveryBatchSize());
                    ProcessingFailureHandler failureHandler =
                            new ProcessingFailureHandler(
                                    frontier,
                                    storage,
                                    config,
                                    clock,
                                    LoggerFactory.getLogger(WorkerLoop.class),
                                    metrics);
                    RecoveryPathExecutor recoveryPath =
                            new RecoveryPathExecutor(config, LoggerFactory.getLogger("recovery-" + workerId));
                    return new WorkerLoop(
                            workerId,
                            frontier,
                            politenessGate,
                            fetcher,
                            parser,
                            storage,
                            failureHandler,
                            recoveryPath,
                            config,
                            clock,
                            shut,
                            metrics);
                };

        VirtualThreadCrawlerScheduler scheduler =
                new VirtualThreadCrawlerScheduler(
                        config,
                        pageRepository,
                        frontierStore,
                        politenessGate,
                        factory,
                        shutdown,
                        heartbeatWorkerIdSink,
                        frontierWakeRegistration,
                        metrics);
        schedulerRef.set(scheduler);
        scheduler.start(config.nCrawlers());
        if (onSchedulerStarted != null) {
            onSchedulerStarted.run();
        }
        scheduler.awaitRunCompletion(Duration.ofDays(7));
        return seeds;
    }
}
