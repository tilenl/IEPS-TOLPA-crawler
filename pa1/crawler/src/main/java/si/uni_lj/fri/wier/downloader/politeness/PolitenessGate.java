package si.uni_lj.fri.wier.downloader.politeness;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;

/**
 * Combined placeholder for robots and per-domain rate-limit decisions.
 *
 * <p>This class currently implements deterministic allow behavior so contract wiring compiles; full
 * policy logic is implemented in TS-06 and TS-08 tracks.
 */
public final class PolitenessGate implements RobotsTxtCache, RateLimiterRegistry {
    private static final BaseRobotRules ALLOW_ALL_RULES =
            new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

    private final Map<String, BaseRobotRules> rulesByDomain = new ConcurrentHashMap<>();

    @Override
    public void ensureLoaded(String domain) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        rulesByDomain.computeIfAbsent(domain, ignored -> ALLOW_ALL_RULES);
    }

    @Override
    public RobotDecision evaluate(String canonicalUrl) {
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            return RobotDecision.temporaryDeny(Instant.now(), "INVALID_URL");
        }
        return RobotDecision.allowed();
    }

    @Override
    public BaseRobotRules getRulesForDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return ALLOW_ALL_RULES;
        }
        return rulesByDomain.getOrDefault(domain, ALLOW_ALL_RULES);
    }

    @Override
    public RateLimitDecision tryAcquire(String domain) {
        if (domain == null || domain.isBlank()) {
            return RateLimitDecision.delayed(1_000_000L);
        }
        return RateLimitDecision.allowed();
    }
}
