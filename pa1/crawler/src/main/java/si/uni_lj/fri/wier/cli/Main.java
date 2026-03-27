package si.uni_lj.fri.wier.cli;

import java.io.InputStream;
import java.util.Properties;
import org.postgresql.ds.PGSimpleDataSource;
import si.uni_lj.fri.wier.app.PreferentialCrawler;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.queue.claim.ClaimService;
import si.uni_lj.fri.wier.storage.postgres.SchemaVersionValidator;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;

public final class Main {

    public static void main(String[] args) throws Exception {
        Properties props = loadClasspathProperties("application.properties");
        RuntimeConfig config = RuntimeConfig.fromProperties(props, Runtime.getRuntime().availableProcessors());
        PGSimpleDataSource dataSource = dataSource(config);
        new SchemaVersionValidator(dataSource).validateExpectedVersion(config.dbExpectedSchemaVersion());
        PageRepository pageRepository = new PageRepository(dataSource);
        FrontierStore frontierStore = new FrontierStore(pageRepository);
        ClaimService.runStartupLeaseRecovery(
                frontierStore, config.frontierStartupLeaseRecoveryBatchSize(), "startup stale lease recovery");
        new PreferentialCrawler(config).preflightAndLogEffectiveConfig();
    }

    private static Properties loadClasspathProperties(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + name);
            }
            p.load(in);
        }
        return p;
    }

    private static PGSimpleDataSource dataSource(RuntimeConfig config) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(config.dbUrl());
        dataSource.setUser(config.dbUser());
        dataSource.setPassword(config.dbPassword());
        return dataSource;
    }
}
