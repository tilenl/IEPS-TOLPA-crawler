package si.uni_lj.fri.wier.integration.storage.frontier;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Placeholder tests for TS-07 items that depend on pipeline pieces not yet wired (TS-17 order: TS-02
 * worker orchestration, seed bootstrap). Disabled until those components exist.
 */
class Ts07RoadmapBacklogTest {

    @Test
    @Disabled("Termination grace-window requires TS-02 terminationConditionMet / scheduler integration")
    void terminationGraceWindow_preventsPrematureCompletion() {
        throw new UnsupportedOperationException("planned per TS-07 Required Tests");
    }

    @Test
    @Disabled("Seed bootstrap canonicalization/scoring tests require TS-02 seed path integration")
    void seedBootstrap_canonicalUrlAndScore() {
        throw new UnsupportedOperationException("planned per TS-07 Required Tests");
    }
}
