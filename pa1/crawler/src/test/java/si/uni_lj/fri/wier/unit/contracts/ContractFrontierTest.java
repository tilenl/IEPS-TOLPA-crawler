package si.uni_lj.fri.wier.unit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.storage.frontier.ContractFrontier;

class ContractFrontierTest {

    @Test
    void claimNextFrontier_delegates_with_worker_and_lease() {
        FrontierRow row =
                new FrontierRow(
                        10L,
                        "https://example.com/a",
                        2L,
                        0.8,
                        0,
                        0,
                        Instant.parse("2025-01-01T00:00:00Z"));
        class StubDelegate implements ContractFrontier.Delegate {
            String workerSeen;
            Duration leaseSeen;

            @Override
            public Optional<FrontierRow> claim(String workerId, Duration leaseDuration) {
                workerSeen = workerId;
                leaseSeen = leaseDuration;
                return Optional.of(row);
            }

            @Override
            public boolean reschedule(
                    long pageId, Instant nextAttemptAt, String errorCategory, String diagnosticMessage) {
                return true;
            }
        }
        StubDelegate delegate = new StubDelegate();
        ContractFrontier frontier = new ContractFrontier(delegate, "w-1", Duration.ofSeconds(90));

        Optional<FrontierRow> claimed = frontier.claimNextFrontier();

        assertTrue(claimed.isPresent());
        assertSame(row, claimed.get());
        assertEquals("w-1", delegate.workerSeen);
        assertEquals(Duration.ofSeconds(90), delegate.leaseSeen);
    }

    @Test
    void reschedule_throws_when_delegate_reports_missing_page() {
        ContractFrontier frontier =
                new ContractFrontier(
                        new ContractFrontier.Delegate() {
                            @Override
                            public Optional<FrontierRow> claim(String workerId, Duration leaseDuration) {
                                return Optional.empty();
                            }

                            @Override
                            public boolean reschedule(
                                    long pageId, Instant nextAttemptAt, String errorCategory, String diagnosticMessage) {
                                return false;
                            }
                        },
                        "w-2",
                        Duration.ofSeconds(60));

        assertThrows(
                IllegalStateException.class,
                () -> frontier.reschedule(99L, Instant.now().plusSeconds(10), "FETCH_TIMEOUT", "test"));
    }
}
