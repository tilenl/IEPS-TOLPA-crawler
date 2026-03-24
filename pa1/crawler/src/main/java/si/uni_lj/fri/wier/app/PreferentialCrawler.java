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

    /** Startup preflight validation and effective config logging without secrets (TS-13). */
    public void preflightAndLogEffectiveConfig() {
        config.validate();
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
