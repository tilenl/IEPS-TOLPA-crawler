package si.uni_lj.fri.wier.contracts;

import crawlercommons.robots.BaseRobotRules;

/**
 * Domain robots rules cache contract.
 *
 * <p>Callers must load/refresh rules before evaluating policy for a URL.
 */
public interface RobotsTxtCache {
    void ensureLoaded(String domain);

    RobotDecision evaluate(String canonicalUrl);

    BaseRobotRules getRulesForDomain(String domain);
}
