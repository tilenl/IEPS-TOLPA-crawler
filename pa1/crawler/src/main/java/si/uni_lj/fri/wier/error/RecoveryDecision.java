package si.uni_lj.fri.wier.error;

import java.time.Instant;

/**
 * Outcome of TS-12 recovery policy evaluation for a leased page row.
 *
 * <p>Callers translate {@link #Reschedule} into {@link si.uni_lj.fri.wier.contracts.Frontier#reschedule}
 * and {@link #Terminal} into {@link si.uni_lj.fri.wier.contracts.Storage#markPageAsError}.
 */
public sealed interface RecoveryDecision {

    /** Schedule another claim at {@code nextAttemptAt} with durable queue transition. */
    record Reschedule(Instant nextAttemptAt) implements RecoveryDecision {}

    /** No further retries; persist terminal ERROR for the current lease. */
    record Terminal() implements RecoveryDecision {}
}
