package si.uni_lj.fri.wier.unit.scheduler.policies;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;

class SchedulingPolicyTest {

    @Test
    void newVirtualThreadWorkerExecutor_isUsable() throws Exception {
        ExecutorService ex = SchedulingPolicy.newVirtualThreadWorkerExecutor();
        try {
            var ref = new Object() {
                boolean ran;
            };
            ex.submit(
                            () -> {
                                ref.ran = true;
                            })
                    .get();
            assertTrue(ref.ran);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void newWorkerId_isNonBlank() {
        assertNotNull(SchedulingPolicy.newWorkerId());
        assertTrue(SchedulingPolicy.newWorkerId().length() > 5);
    }

    @Test
    void poolSizeSatisfiesWorkers_followsValidatedConfig() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertTrue(SchedulingPolicy.poolSizeSatisfiesWorkers(cfg));
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(SchedulingPolicyTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        return p;
    }
}
