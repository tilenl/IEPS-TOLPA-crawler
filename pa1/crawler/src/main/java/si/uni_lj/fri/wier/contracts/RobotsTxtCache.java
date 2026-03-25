package si.uni_lj.fri.wier.contracts;

import crawlercommons.robots.BaseRobotRules;

public interface RobotsTxtCache {
    void ensureLoaded(String domain);

    Contracts.RobotDecision evaluate(String canonicalUrl);

    BaseRobotRules getRulesForDomain(String domain);
}

