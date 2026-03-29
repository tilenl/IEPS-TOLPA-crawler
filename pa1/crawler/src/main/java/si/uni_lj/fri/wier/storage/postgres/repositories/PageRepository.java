package si.uni_lj.fri.wier.storage.postgres.repositories;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import si.uni_lj.fri.wier.contracts.ContentHasher;
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
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.observability.QueueStateStructuredLog;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueService;

/**
 * PostgreSQL repository for TS-10 SQL contracts, TS-07 frontier claim / lease recovery, TS-09 content
 * deduplication (atomic {@code content_owner} + duplicate→owner {@code link}), and TS-12 retry diagnostics.
 *
 * <p>All statements are executed as prepared statements, and Stage B persistence runs in one
 * SERIALIZABLE transaction with bounded retry on SQLSTATE 40001. TS-04 page metadata is upserted into
 * {@code crawldb.page_data} during {@code persistFetchOutcomeWithLinks} for HTML outcomes. Reschedule and
 * terminal-error updates require {@code page_type_code = 'PROCESSING'} so SQL matches TS-10 transitions.
 * Parser-stage reschedules increment {@code parser_retry_count} only; fetch-stage reschedules increment
 * {@code attempt_count} only (TS-12).
 *
 * <p>Frontier claim and stale-lease recovery SQL follow TS-07 normative shapes; orchestration (pre-claim
 * recovery batch, startup drain) lives in {@link si.uni_lj.fri.wier.storage.frontier.FrontierStore} and
 * {@link si.uni_lj.fri.wier.queue.claim.ClaimService}.
 *
 * <p>TS-02: when {@link RobotsTxtCache}, {@link RuntimeConfig}, and {@link EnqueueService} are supplied to the
 * extended constructor, {@link #ingestDiscoveredUrls} (inside {@code persistFetchOutcomeWithLinks}) evaluates
 * robots (evaluate-only; caller must {@code ensureLoaded} before opening the SERIALIZABLE transaction) and
 * applies crawl budgets with structured {@code BUDGET_DROPPED} / frontier deferral logging.
 */
public final class PageRepository {

    /**
     * Snapshot of overdue {@code FRONTIER} rows ({@code next_attempt_at <= now()}) for TS-12 queue-health
     * metrics ({@code delayed queue age}, {@code oldest overdue retry}).
     */
    public record FrontierOverdueHealth(long overdueFrontierCount, long avgOverdueMillis, long oldestOverdueMillis) {}

    /**
     * TS-15 {@code CRAWLER_HEARTBEAT}: one round-trip counts for {@code FRONTIER}, {@code PROCESSING}, and terminal
     * types ({@code HTML}, {@code BINARY}, {@code DUPLICATE}, {@code ERROR}).
     */
    /**
     * Heartbeat snapshot (TS-15): queue depths, terminal throughput coarse count, and optional oldest active lease
     * age in milliseconds (MAY field).
     */
    public record HeartbeatQueueSnapshot(
            long frontierDepth, long processingCount, long pagesTerminalTotal, long oldestLeaseAgeMs) {}

    private static final Logger log = LoggerFactory.getLogger(PageRepository.class);

    private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";

    /** {@code crawldb.data_type.code} for document title bytes in {@code crawldb.page_data.data}. */
    private static final String PAGE_DATA_TYPE_TITLE = "TITLE";

    /** {@code crawldb.data_type.code} for meta description bytes in {@code crawldb.page_data.data}. */
    private static final String PAGE_DATA_TYPE_META_DESCRIPTION = "META_DESCRIPTION";

    /** Caps stored UTF-8 text so a single metadata field cannot dominate the transaction log. */
    private static final int MAX_PAGE_METADATA_CHARS = 32_000;

    /**
     * HTML body hashing for TS-09 at persist time via {@link ContentHasherImpl}. Any future fetch-stage hash must
     * use the same {@link ContentHasher} implementation to keep fingerprints aligned end-to-end.
     */
    private final ContentHasher contentHasher = new ContentHasherImpl();

    private final DataSource dataSource;
    private final int maxSerializableRetries;
    private final Duration serializableBaseBackoff;
    private final int retryJitterMs;
    private final RobotsTxtCache discoveredIngestRobots;
    private final RuntimeConfig discoveredIngestConfig;
    private final EnqueueService discoveredIngestLog;
    private final CrawlerMetrics crawlMetrics;

