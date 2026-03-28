/*
 * Immutable snapshot of TS-06 robots fetch outcome for one domain, held in the Caffeine rules cache.
 *
 * Callers: PolitenessGate only.
 *
 * Created: 2026-03 for TS-06.
 */

package si.uni_lj.fri.wier.downloader.politeness;

import crawlercommons.robots.BaseRobotRules;
import java.time.Instant;
import java.util.Objects;

/**
 * Cached robots policy for a single domain after {@link PolitenessGate#ensureLoaded(String)}.
 *
 * <p>Transient HTTP failures (3xx/5xx final, hop errors) store {@link Kind#TRANSIENT_FAILURE} with
 * {@link #denyUntil} and {@link #consecutiveFailures}; path rules use allow-all until refresh succeeds so
 * {@link PolitenessGate#evaluate(String)} can return {@code TEMPORARY_DENY} without misclassifying as
 * {@code DISALLOWED}.
 */
final class RobotsDomainPolicy {

    enum Kind {
        /** Parsed rules from HTTP 2xx body. */
        PARSED,
        /** HTTP 4xx: intentional allow-all (TS-06 product decision). */
        ALLOW_ALL_4XX,
        /** Last fetch ended in temporary deny window (3xx/5xx, redirect loop, hop limit, I/O). */
        TRANSIENT_FAILURE
    }

    private final Kind kind;
    private final BaseRobotRules rules;
    /**
     * End of the temporary-deny window for {@link Kind#TRANSIENT_FAILURE}; {@code null} if not applicable.
     */
    private final Instant denyUntil;
    /** Counts consecutive failed load attempts; reset to zero only after a successful load. */
    private final int consecutiveFailures;

    private RobotsDomainPolicy(Kind kind, BaseRobotRules rules, Instant denyUntil, int consecutiveFailures) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.denyUntil = denyUntil;
        this.consecutiveFailures = consecutiveFailures;
    }

    static RobotsDomainPolicy parsed(BaseRobotRules rules) {
        return new RobotsDomainPolicy(Kind.PARSED, rules, null, 0);
    }

    static RobotsDomainPolicy allowAll4xx(BaseRobotRules allowAllRules) {
        return new RobotsDomainPolicy(Kind.ALLOW_ALL_4XX, allowAllRules, null, 0);
    }

    /**
     * @param allowAllRules rules used only for crawl-delay / spacing (allow-all mode)
     * @param denyUntil when {@code evaluate} may stop returning TEMPORARY_DENY for this failure episode
     * @param consecutiveFailures failures so far including this attempt
     */
    static RobotsDomainPolicy transientFailure(
            BaseRobotRules allowAllRules, Instant denyUntil, int consecutiveFailures) {
        return new RobotsDomainPolicy(
                Kind.TRANSIENT_FAILURE, allowAllRules, Objects.requireNonNull(denyUntil), consecutiveFailures);
    }

    Kind kind() {
        return kind;
    }

    BaseRobotRules rules() {
        return rules;
    }

    Instant denyUntil() {
        return denyUntil;
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * {@code true} when URLs for this domain must be deferred (ingestion / fetch precondition already met).
     */
    boolean blockingDenyActive(Instant now) {
        return kind == Kind.TRANSIENT_FAILURE && denyUntil != null && now.isBefore(denyUntil);
    }

    /**
     * {@code true} when a transient failure's deny window has elapsed and {@code ensureLoaded} should hit
     * the network again (same thread orders {@code ensureLoaded} before {@code evaluate} on the fetch path).
     */
    boolean shouldRefreshAfterDenyWindow(Instant now) {
        return kind == Kind.TRANSIENT_FAILURE && denyUntil != null && !now.isBefore(denyUntil);
    }
}
