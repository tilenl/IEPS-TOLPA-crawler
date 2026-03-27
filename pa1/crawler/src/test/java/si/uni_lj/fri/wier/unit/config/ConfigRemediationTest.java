package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.ConfigRemediation;

class ConfigRemediationTest {

    @Test
    void budgetTotalPagesDropped_matchesTs13KeyAndHint() {
        ConfigRemediation.Remediation r = ConfigRemediation.budgetTotalPagesDropped();
        assertEquals("crawler.budget.maxTotalPages", r.configKey());
        assertTrue(r.remediationHint().contains("crawler.budget.maxTotalPages"));
        assertTrue(r.remediationHint().toLowerCase().contains("increase"));
    }

    @Test
    void frontierHighWatermarkDeferred_matchesTs13Key() {
        ConfigRemediation.Remediation r = ConfigRemediation.frontierHighWatermarkDeferred();
        assertEquals("crawler.budget.maxFrontierRows", r.configKey());
        assertTrue(r.remediationHint().contains("crawler.budget.maxFrontierRows"));
    }

    @Test
    void schemaVersionMismatch_includesExpectedKey() {
        ConfigRemediation.Remediation r = ConfigRemediation.schemaVersionMismatch();
        assertEquals("crawler.db.expectedSchemaVersion", r.configKey());
    }

    @Test
    void redirectHopLimitExceeded_matchesTs13Key() {
        ConfigRemediation.Remediation r = ConfigRemediation.redirectHopLimitExceeded();
        assertEquals("crawler.fetch.maxRedirects", r.configKey());
        assertTrue(r.remediationHint().contains("crawler.fetch.maxRedirects"));
    }
}