    public PageRepository(DataSource dataSource) {
        this(dataSource, 3, Duration.ofMillis(100), 250, null, null, null, null);
    }


    public PageRepository(
            DataSource dataSource,
            int maxSerializableRetries,
            Duration serializableBaseBackoff,
            int retryJitterMs) {
        this(dataSource, maxSerializableRetries, serializableBaseBackoff, retryJitterMs, null, null, null, null);
    }

    /**
     * @param discoveredIngestRobots when non-null with config and log, discovery ingestion applies TS-02 robots
     *     evaluate + budgets; any null disables that policy (legacy direct insert for tests).
     */
    public PageRepository(
            DataSource dataSource,
            int maxSerializableRetries,
            Duration serializableBaseBackoff,
            int retryJitterMs,
            RobotsTxtCache discoveredIngestRobots,
            RuntimeConfig discoveredIngestConfig,
            EnqueueService discoveredIngestLog) {
        this(
                dataSource,
                maxSerializableRetries,
                serializableBaseBackoff,
                retryJitterMs,
                discoveredIngestRobots,
                discoveredIngestConfig,
                discoveredIngestLog,
                null);
    }

    /**
     * @param crawlMetrics optional TS-15 hooks (URL dedup, persist outcomes, lease recovery, SQL timeout signals)
     */
    public PageRepository(
            DataSource dataSource,
            int maxSerializableRetries,
            Duration serializableBaseBackoff,
            int retryJitterMs,
            RobotsTxtCache discoveredIngestRobots,
            RuntimeConfig discoveredIngestConfig,
            EnqueueService discoveredIngestLog,
            CrawlerMetrics crawlMetrics) {
        this.dataSource = dataSource;
        this.maxSerializableRetries = Math.max(1, maxSerializableRetries);
        this.serializableBaseBackoff = serializableBaseBackoff;
        // Jitter spreads retries when many workers collide on the same conflict window.
        this.retryJitterMs = Math.max(0, retryJitterMs);
        this.discoveredIngestRobots = discoveredIngestRobots;
        this.discoveredIngestConfig = discoveredIngestConfig;
        this.discoveredIngestLog = discoveredIngestLog;
        this.crawlMetrics = crawlMetrics;
    }

