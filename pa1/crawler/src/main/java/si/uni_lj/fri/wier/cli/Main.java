/*
 * CLI entry: TS-13 precedence (classpath base + profile + env + CLI), then validate, schema, lease recovery.
 *
 * Calls: ConfigurationBootstrap, RuntimeConfig, PreferentialCrawler, ClaimService.
 *
 * Created: 2026-03. Major revisions: TS-13 args/env/profile bootstrap.
 */

package si.uni_lj.fri.wier.cli;

import java.time.Duration;
import java.util.Properties;
import org.postgresql.ds.PGSimpleDataSource;
import si.uni_lj.fri.wier.app.PreferentialCrawler;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap.CliHelpRequestedException;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.queue.claim.ClaimService;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.SchemaVersionValidator;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Bootstrap: {@link ConfigurationBootstrap} → {@link RuntimeConfig#validate()} → DB → startup lease recovery →
 * effective config log. CLI: {@code --n-crawlers N}, {@code -h} / {@code --help}.
 */
public final class Main {

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
     * schema check, startup lease recovery (TS-07), then preflight logging.
     */
    static void run(String[] args, int availableCpuCores) throws Exception {
        Properties props =
                ConfigurationBootstrap.resolveEffectiveProperties(Main.class.getClassLoader(), args);
        RuntimeConfig config = RuntimeConfig.fromProperties(props, availableCpuCores);
        config.validate();
        PGSimpleDataSource dataSource = dataSource(config);
        new SchemaVersionValidator(dataSource).validateExpectedVersion(config.dbExpectedSchemaVersion());
        PageRepository pageRepository =
                new PageRepository(
                        dataSource,
                        config.recoveryPathMaxAttempts(),
                        Duration.ofMillis(config.recoveryPathBaseBackoffMs()),
                        config.retryJitterMs());
        FrontierStore frontierStore = new FrontierStore(pageRepository, new CrawlerMetrics());
        ClaimService.runStartupLeaseRecovery(
                frontierStore,
                config.frontierStartupLeaseRecoveryBatchSize(),
                "startup stale lease recovery",
                "startup");
        new PreferentialCrawler(config).preflightAndLogEffectiveConfig();
    }

    private static PGSimpleDataSource dataSource(RuntimeConfig config) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(config.dbUrl());
        dataSource.setUser(config.dbUser());
        dataSource.setPassword(config.dbPassword());
        return dataSource;
    }
}
