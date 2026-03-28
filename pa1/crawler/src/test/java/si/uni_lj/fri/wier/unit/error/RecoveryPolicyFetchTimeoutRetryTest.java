package si.uni_lj.fri.wier.unit.error;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.RecoveryPolicy;

/**
 * TS-12: lease-margin politeness abort uses {@link CrawlerErrorCategory#FETCH_TIMEOUT}; ensure it stays retryable
 * like other fetch timeouts.
 */
class RecoveryPolicyFetchTimeoutRetryTest {

    @Test
    void fetchTimeout_isRetryable() {
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.FETCH_TIMEOUT));
    }
}
