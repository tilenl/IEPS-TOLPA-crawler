package si.uni_lj.fri.wier.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import si.uni_lj.fri.wier.config.RuntimeConfig;

class RecoveryPathExecutorTest {

    @Test
    void isTransientDatabaseFailure_true_forSerializationConflict() {
        assertTrue(
                RecoveryPathExecutor.isTransientDatabaseFailure(
                        new IllegalStateException(new SQLException("x", "40001"))));
    }

    @Test
    void isTransientDatabaseFailure_true_forConnectionClass() {
        assertTrue(
                RecoveryPathExecutor.isTransientDatabaseFailure(
                        new RuntimeException(new SQLException("conn", "08006"))));
    }

    @Test
    void isTransientDatabaseFailure_false_forConstraint() {
        assertFalse(
                RecoveryPathExecutor.isTransientDatabaseFailure(
                        new IllegalStateException(new SQLException("unique", "23505"))));
    }

    @Test
    void isTransientDatabaseFailure_false_withoutSqlException() {
        assertFalse(RecoveryPathExecutor.isTransientDatabaseFailure(new IllegalStateException("bug")));
    }

    @Test
    void runWithRetries_rethrowsNonTransientImmediately() {
        AtomicInteger runs = new AtomicInteger();
        RecoveryPathExecutor exec = new RecoveryPathExecutor(testConfig(), NOPLogger.NOP_LOGGER);
        assertThrows(
                IllegalStateException.class,
                () ->
                        exec.runWithRetries(
                                "w",
                                1L,
                                null,
                                "CAT",
                                () -> {
                                    runs.incrementAndGet();
                                    throw new IllegalStateException("not db");
                                }));
        assertEquals(1, runs.get());
    }

    @Test
    void runWithRetries_retriesTransientSqlThenSucceeds() {
        AtomicInteger runs = new AtomicInteger();
        RecoveryPathExecutor exec = new RecoveryPathExecutor(testConfig(), NOPLogger.NOP_LOGGER);
        exec.runWithRetries(
                "w",
                1L,
                null,
                "CAT",
                () -> {
                    int n = runs.incrementAndGet();
                    if (n < 3) {
                        throw new IllegalStateException(new SQLException("ser", "40001"));
                    }
                });
        assertEquals(3, runs.get());
    }

    private static RuntimeConfig testConfig() {
        Properties p = new Properties();
        try {
            p.setProperty(
                    "crawler.scoring.keywordConfig",
                    Paths.get(RecoveryPathExecutorTest.class.getResource("/keywords-valid.json").toURI())
                            .toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.retry.jitterMs", "0");
        p.setProperty("crawler.recoveryPath.maxAttempts", "5");
        p.setProperty("crawler.recoveryPath.baseBackoffMs", "1");
        return RuntimeConfig.fromProperties(p, 4);
    }
}
