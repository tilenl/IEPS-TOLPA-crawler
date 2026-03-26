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

    void reschedule(long pageId, Instant nextAttemptAt, String reason);
}
