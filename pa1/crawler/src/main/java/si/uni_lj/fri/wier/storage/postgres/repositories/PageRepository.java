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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.ExtractedPageMetadata;
import si.uni_lj.fri.wier.contracts.ExtractedImage;
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
 * PostgreSQL repository for TS-10 SQL contracts and TS-07 frontier claim / lease recovery.
 *
 * <p>All statements are executed as prepared statements, and Stage B persistence runs in one
 * SERIALIZABLE transaction with bounded retry on SQLSTATE 40001. TS-04 page metadata is upserted into
 * {@code crawldb.page_data} during {@code persistFetchOutcomeWithLinks} for HTML outcomes. Reschedule and
 * terminal-error updates require {@code page_type_code = 'PROCESSING'} so SQL matches TS-10 transitions.
 *
 * <p>Frontier claim and stale-lease recovery SQL follow TS-07 normative shapes; orchestration (pre-claim
 * recovery batch, startup drain) lives in {@link si.uni_lj.fri.wier.storage.frontier.FrontierStore} and
 * {@link si.uni_lj.fri.wier.queue.claim.ClaimService}.
 */
public final class PageRepository {
    private static final Logger log = LoggerFactory.getLogger(PageRepository.class);

    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    /** {@code crawldb.data_type.code} for document title bytes in {@code crawldb.page_data.data}. */
    private static final String PAGE_DATA_TYPE_TITLE = "TITLE";

    /** {@code crawldb.data_type.code} for meta description bytes in {@code crawldb.page_data.data}. */
    private static final String PAGE_DATA_TYPE_META_DESCRIPTION = "META_DESCRIPTION";

