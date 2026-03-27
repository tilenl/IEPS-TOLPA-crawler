package si.uni_lj.fri.wier.contracts;

import java.time.Instant;
import java.util.Optional;

/**
 * Frontier claim and reschedule contract.
 *
 * <p>Ownership rule: worker orchestration claims work through this interface, not through ad-hoc
 * repository calls.
 */
public interface Frontier {
    Optional<FrontierRow> claimNextFrontier();

    /**
     * Durable retry transition {@code PROCESSING → FRONTIER} with diagnostics (TS-10 / TS-12).
     *
     * @param pageId claimed page id
     * @param nextAttemptAt earliest eligibility for the next claim
     * @param errorCategory {@link si.uni_lj.fri.wier.error.CrawlerErrorCategory#name()} or compatible stable code
     * @param diagnosticMessage human-readable reason stored in {@code last_error_message}
     */
    void reschedule(long pageId, Instant nextAttemptAt, String errorCategory, String diagnosticMessage);
}
