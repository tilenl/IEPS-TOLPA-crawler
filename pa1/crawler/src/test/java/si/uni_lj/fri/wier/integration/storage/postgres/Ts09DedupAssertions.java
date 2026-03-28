/*
 * TS-09 / TS-16: shared assertions for concurrent or serial same-content_hash batches.
 *
 * <p>Used by {@link PageRepositoryIntegrationTest} and {@link Ts16ConcurrencyRestartGateIT} so LEAST-owner
 * semantics (min {@code page_id}), duplicate rows, {@code content_owner}, and duplicate→owner {@code link} edges
 * stay single-sourced. Callers own Testcontainers setup and {@link javax.sql.DataSource} lifecycle.
 *
 * <p>Created: 2026-03. Major revision: extracted from PageRepositoryIntegrationTest for TS-16 release gate.
 */
package si.uni_lj.fri.wier.integration.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/** Static helpers asserting PostgreSQL state after a same-hash batch persist (TS-09). */
public final class Ts09DedupAssertions {

    private Ts09DedupAssertions() {}

    /**
     * Asserts one {@code HTML} owner (min {@code page_id} among {@code claimedIds}), {@code n-1} {@code DUPLICATE}
     * rows, a single {@code content_owner} row for {@code expectedHash}, and duplicate→owner links.
     *
     * @param dataSource live crawldb connection
     * @param claimedIds page ids that participated in the batch (all must exist)
     * @param expectedOwner expected owner id (typically min of inserted ids)
     * @param expectedHash SHA-256 hex from {@link si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl}
     * @param n batch size (number of pages with identical body)
     */
    public static void assertSameHashBatchPostconditions(
            DataSource dataSource, List<Long> claimedIds, long expectedOwner, String expectedHash, int n)
            throws SQLException {
        long minClaimed = claimedIds.stream().mapToLong(Long::longValue).min().orElseThrow();
        long ownerFromDb;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT owner_page_id FROM crawldb.content_owner WHERE content_hash = ?")) {
            ps.setString(1, expectedHash);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "content_owner row must exist for batch hash");
                ownerFromDb = rs.getLong(1);
            }
        }
        assertEquals(
                minClaimed,
                ownerFromDb,
                "TS-09 LEAST owner must equal min(page_id) among batch participants");
        assertEquals(expectedOwner, ownerFromDb);

        int htmlCount = 0;
        int dupCount = 0;
        for (long pid : claimedIds) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement("SELECT page_type_code, content_hash FROM crawldb.page WHERE id = ?")) {
                ps.setLong(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    String type = rs.getString(1);
                    assertEquals(expectedHash, rs.getString(2));
                    if (pid == expectedOwner) {
                        assertEquals("HTML", type);
                        htmlCount++;
                    } else {
                        assertEquals("DUPLICATE", type);
                        dupCount++;
                    }
                }
            }
        }
        assertEquals(1, htmlCount);
        assertEquals(n - 1, dupCount);

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT owner_page_id FROM crawldb.content_owner WHERE content_hash = ?")) {
            ps.setString(1, expectedHash);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedOwner, rs.getLong(1));
            }
        }

        for (long pid : claimedIds) {
            if (pid == expectedOwner) {
                continue;
            }
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT 1 FROM crawldb.link WHERE from_page = ? AND to_page = ? LIMIT 1")) {
                ps.setLong(1, pid);
                ps.setLong(2, expectedOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "duplicate page must link to owner");
                }
            }
        }
    }
}
