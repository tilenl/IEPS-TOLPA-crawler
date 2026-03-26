package si.uni_lj.fri.wier.storage.postgres.repositories;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.IngestRejection;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.LinkInsertResult;
import si.uni_lj.fri.wier.contracts.PageOutcomeType;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;

/**
 * PostgreSQL repository for TS-10 SQL contracts.
 *
 * <p>All statements are executed as prepared statements, and Stage B persistence runs in one
 * SERIALIZABLE transaction with bounded retry on SQLSTATE 40001.
 */
public final class PageRepository {
    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    private final DataSource dataSource;
    private final int maxSerializableRetries;
    private final Duration serializableBaseBackoff;
    private final int retryJitterMs;

    public PageRepository(DataSource dataSource) {
        this(dataSource, 3, Duration.ofMillis(100), 250);
    }

    public PageRepository(
            DataSource dataSource,
            int maxSerializableRetries,
            Duration serializableBaseBackoff,
            int retryJitterMs) {
        this.dataSource = dataSource;
        this.maxSerializableRetries = Math.max(1, maxSerializableRetries);
        this.serializableBaseBackoff = serializableBaseBackoff;
        this.retryJitterMs = Math.max(0, retryJitterMs);
    }

    public Optional<Long> ensureSite(String domain) {
        final String selectSql = "SELECT id FROM crawldb.site WHERE domain = ? ORDER BY id ASC LIMIT 1";
        final String insertSql = "INSERT INTO crawldb.site(domain) VALUES (?) RETURNING id";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, domain);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getLong(1));
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, domain);
                try (ResultSet rs = insert.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getLong(1));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ensure site for domain=" + domain, e);
        }
    }

    public Optional<FrontierRow> claimNextEligibleFrontier(String workerId, Duration leaseDuration) {
        final String sql =
                """
                WITH candidate AS (
                  SELECT id
                  FROM crawldb.page
                  WHERE page_type_code = 'FRONTIER'
                    AND next_attempt_at <= now()
                  ORDER BY relevance_score DESC, next_attempt_at ASC, accessed_time ASC NULLS FIRST, id ASC
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1
                )
                UPDATE crawldb.page p
                SET page_type_code = 'PROCESSING',
                    claimed_by = ?,
                    claimed_at = now(),
                    claim_expires_at = now() + (? * interval '1 second')
                FROM candidate c
                WHERE p.id = c.id
                RETURNING p.id, p.url, p.site_id, p.relevance_score
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workerId);
            statement.setLong(2, Math.max(1L, leaseDuration.toSeconds()));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new FrontierRow(
                                rs.getLong("id"),
                                rs.getString("url"),
                                rs.getLong("site_id"),
                                rs.getDouble("relevance_score")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim next frontier row", e);
        }
    }

    public int recoverExpiredLeases(int batchSize, String reason) {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'FRONTIER',
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    next_attempt_at = now(),
                    last_error_category = 'DB_TRANSIENT',
                    last_error_message = ?,
                    last_error_at = now()
                WHERE id IN (
                  SELECT id
                  FROM crawldb.page
                  WHERE page_type_code = 'PROCESSING'
                    AND claim_expires_at < now()
                  ORDER BY claim_expires_at ASC
                  LIMIT ?
                )
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setInt(2, Math.max(1, batchSize));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to recover expired leases", e);
        }
    }

    public boolean reschedulePage(long pageId, Instant nextAttemptAt, String reason) {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'FRONTIER',
                    next_attempt_at = ?,
                    attempt_count = attempt_count + 1,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    last_error_category = 'DB_TRANSIENT',
                    last_error_message = ?,
                    last_error_at = now()
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(nextAttemptAt));
            statement.setString(2, reason);
            statement.setLong(3, pageId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reschedule pageId=" + pageId, e);
        }
    }

    public void markPageTerminalError(long pageId, String category, String message) {
        markPageTerminalError(pageId, category, message, Instant.now());
    }

    public void markPageTerminalError(long pageId, String category, String message, Instant errorAt) {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'ERROR',
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    last_error_category = ?,
                    last_error_message = ?,
                    last_error_at = ?
                WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, category);
            statement.setString(2, message);
            statement.setTimestamp(3, Timestamp.from(errorAt));
            statement.setLong(4, pageId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark terminal error for pageId=" + pageId, e);
        }
    }

    public InsertFrontierResult insertFrontierIfAbsent(String canonicalUrl, long siteId, double relevanceScore) {
        try (Connection connection = dataSource.getConnection()) {
            return insertFrontierIfAbsent(connection, canonicalUrl, siteId, relevanceScore);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert frontier url=" + canonicalUrl, e);
        }
    }

    public LinkInsertResult insertLink(long fromPageId, long toPageId) {
        final String sql =
                """
                INSERT INTO crawldb.link(from_page, to_page)
                VALUES (?, ?)
                ON CONFLICT (from_page, to_page) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, fromPageId);
            statement.setLong(2, toPageId);
            return new LinkInsertResult(statement.executeUpdate() == 1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert link " + fromPageId + "->" + toPageId, e);
        }
    }

    public IngestResult ingestDiscoveredUrls(Collection<DiscoveredUrl> discoveredUrls) {
        try (Connection connection = dataSource.getConnection()) {
            return ingestDiscoveredUrls(connection, discoveredUrls);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ingest discovered URLs", e);
        }
    }

    public PersistOutcome persistFetchOutcomeWithLinks(
            FetchContext context,
            FetchResult result,
            ParseResult parsed,
            Collection<DiscoveredUrl> discovered) {
        return withSerializableRetry(
                () -> persistFetchOutcomeWithLinksOnce(context, result, parsed, discovered));
    }

    private PersistOutcome persistFetchOutcomeWithLinksOnce(
            FetchContext context,
            FetchResult result,
            ParseResult parsed,
            Collection<DiscoveredUrl> discovered)
            throws SQLException {
        Connection connection = dataSource.getConnection();
        int originalIsolation = connection.getTransactionIsolation();
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            String contentType = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
            boolean html = contentType.contains("text/html") && result.body() != null;

            Long ownerPageId = null;
            PageOutcomeType outcomeType;
            if (html) {
                String contentHash = sha256Hex(result.body());
                ownerPageId = registerContentOwnership(connection, contentHash, context.pageId());
                if (ownerPageId == context.pageId()) {
                    markPageHtml(connection, context.pageId(), result.statusCode(), result.fetchedAt(), result.body(), contentHash);
                    outcomeType = PageOutcomeType.HTML;
                } else {
                    markPageDuplicate(connection, context.pageId(), result.statusCode(), result.fetchedAt(), contentHash);
                    outcomeType = PageOutcomeType.DUPLICATE;
                }
            } else {
                markPageBinary(connection, context.pageId(), result.statusCode(), result.fetchedAt());
                outcomeType = PageOutcomeType.BINARY;
            }

            List<DiscoveredUrl> merged = new ArrayList<>();
            if (parsed != null && parsed.discoveredUrls() != null) {
                merged.addAll(parsed.discoveredUrls());
            }
            if (discovered != null) {
                merged.addAll(discovered);
            }
            IngestResult ingestResult = ingestDiscoveredUrls(connection, merged);

            connection.commit();
            return new PersistOutcome(context.pageId(), outcomeType, ownerPageId, ingestResult);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
            connection.setTransactionIsolation(originalIsolation);
            connection.close();
        }
    }

    private InsertFrontierResult insertFrontierIfAbsent(
            Connection connection, String canonicalUrl, long siteId, double relevanceScore)
            throws SQLException {
        final String sql =
                """
                INSERT INTO crawldb.page(site_id, page_type_code, url, relevance_score, next_attempt_at, attempt_count)
                VALUES (?, 'FRONTIER', ?, ?, now(), 0)
                ON CONFLICT (url) DO UPDATE SET url = EXCLUDED.url
                RETURNING id, (xmax = 0) AS inserted
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, siteId);
            statement.setString(2, canonicalUrl);
            statement.setDouble(3, relevanceScore);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("insertFrontierIfAbsent returned no rows");
                }
                return new InsertFrontierResult(rs.getLong("id"), rs.getBoolean("inserted"));
            }
        }
    }

    private IngestResult ingestDiscoveredUrls(Connection connection, Collection<DiscoveredUrl> discoveredUrls)
            throws SQLException {
        List<Long> accepted = new ArrayList<>();
        List<IngestRejection> rejected = new ArrayList<>();
        if (discoveredUrls == null) {
            return new IngestResult(accepted, rejected);
        }

        for (DiscoveredUrl discovered : discoveredUrls) {
            if (discovered == null || discovered.canonicalUrl() == null || discovered.canonicalUrl().isBlank()) {
                rejected.add(new IngestRejection(discovered, "INVALID_URL"));
                continue;
            }
            if (discovered.canonicalUrl().length() > 3000) {
                rejected.add(new IngestRejection(discovered, "URL_TOO_LONG"));
                continue;
            }

            InsertFrontierResult insertResult =
                    insertFrontierIfAbsent(connection, discovered.canonicalUrl(), discovered.siteId(), discovered.relevanceScore());
            accepted.add(insertResult.pageId());
            insertLink(connection, discovered.fromPageId(), insertResult.pageId());
        }
        return new IngestResult(accepted, rejected);
    }

    private void insertLink(Connection connection, long fromPageId, long toPageId) throws SQLException {
        final String sql =
                """
                INSERT INTO crawldb.link(from_page, to_page)
                VALUES (?, ?)
                ON CONFLICT (from_page, to_page) DO NOTHING
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, fromPageId);
            statement.setLong(2, toPageId);
            statement.executeUpdate();
        }
    }

    private long registerContentOwnership(Connection connection, String contentHash, long pageId)
            throws SQLException {
        final String sql =
                """
                INSERT INTO crawldb.content_owner(content_hash, owner_page_id, created_at)
                VALUES (?, ?, now())
                ON CONFLICT (content_hash) DO UPDATE
                SET owner_page_id = LEAST(crawldb.content_owner.owner_page_id, EXCLUDED.owner_page_id)
                RETURNING owner_page_id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, contentHash);
            statement.setLong(2, pageId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("registerContentOwnership returned no rows");
                }
                return rs.getLong("owner_page_id");
            }
        }
    }

    private void markPageHtml(
            Connection connection,
            long pageId,
            int statusCode,
            Instant fetchedAt,
            String htmlContent,
            String contentHash)
            throws SQLException {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'HTML',
                    html_content = ?,
                    content_hash = ?,
                    http_status_code = ?,
                    accessed_time = ?,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    last_error_category = NULL,
                    last_error_message = NULL,
                    last_error_at = NULL
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, htmlContent);
            statement.setString(2, contentHash);
            statement.setInt(3, statusCode);
            statement.setTimestamp(4, Timestamp.from(nonNullInstant(fetchedAt)));
            statement.setLong(5, pageId);
            statement.executeUpdate();
        }
    }

    private void markPageDuplicate(
            Connection connection, long pageId, int statusCode, Instant fetchedAt, String contentHash)
            throws SQLException {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'DUPLICATE',
                    html_content = NULL,
                    content_hash = ?,
                    http_status_code = ?,
                    accessed_time = ?,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, contentHash);
            statement.setInt(2, statusCode);
            statement.setTimestamp(3, Timestamp.from(nonNullInstant(fetchedAt)));
            statement.setLong(4, pageId);
            statement.executeUpdate();
        }
    }

    private void markPageBinary(Connection connection, long pageId, int statusCode, Instant fetchedAt)
            throws SQLException {
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'BINARY',
                    html_content = NULL,
                    http_status_code = ?,
                    accessed_time = ?,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, statusCode);
            statement.setTimestamp(2, Timestamp.from(nonNullInstant(fetchedAt)));
            statement.setLong(3, pageId);
            statement.executeUpdate();
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Instant nonNullInstant(Instant instant) {
        return instant == null ? Instant.now() : instant;
    }

    private <T> T withSerializableRetry(SqlWork<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxSerializableRetries; attempt++) {
            try {
                return work.execute();
            } catch (SQLException e) {
                last = e;
                if (!SQLSTATE_SERIALIZATION_FAILURE.equals(e.getSQLState()) || attempt >= maxSerializableRetries) {
                    break;
                }
                long base = serializableBaseBackoff.toMillis() * (1L << (attempt - 1));
                long jitter = retryJitterMs == 0 ? 0 : ThreadLocalRandom.current().nextLong(retryJitterMs + 1L);
                sleepQuietly(base + jitter);
            }
        }
        throw new IllegalStateException("SERIALIZABLE transaction failed after retries", last);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry backoff", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute() throws SQLException;
    }
}
