package si.uni_lj.fri.wier.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;

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
                "effectiveConfig nCrawlers={} frontierPollMs={} budgetMaxTotalPages={} budgetMaxFrontierRows={}"
                        + " dbPoolSize={} fetchMaxHeadlessSessions={} expectedSchemaVersion={}",
                config.nCrawlers(),
                config.frontierPollMs(),
                config.budgetMaxTotalPages(),
                config.budgetMaxFrontierRows(),
                config.dbPoolSize(),
                config.fetchMaxHeadlessSessions(),
                config.dbExpectedSchemaVersion());
    }

    public RuntimeConfig config() {
        return config;
    }
}