    private boolean isDiscoveredIngestPolicyEnabled() {
        return discoveredIngestRobots != null
                && discoveredIngestConfig != null
                && discoveredIngestLog != null;
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
                RETURNING p.id, p.url, p.site_id, p.relevance_score, p.attempt_count, p.parser_retry_count,
                          p.next_attempt_at, p.claimed_at, p.claim_expires_at
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
                Timestamp claimedTs = rs.getTimestamp("claimed_at");
                Timestamp claimExpiresTs = rs.getTimestamp("claim_expires_at");
                return Optional.of(
                        new FrontierRow(
                                rs.getLong("id"),
                                rs.getString("url"),
                                rs.getLong("site_id"),
                                rs.getDouble("relevance_score"),
                                rs.getInt("attempt_count"),
                                rs.getInt("parser_retry_count"),
                                Objects.requireNonNull(nextAttemptTs, "next_attempt_at must not be null for claimed row")
                                        .toInstant(),
                                Objects.requireNonNull(claimedTs, "claimed_at must not be null for claimed row")
                                        .toInstant(),
                                Objects.requireNonNull(claimExpiresTs, "claim_expires_at must not be null for claimed row")
                                        .toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim next frontier row", e);
        }
    }

    /**
     * One round-trip snapshot of how long eligible {@code FRONTIER} URLs have been waiting past
     * {@code next_attempt_at} (TS-12 observability: delayed queue age, oldest overdue retry).
     */
    public FrontierOverdueHealth sampleFrontierOverdueHealth() {
        final String sql =
                """
                SELECT COUNT(*)::bigint,
                       COALESCE(
                           ROUND(AVG((EXTRACT(EPOCH FROM (now() - next_attempt_at)) * 1000)))::bigint,
                           0),
                       COALESCE(
                           MAX((EXTRACT(EPOCH FROM (now() - next_attempt_at)) * 1000))::bigint,
                           0)
                FROM crawldb.page
                WHERE page_type_code = 'FRONTIER'
                  AND next_attempt_at <= now()
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return new FrontierOverdueHealth(0L, 0L, 0L);
            }
            return new FrontierOverdueHealth(rs.getLong(1), rs.getLong(2), rs.getLong(3));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to sample frontier overdue health", e);
        }
    }

    /**
     * Single-query snapshot for structured heartbeat logs (TS-15): {@code frontierDepth}, {@code processingCount},
     * {@code pagesTerminalTotal}.
     */
    public HeartbeatQueueSnapshot queryHeartbeatQueueSnapshot() {
        final String sql =
                """
                SELECT COUNT(*) FILTER (WHERE page_type_code = 'FRONTIER'),
                       COUNT(*) FILTER (WHERE page_type_code = 'PROCESSING'),
                       COUNT(*) FILTER (WHERE page_type_code IN ('HTML', 'BINARY', 'DUPLICATE', 'ERROR')),
                       COALESCE(
                           (SELECT MAX(
                                   (EXTRACT(EPOCH FROM (now() - p2.claimed_at)) * 1000)::bigint)
                            FROM crawldb.page p2
                            WHERE p2.page_type_code = 'PROCESSING'
                              AND p2.claimed_at IS NOT NULL),
                           0)
                FROM crawldb.page
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return new HeartbeatQueueSnapshot(0L, 0L, 0L, 0L);
            }
            long frontier = rs.getLong(1);
            long processing = rs.getLong(2);
            long terminal = rs.getLong(3);
            long oldestLease = rs.getLong(4);
            if (crawlMetrics != null) {
                crawlMetrics.setFrontierDepthMirror(frontier);
                crawlMetrics.recordOldestActiveLeaseAgeSample(oldestLease);
            }
            return new HeartbeatQueueSnapshot(frontier, processing, terminal, oldestLease);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query heartbeat queue snapshot", e);
        }
    }

    /** TS-15 run summary: one row with counts per {@code page_type_code}. */
    public record RunSummaryPageTypeSnapshot(
            long totalUrls,
            long frontierCount,
            long processingCount,
            long htmlCount,
            long binaryCount,
            long duplicateCount,
            long errorCount) {}

    public record ErrorCategoryCountRow(String category, long count) {}

    public record TopDomainRow(String domain, long terminalPageCount) {}

    public RunSummaryPageTypeSnapshot queryRunSummaryPageTypeSnapshot() {
        final String sql =
                """
                SELECT COUNT(*)::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'FRONTIER')::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'PROCESSING')::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'HTML')::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'BINARY')::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'DUPLICATE')::bigint,
                       COUNT(*) FILTER (WHERE page_type_code = 'ERROR')::bigint
                FROM crawldb.page
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return new RunSummaryPageTypeSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L);
            }
            return new RunSummaryPageTypeSnapshot(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5),
                    rs.getLong(6),
                    rs.getLong(7));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query run summary page type snapshot", e);
        }
    }

    public List<ErrorCategoryCountRow> queryErrorCountsByCategory() {
        final String sql =
                """
                SELECT COALESCE(NULLIF(TRIM(last_error_category), ''), 'UNKNOWN'), COUNT(*)::bigint
                FROM crawldb.page
                WHERE page_type_code = 'ERROR'
                GROUP BY 1
                ORDER BY COUNT(*) DESC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<ErrorCategoryCountRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new ErrorCategoryCountRow(rs.getString(1), rs.getLong(2)));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query error counts by category", e);
        }
    }

    public List<TopDomainRow> queryTopDomainsByTerminalPageCount(int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        final String sql =
                """
                SELECT s.domain, COUNT(*)::bigint AS c
                FROM crawldb.page p
                JOIN crawldb.site s ON s.id = p.site_id
                WHERE p.page_type_code IN ('HTML', 'BINARY', 'DUPLICATE', 'ERROR')
                GROUP BY s.domain
                ORDER BY c DESC
                LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, lim);
            try (ResultSet rs = statement.executeQuery()) {
                List<TopDomainRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new TopDomainRow(rs.getString(1), rs.getLong(2)));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query top domains by terminal page count", e);
        }
    }

    /**
     * Total rows in {@code crawldb.page} (TS-02 seed bootstrap: empty table guard).
     */
    public long countPagesTotal() {
        final String sql = "SELECT COUNT(*) FROM crawldb.page";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count crawldb.page rows", e);
        }
    }

    /**
     * Rows still queued or leased ({@code FRONTIER} or {@code PROCESSING}), including delayed frontier work
     * (TS-02 termination condition).
     */
    public long countNonTerminalQueuePages() {
        final String sql =
                """
                SELECT COUNT(*) FROM crawldb.page
                WHERE page_type_code IN ('FRONTIER', 'PROCESSING')
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count non-terminal queue pages", e);
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
            if (crawlMetrics != null && updated > 0) {
                crawlMetrics.recordLeaseRecoveryBatch(updated);
            }
            if (updated > 0) {
                QueueStateStructuredLog.logLeaseRecoveryBatch(log, updated, reason, recovererIdentity);
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

    /**
     * TS-10 / TS-12: atomic {@code PROCESSING → FRONTIER} with counters and durable diagnostics.
     *
     * @param parserStageReschedule when {@code true}, only {@code parser_retry_count} increments (TS-12
     *     {@code PARSER_FAILURE}); otherwise only {@code attempt_count} increments.
     */
    public boolean reschedulePage(
            long pageId,
            Instant nextAttemptAt,
            String lastErrorCategory,
            String lastErrorMessage,
            boolean parserStageReschedule) {
        // Single UPDATE with bound deltas keeps queue transition and counters atomic (TS-10).
        final int attemptDelta = parserStageReschedule ? 0 : 1;
        final int parserRetryDelta = parserStageReschedule ? 1 : 0;
        final String sql =
                """
                UPDATE crawldb.page
                SET page_type_code = 'FRONTIER',
                    next_attempt_at = ?,
                    attempt_count = attempt_count + ?,
                    parser_retry_count = parser_retry_count + ?,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    last_error_category = ?,
                    last_error_message = ?,
                    last_error_at = now()
                WHERE id = ?
                  AND page_type_code = 'PROCESSING'
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(nextAttemptAt));
            statement.setInt(2, attemptDelta);
            statement.setInt(3, parserRetryDelta);
            statement.setString(4, lastErrorCategory);
            statement.setString(5, lastErrorMessage);
            statement.setLong(6, pageId);
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
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException(
                        "markPageTerminalError expected exactly one PROCESSING row for pageId="
                                + pageId
                                + " but updated="
                                + updated);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark terminal error for pageId=" + pageId, e);
        }
    }

    public InsertFrontierResult insertFrontierIfAbsent(String canonicalUrl, long siteId, double relevanceScore) {
        return insertFrontierIfAbsent(canonicalUrl, siteId, relevanceScore, Instant.now());
    }

    /**
     * Inserts a FRONTIER row with an explicit {@code next_attempt_at} (TS-06 deferred enqueue after robots
     * TEMPORARY_DENY).
     */
    public InsertFrontierResult insertFrontierIfAbsent(
            String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        try (Connection connection = dataSource.getConnection()) {
            return insertFrontierIfAbsent(connection, canonicalUrl, siteId, relevanceScore, nextAttemptAt);
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
            // Isolation must be set before the transaction begins (JDBC + PostgreSQL). Starting with
            // setAutoCommit(false) first can open READ COMMITTED and leave SERIALIZABLE ineffective.
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);

            String contentType = result.contentType() == null ? "" : result.contentType().toLowerCase(Locale.ROOT);
            // Body must be present to persist HTML; missing body is treated as non-HTML even if type says html.
            boolean html = contentType.contains("text/html") && result.body() != null;

            Long ownerPageId = null;
            PageOutcomeType outcomeType;
            if (html) {
                String contentHash = contentHasher.sha256(result.body());
                ownerPageId = registerContentOwnership(connection, contentHash, context.pageId());
                // A larger page_id may have committed HTML before LEAST chose a smaller owner; reconcile stale rows.
                downgradeStaleHtmlOwnersForSameContentHash(
                        connection, contentHash, ownerPageId, result.statusCode(), result.fetchedAt());
                if (ownerPageId == context.pageId()) {
                    markPageHtml(connection, context.pageId(), result.statusCode(), result.fetchedAt(), result.body(), contentHash);
                    outcomeType = PageOutcomeType.HTML;
                } else {
                    markPageDuplicate(connection, context.pageId(), result.statusCode(), result.fetchedAt(), contentHash);
                    // TS-09: same graph convention as URL dedup — edge from the current page to the canonical target.
                    insertLink(connection, context.pageId(), ownerPageId);
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

            if (crawlMetrics != null) {
                switch (outcomeType) {
                    case HTML -> crawlMetrics.recordTerminalHtmlPersisted();
                    case BINARY -> crawlMetrics.recordTerminalBinaryPersisted();
                    case DUPLICATE -> crawlMetrics.recordContentDedupHit();
                }
            }

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
            Connection connection, String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt)
            throws SQLException {
        // NOTE: DO UPDATE SET url = EXCLUDED.url is a no-op that still runs so xmax distinguishes insert vs conflict.
        final String sql =
                """
                INSERT INTO crawldb.page(site_id, page_type_code, url, relevance_score, next_attempt_at, attempt_count)
                VALUES (?, 'FRONTIER', ?, ?, ?, 0)
                ON CONFLICT (url) DO UPDATE SET url = EXCLUDED.url
                RETURNING id, (xmax = 0) AS inserted
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, siteId);
            statement.setString(2, canonicalUrl);
            statement.setDouble(3, relevanceScore);
            statement.setTimestamp(4, Timestamp.from(nextAttemptAt));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("insertFrontierIfAbsent returned no rows");
                }
                long id = rs.getLong("id");
                boolean inserted = rs.getBoolean("inserted");
                if (crawlMetrics != null && !inserted) {
                    crawlMetrics.recordUrlDedupHit();
                }
                return new InsertFrontierResult(id, inserted);
            }
        }
    }

    /** Assignment: {@code crawldb.image.content_type} is always {@code BINARY} for extracted {@code img} refs. */
    private static final String IMAGE_ROW_CONTENT_TYPE = "BINARY";

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
                statement.setLong(1, pageId);
                statement.setString(2, filename);
                statement.setString(3, IMAGE_ROW_CONTENT_TYPE);
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

        if (!isDiscoveredIngestPolicyEnabled()) {
            return ingestDiscoveredUrlsLegacy(connection, discoveredUrls, accepted, rejected);
        }

        RuntimeConfig cfg = discoveredIngestConfig;
        for (DiscoveredUrl discovered : discoveredUrls) {
            if (discovered == null || discovered.canonicalUrl() == null || discovered.canonicalUrl().isBlank()) {
                rejected.add(new IngestRejection(discovered, "INVALID_URL"));
                continue;
            }
            if (discovered.canonicalUrl().length() > 3000) {
                rejected.add(new IngestRejection(discovered, "URL_TOO_LONG"));
                continue;
            }

            // TS-02 backpressure order: (1) global page budget, (2) frontier high-watermark deferral, (3) robots + ingest.
            long totalPages = countAllPages(connection);
            if (totalPages >= cfg.budgetMaxTotalPages()) {
                discoveredIngestLog.logBudgetDropped(
                        discovered.canonicalUrl(), hostForBudgetLog(discovered.canonicalUrl()));
                rejected.add(new IngestRejection(discovered, "BUDGET_DROPPED"));
                continue;
            }

            Instant now = Instant.now();
            Instant nextAttempt = now;
            long frontierRows = countFrontierPages(connection);
            if (frontierRows >= cfg.budgetMaxFrontierRows()) {
                long delayMs = scoreDeferralDelayMs(discovered.relevanceScore(), cfg);
                nextAttempt = now.plusMillis(delayMs);
                discoveredIngestLog.logFrontierDeferred(
                        discovered.canonicalUrl(), hostForBudgetLog(discovered.canonicalUrl()));
            }

            RobotDecision robotDecision = discoveredIngestRobots.evaluate(discovered.canonicalUrl());
            if (robotDecision.type() == RobotDecisionType.DISALLOWED) {
                rejected.add(new IngestRejection(discovered, "ROBOTS_DISALLOWED"));
                continue;
            }
            if (robotDecision.type() == RobotDecisionType.TEMPORARY_DENY) {
                Instant denyUntil = robotDecision.denyUntilOrDefault();
                if (denyUntil.isAfter(nextAttempt)) {
                    nextAttempt = denyUntil;
                }
            }

            InsertFrontierResult insertResult =
                    insertFrontierIfAbsent(
                            connection,
                            discovered.canonicalUrl(),
                            discovered.siteId(),
                            discovered.relevanceScore(),
                            nextAttempt);
            accepted.add(insertResult.pageId());
            insertLink(connection, discovered.fromPageId(), insertResult.pageId());
        }
        return new IngestResult(accepted, rejected);
    }

    /**
     * Legacy path: insert every validated URL with {@code next_attempt_at = now()} (tests and backward-compatible
     * constructor).
     */
    private IngestResult ingestDiscoveredUrlsLegacy(
            Connection connection,
            Collection<DiscoveredUrl> discoveredUrls,
            List<Long> accepted,
            List<IngestRejection> rejected)
            throws SQLException {
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
                    insertFrontierIfAbsent(
                            connection,
                            discovered.canonicalUrl(),
                            discovered.siteId(),
                            discovered.relevanceScore(),
                            Instant.now());
            accepted.add(insertResult.pageId());
            insertLink(connection, discovered.fromPageId(), insertResult.pageId());
        }
        return new IngestResult(accepted, rejected);
    }

    private static long countAllPages(Connection connection) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM crawldb.page";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static long countFrontierPages(Connection connection) throws SQLException {
        final String sql = "SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'FRONTIER'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * TS-02 frontier high-watermark: lower relevance scores receive longer deferral, clamped to rate-limit bounds.
     */
    private static long scoreDeferralDelayMs(double relevanceScore, RuntimeConfig cfg) {
        double s = Math.max(0.0, Math.min(1.0, relevanceScore));
        long raw = Math.round((1.0 - s) * (long) cfg.rateLimitMaxBackoffMs());
        long min = cfg.rateLimitMinDelayMs();
        long max = cfg.rateLimitMaxBackoffMs();
        return Math.min(max, Math.max(min, raw));
    }

    private static String hostForBudgetLog(String canonicalUrl) {
        try {
            URI u = URI.create(canonicalUrl);
            String h = u.getHost();
            return h != null ? h : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
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
                return rs.getLong(1);
            }
        }
    }

    /**
     * When a smaller {@code page.id} becomes canonical after a larger id already committed as {@code HTML}, those
     * stale owner rows must become {@code DUPLICATE} in the same persist transaction (TS-09).
     */
    private void downgradeStaleHtmlOwnersForSameContentHash(
            Connection connection,
            String contentHash,
            long canonicalOwnerPageId,
            int statusCode,
            Instant fetchedAt)
            throws SQLException {
        final String downgrade =
                """
                UPDATE crawldb.page
                SET page_type_code = 'DUPLICATE',
                    html_content = NULL,
                    content_hash = ?,
                    http_status_code = ?,
                    accessed_time = ?,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_expires_at = NULL,
                    parser_retry_count = 0
                WHERE content_hash = ?
                  AND page_type_code = 'HTML'
                  AND id <> ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(downgrade)) {
            statement.setString(1, contentHash);
            statement.setInt(2, statusCode);
            statement.setTimestamp(3, Timestamp.from(nonNullInstant(fetchedAt)));
            statement.setString(4, contentHash);
            statement.setLong(5, canonicalOwnerPageId);
            statement.executeUpdate();
        }
        final String dedupLinks =
                """
                INSERT INTO crawldb.link (from_page, to_page)
                SELECT p.id, ?
                FROM crawldb.page p
                WHERE p.content_hash = ?
                  AND p.page_type_code = 'DUPLICATE'
                  AND p.id <> ?
                ON CONFLICT (from_page, to_page) DO NOTHING
                """;
        try (PreparedStatement statement = connection.prepareStatement(dedupLinks)) {
            statement.setLong(1, canonicalOwnerPageId);
            statement.setString(2, contentHash);
            statement.setLong(3, canonicalOwnerPageId);
            statement.executeUpdate();
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
                    parser_retry_count = 0,
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
                    claim_expires_at = NULL,
                    parser_retry_count = 0
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
                    claim_expires_at = NULL,
                    parser_retry_count = 0
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, statusCode);
            statement.setTimestamp(2, Timestamp.from(nonNullInstant(fetchedAt)));
            statement.setLong(3, pageId);
            statement.executeUpdate();
        }
    }

    private static Instant nonNullInstant(Instant instant) {
        // Fetch timestamps can be absent in tests or bad upstream data; DB column is NOT NULL.
        return instant == null ? Instant.now() : instant;
    }

    private static boolean isSerializationFailureSqlState(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            if (SQLSTATE_SERIALIZATION_FAILURE.equals(cur.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** Heuristic for TS-15 DB wait/timeout metrics (driver messages vary). */
    private static boolean isTimeoutLikeSQLException(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    private <T> T withSerializableRetry(SqlWork<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxSerializableRetries; attempt++) {
            try {
                return work.execute();
            } catch (SQLException e) {
                last = e;
                if (crawlMetrics != null && isTimeoutLikeSQLException(e)) {
                    crawlMetrics.recordDbTimeoutLikeFailure();
                }
                // Only serialization failures are worth retrying; anything else should fail fast.
                if (!isSerializationFailureSqlState(e) || attempt >= maxSerializableRetries) {
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
