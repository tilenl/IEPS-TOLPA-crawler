/*
 * CLI entry: TS-13 precedence (classpath base + profile + env + CLI), then validate, schema, lease recovery,
 * TS-02 crawl lifecycle (workers + termination), exit 0 on natural completion.
 *
 * Calls: ConfigurationBootstrap, RuntimeConfig, PreferentialCrawler, ClaimService, PolitenessGate, PageRepository.
 *
 * Created: 2026-03. Major revisions: TS-02 wiring, System.exit(0) after crawl completion; TS-15 run summary + heartbeat.
 */

package si.uni_lj.fri.wier.cli;

import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import si.uni_lj.fri.wier.app.PreferentialCrawler;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap.CliHelpRequestedException;
import si.uni_lj.fri.wier.config.CrawlScopes;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;
import si.uni_lj.fri.wier.observability.CrawlerHeartbeatScheduler;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.observability.RunSummaryReporter;
import si.uni_lj.fri.wier.observability.SeedBootstrapStats;
import si.uni_lj.fri.wier.queue.claim.ClaimService;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueCoordinator;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueService;
import si.uni_lj.fri.wier.scheduler.VirtualThreadCrawlerScheduler;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.CountingDataSource;
import si.uni_lj.fri.wier.storage.postgres.PostgresStorage;
import si.uni_lj.fri.wier.storage.postgres.SchemaVersionValidator;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;
import si.uni_lj.fri.wier.storage.postgres.repositories.SiteRepository;

/**
 * Bootstrap: {@link ConfigurationBootstrap} → {@link RuntimeConfig#validate()} → DB → startup lease recovery →
 * TS-02 crawl (virtual-thread workers + termination grace) → {@code System.exit(0)}. SIGINT/SIGTERM stops the
 * scheduler gracefully then closes the heartbeat. CLI: {@code --n-crawlers N}, {@code -h} / {@code --help}.
 */
public final class Main {

    /**
     * TS-02 shares one {@link PolitenessGate} for Stage A enqueue and Stage B fetch; retained for
     * {@link EnqueueCoordinator} static wiring until callers migrate.
     */
    static volatile PolitenessGate sharedPolitenessGate;

    /** Stage A enqueue entry point for components that still read this static. */
    static volatile EnqueueCoordinator sharedEnqueueCoordinator;

    public static void main(String[] args) {
        try {
            run(args, Runtime.getRuntime().availableProcessors());
        } catch (CliHelpRequestedException e) {
            System.out.println(ConfigurationBootstrap.usageLine());
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Ordered bootstrap: validate config before any DB access that depends on frontier settings (TS-13), then
     * schema check, startup lease recovery (TS-07), preflight logging, heartbeat, crawl to completion (TS-02).
     */
    static void run(String[] args, int availableCpuCores) throws Exception {
        Properties props =
                ConfigurationBootstrap.resolveEffectiveProperties(Main.class.getClassLoader(), args);
        RuntimeConfig config = RuntimeConfig.fromProperties(props, availableCpuCores);
        config.validate();
        PGSimpleDataSource rawDataSource = dataSource(config);
        CrawlerMetrics crawlMetrics = new CrawlerMetrics();
        crawlMetrics.setDbPoolCapacity(config.dbPoolSize());
        DataSource dataSource = CountingDataSource.wrap(rawDataSource, crawlMetrics);
        new SchemaVersionValidator(dataSource).validateExpectedVersion(config.dbExpectedSchemaVersion());
        AtomicReference<String> heartbeatWorkerIdRef = new AtomicReference<>();
        AtomicReference<SeedBootstrapStats> seedStatsForHook = new AtomicReference<>();
        AtomicBoolean runSummaryEmitted = new AtomicBoolean(false);

        EnqueueService enqueueService = new EnqueueService(crawlMetrics);
        SiteRepository siteRepository = new SiteRepository(dataSource);
        PolitenessGate politenessGate =
                new PolitenessGate(
                        config,
                        PolitenessGate.buildHttpClient(config),
                        null,
                        siteRepository,
                        Clock.systemUTC(),
                        crawlMetrics,
                        null);
        sharedPolitenessGate = politenessGate;
        AtomicReference<Consumer<String>> frontierWakeSink = new AtomicReference<>();
        PageRepository pageRepository =
                new PageRepository(
                        dataSource,
                        config.recoveryPathMaxAttempts(),
                        Duration.ofMillis(config.recoveryPathBaseBackoffMs()),
                        config.retryJitterMs(),
                        politenessGate,
                        config,
                        enqueueService,
                        crawlMetrics,
                        domain -> {
                            Consumer<String> sink = frontierWakeSink.get();
                            if (sink != null) {
                                sink.accept(domain);
                            }
                        });
        PostgresStorage postgresStorage = new PostgresStorage(pageRepository);
        sharedEnqueueCoordinator =
                new EnqueueCoordinator(
                        politenessGate, postgresStorage, CrawlScopes.persistencePredicate(config.crawlScope()));
        FrontierStore frontierStore = new FrontierStore(pageRepository, crawlMetrics);
        ClaimService.runStartupLeaseRecovery(
                frontierStore,
                config.frontierStartupLeaseRecoveryBatchSize(),
                "startup stale lease recovery",
                "startup");
        PreferentialCrawler app = new PreferentialCrawler(config);
        app.preflightAndLogEffectiveConfig();

        FrontierStore frontierForCrawl = new FrontierStore(pageRepository, crawlMetrics);
        AtomicBoolean shutdown = new AtomicBoolean(false);
        AtomicReference<VirtualThreadCrawlerScheduler> schedulerRef = new AtomicReference<>();

        CrawlerHeartbeatScheduler heartbeat =
                new CrawlerHeartbeatScheduler(
                        pageRepository,
                        config,
                        heartbeatWorkerIdRef::get,
                        politenessGate::refreshRobotsTemporaryDenyGauge,
                        crawlMetrics);

        Runnable emitRunSummaryOnce =
                () -> {
                    if (!runSummaryEmitted.compareAndSet(false, true)) {
                        return;
                    }
                    SeedBootstrapStats s = seedStatsForHook.get();
                    if (s == null) {
                        s = new SeedBootstrapStats(0, 0, 0, false);
                    }
                    RunSummaryReporter.emitRunSummary(pageRepository, crawlMetrics, s);
                };

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        VirtualThreadCrawlerScheduler s = schedulerRef.get();
                                        if (s != null) {
                                            s.stopGracefully(Duration.ofSeconds(30));
                                        }
                                    } finally {
                                        emitRunSummaryOnce.run();
                                        try {
                                            heartbeat.close();
                                        } catch (Exception ignored) {
                                            // best-effort
                                        }
                                    }
                                },
                                "crawler-shutdown"));

        SeedBootstrapStats seeds =
                app.runCrawlToCompletion(
                        pageRepository,
                        politenessGate,
                        postgresStorage,
                        frontierForCrawl,
                        crawlMetrics,
                        shutdown,
                        schedulerRef,
                        heartbeatWorkerIdRef,
                        seedStatsForHook,
                        heartbeat::start,
                        frontierWakeSink);
        seedStatsForHook.set(seeds);
        emitRunSummaryOnce.run();
        heartbeat.close();
        System.exit(0);
    }

    private static PGSimpleDataSource dataSource(RuntimeConfig config) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(config.dbUrl());
        dataSource.setUser(config.dbUser());
        dataSource.setPassword(config.dbPassword());
        return dataSource;
    }
}
