package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

/**
 * Robots policy decision for one canonical URL.
 *
 * @param type decision kind: ALLOWED, DISALLOWED, or TEMPORARY_DENY.
 * @param reason optional diagnostic reason (for logs and error mapping).
 * @param denyUntil optional retry boundary for TEMPORARY_DENY.
 */
public record RobotDecision(RobotDecisionType type, String reason, Instant denyUntil) {
    public static RobotDecision allowed() {
        return new RobotDecision(RobotDecisionType.ALLOWED, null, null);
    }

    public static RobotDecision disallowed(String reason) {
        return new RobotDecision(RobotDecisionType.DISALLOWED, reason, null);
    }

    public static RobotDecision temporaryDeny(Instant denyUntil, String reason) {
        return new RobotDecision(RobotDecisionType.TEMPORARY_DENY, reason, denyUntil);
    }

    public Instant denyUntilOrDefault() {
        return denyUntil == null ? Instant.now() : denyUntil;
    }
}
