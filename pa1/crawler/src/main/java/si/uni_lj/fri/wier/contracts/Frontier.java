package si.uni_lj.fri.wier.contracts;

import java.time.Instant;
import java.util.Optional;

public interface Frontier {
    Optional<Contracts.FrontierRow> claimNextFrontier();

    void reschedule(long pageId, Instant nextAttemptAt, String reason);
}