    /** Caps stored UTF-8 text so a single metadata field cannot dominate the transaction log. */
    private static final int MAX_PAGE_METADATA_CHARS = 32_000;

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
        // Jitter spreads retries when many workers collide on the same conflict window.
        this.retryJitterMs = Math.max(0, retryJitterMs);
    }

    public Optional<Long> ensureSite(String domain) {
        final String selectSql = "SELECT id FROM crawldb.site WHERE domain = ? ORDER BY id ASC LIMIT 1";
        final String insertSql = "INSERT INTO crawldb.site(domain) VALUES (?) RETURNING id";
        try (Connection connection = dataSource.getConnection()) {
            // SELECT-first is the fast path when a site row already exists. Concurrent first-time inserts for
            // the same domain can still race (duplicate rows or constraint errors); this path does not use ON CONFLICT.
            // NOTE: follow-up (UNIQUE + upsert): .cursor/plans/implementation/fix_ensuresite_comment_eddfb802.plan.md
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

    /**
     * Atomic frontier claim (TS-07): one statement selects the candidate and transitions it to
     * {@code PROCESSING} with lease columns set. Ordering matches {@code idx_page_frontier_priority}.
     *
     * @param workerId stable worker identity for {@code claimed_by}
     * @param leaseDuration lease length; sub-second values are rounded up to one second for the SQL interval
     */
    public Optional<FrontierRow> claimNextEligibleFrontier(String workerId, Duration leaseDuration) {
        // Tie order matches TS-07 / TS-11: score, due time, age (accessed_time), then stable id.
        // Plain accessed_time ASC aligns with the btree index (no NULLS FIRST).
        final String sql =
                """
                WITH candidate AS (
                  SELECT id
                  FROM crawldb.page
                  WHERE page_type_code = 'FRONTIER'
                    AND next_attempt_at <= now()
                  ORDER BY relevance_score DESC, next_attempt_at ASC, accessed_time ASC, id ASC
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
                RETURNING p.id, p.url, p.site_id, p.relevance_score, p.attempt_count, p.next_attempt_at
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workerId);
            // Zero or negative lease would expire immediately; minimum one second matches interval semantics.
            statement.setLong(2, Math.max(1L, leaseDuration.toSeconds()));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp nextAttemptTs = rs.getTimestamp("next_attempt_at");
                return Optional.of(
                        new FrontierRow(
                                rs.getLong("id"),
                                rs.getString("url"),
                                rs.getLong("site_id"),
                                rs.getDouble("relevance_score"),
                                rs.getInt("attempt_count"),
                                Objects.requireNonNull(nextAttemptTs, "next_attempt_at must not be null for claimed row")
                                        .toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim next frontier row", e);
        }
    }

    /**
     * Stale-lease recovery (TS-07): {@code FOR UPDATE SKIP LOCKED} on the candidate set so concurrent
     * recoverers do not block each other on disjoint rows. Diagnostic columns follow TS-10 storage notes.
     *
     * @param recovererIdentity who is performing recovery (for application logs; TS-07 worker identity)
     */
    public int recoverExpiredLeases(int batchSize, String reason, String recovererIdentity) {
        final String sql =
                """
                WITH stale AS (
                  SELECT id
                  FROM crawldb.page
                  WHERE page_type_code = 'PROCESSING'
                    AND claim_expires_at < now()
                  ORDER BY claim_expires_at ASC, id ASC
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
                )
                UPDATE crawldb.page p
                SET page_type_code = 'FRONTIER',
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    next_attempt_at = now(),
                    last_error_category = 'DB_TRANSIENT',
                    last_error_message = ?,
                    last_error_at = now()
                FROM stale s
                WHERE p.id = s.id
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // LIMIT must bind before the message parameter to match placeholder order in the CTE.
            statement.setInt(1, Math.max(1, batchSize));
            // NOTE: last_error_message currently stores a short reason only. A future improvement could
            // persist the prior claimed_by (before clearing) in this message or alongside it, so operators
            // can see which crawler had lost the page when the lease went stale.
            statement.setString(2, reason);
            int updated = statement.executeUpdate();
            if (updated > 0) {
                log.info(
                        "recovered {} stale lease row(s) reason={} recoverer={}",
                        updated,
                        reason,
                        recovererIdentity);
            } else if (log.isDebugEnabled()) {
                log.debug(
                        "stale lease recovery batch reason={} recoverer={} (no rows)",
                        reason,
                        recovererIdentity);
            }
            return updated;
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
                  AND page_type_code = 'PROCESSING'
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
                  AND page_type_code = 'PROCESSING'
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
            // SERIALIZABLE prevents torn reads between content_owner upsert and page row updates under concurrency.
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            String contentType = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
            // Body must be present to persist HTML; missing body is treated as non-HTML even if type says html.
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

            // TS-04 image rows and page_data metadata reference the HTML page row (data NULL on image rows per TS-11).
            if (html && parsed != null) {
                insertExtractedImages(connection, context.pageId(), parsed.extractedImages(), result.fetchedAt());
                insertPageData(connection, context.pageId(), parsed.pageMetadata());
            }

            // Parser output and caller-supplied links are both ingested in one transaction for atomicity.
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
            // Restore pool defaults so the connection is safe to reuse for other isolation levels.
            connection.setAutoCommit(originalAutoCommit);
            connection.setTransactionIsolation(originalIsolation);
            connection.close();
        }
    }

    private InsertFrontierResult insertFrontierIfAbsent(
            Connection connection, String canonicalUrl, long siteId, double relevanceScore)
            throws SQLException {
        // NOTE: DO UPDATE SET url = EXCLUDED.url is a no-op that still runs so xmax distinguishes insert vs conflict.
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

    /**
     * Persists TS-04 image references for the current HTML page (URL metadata only; {@code data} stays
     * NULL).
     */
    private void insertExtractedImages(
            Connection connection, long pageId, List<ExtractedImage> images, Instant fetchedAt)
            throws SQLException {
        if (images == null || images.isEmpty()) {
            return;
        }
        Instant accessedAt = fetchedAt != null ? fetchedAt : Instant.now();
        final String sql =
                """
                INSERT INTO crawldb.image (page_id, filename, content_type, data, accessed_time)
                VALUES (?, ?, ?, NULL, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int batchCount = 0;
            for (ExtractedImage image : images) {
                if (image == null
                        || image.canonicalUrl() == null
                        || image.canonicalUrl().isBlank()) {
                    continue;
                }
                String filename = effectiveImageFilename(image);
                String contentType = image.contentType();
                if (contentType != null && contentType.length() > 50) {
                    contentType = contentType.substring(0, 50);
                }
                statement.setLong(1, pageId);
                statement.setString(2, filename);
                if (contentType == null || contentType.isBlank()) {
                    statement.setNull(3, Types.VARCHAR);
                } else {
                    statement.setString(3, contentType);
                }
                statement.setTimestamp(4, Timestamp.from(accessedAt));
                statement.addBatch();
                batchCount++;
            }
            if (batchCount > 0) {
                statement.executeBatch();
            }
        }
    }

    /**
     * TS-10 {@code insertPageData}: upserts optional title and meta description into {@code crawldb.page_data}
     * as UTF-8 {@code bytea} values, idempotent per (page_id, data_type_code).
     */
    private void insertPageData(Connection connection, long pageId, Optional<ExtractedPageMetadata> metadataOpt)
            throws SQLException {
        if (metadataOpt.isEmpty()) {
            return;
        }
        ExtractedPageMetadata meta = metadataOpt.get();
        if (meta.title() != null) {
            String trimmed = meta.title().trim();
            if (!trimmed.isEmpty()) {
                upsertPageDataRow(connection, pageId, PAGE_DATA_TYPE_TITLE, truncateForStorage(trimmed));
            }
        }
        if (meta.metaDescription() != null) {
            String trimmed = meta.metaDescription().trim();
            if (!trimmed.isEmpty()) {
                upsertPageDataRow(
                        connection, pageId, PAGE_DATA_TYPE_META_DESCRIPTION, truncateForStorage(trimmed));
            }
        }
    }

    private static void upsertPageDataRow(Connection connection, long pageId, String dataTypeCode, String text)
            throws SQLException {
        final String sql =
                """
                INSERT INTO crawldb.page_data (page_id, data_type_code, data)
                VALUES (?, ?, ?)
                ON CONFLICT (page_id, data_type_code) DO UPDATE SET data = EXCLUDED.data
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pageId);
            statement.setString(2, dataTypeCode);
            statement.setBytes(3, text.getBytes(StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
    }

    private static String truncateForStorage(String s) {
        if (s.length() <= MAX_PAGE_METADATA_CHARS) {
            return s;
        }
        return s.substring(0, MAX_PAGE_METADATA_CHARS);
    }

    /**
     * Prefers parser-supplied filename; otherwise derives a short name from the last path segment of
     * the canonical URL (TS-04).
     */
    private static String effectiveImageFilename(ExtractedImage image) {
        if (image.filename() != null && !image.filename().isBlank()) {
            String trimmed = image.filename().trim();
            return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
        }
        return filenameSegmentFromUrl(image.canonicalUrl());
    }

    private static String filenameSegmentFromUrl(String canonicalUrl) {
        int slash = canonicalUrl.lastIndexOf('/');
        String segment =
                slash >= 0 && slash < canonicalUrl.length() - 1
                        ? canonicalUrl.substring(slash + 1)
                        : "image";
        int q = segment.indexOf('?');
        if (q >= 0) {
            segment = segment.substring(0, q);
        }
        int h = segment.indexOf('#');
        if (h >= 0) {
            segment = segment.substring(0, h);
        }
        if (segment.isBlank()) {
            return "image";
        }
        return segment.length() > 255 ? segment.substring(segment.length() - 255) : segment;
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
            // Page id is returned for both new frontier rows and existing URL conflicts (idempotent ingest).
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
        // LEAST picks a single canonical owner deterministically when the same hash appears on multiple pages.
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
        // Keep content_hash for joins; drop html_content because the canonical owner row stores the bytes.
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
        // Fetch timestamps can be absent in tests or bad upstream data; DB column is NOT NULL.
        return instant == null ? Instant.now() : instant;
    }

    private <T> T withSerializableRetry(SqlWork<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxSerializableRetries; attempt++) {
            try {
                return work.execute();
            } catch (SQLException e) {
                last = e;
                // Only serialization failures are worth retrying; anything else should fail fast.
                if (!SQLSTATE_SERIALIZATION_FAILURE.equals(e.getSQLState()) || attempt >= maxSerializableRetries) {
                    break;
                }
                // Exponential backoff per attempt; jitter reduces synchronized thundering herds on hot rows.
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
            // Preserve interrupt status so callers can cooperate with shutdown after retries.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry backoff", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute() throws SQLException;
    }
}
