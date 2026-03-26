package si.uni_lj.fri.wier.unit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;

class DecisionContractsTest {

    @Test
    void rateLimitDecision_allowed_has_zero_wait_and_not_delayed() {
        RateLimitDecision decision = RateLimitDecision.allowed();
        assertFalse(decision.isDelayed());
        assertEquals(0L, decision.waitNs());
    }

    @Test
    void rateLimitDecision_delayed_clamps_negative_wait_to_zero() {
        RateLimitDecision decision = RateLimitDecision.delayed(-1L);
        assertTrue(decision.isDelayed());
        assertEquals(0L, decision.waitNs());
    }

    @Test
    void robotDecision_temporary_deny_preserves_type_and_deadline() {
        Instant denyUntil = Instant.now().plusSeconds(30);
        RobotDecision decision = RobotDecision.temporaryDeny(denyUntil, "ROBOTS_TRANSIENT");
        assertEquals(RobotDecisionType.TEMPORARY_DENY, decision.type());
        assertEquals(denyUntil, decision.denyUntil());
        assertEquals(denyUntil, decision.denyUntilOrDefault());
    }

    @Test
    void robotDecision_allow_and_disallow_helpers_set_expected_type() {
        assertEquals(RobotDecisionType.ALLOWED, RobotDecision.allowed().type());
        RobotDecision disallowed = RobotDecision.disallowed("ROBOTS_DISALLOWED");
        assertEquals(RobotDecisionType.DISALLOWED, disallowed.type());
        assertNotNull(disallowed.reason());
    }
}
