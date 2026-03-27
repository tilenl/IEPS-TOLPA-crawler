package si.uni_lj.fri.wier.unit.downloader.fetch;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;

/**
 * Test double: allows all robots paths and never delays rate limit (no network).
 */
public final class AllowAllPolitenessStub implements RobotsTxtCache, RateLimiterRegistry {

    private static final BaseRobotRules ALLOW =
            new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

    @Override
    public void ensureLoaded(String domain) {
        // no-op: tests do not hit /robots.txt
    }

    @Override
    public RobotDecision evaluate(String canonicalUrl) {
        return RobotDecision.allowed();
    }

    @Override
    public BaseRobotRules getRulesForDomain(String domain) {
        return ALLOW;
    }

    @Override
    public RateLimitDecision tryAcquire(String domain) {
        return RateLimitDecision.allowed();
    }
}
