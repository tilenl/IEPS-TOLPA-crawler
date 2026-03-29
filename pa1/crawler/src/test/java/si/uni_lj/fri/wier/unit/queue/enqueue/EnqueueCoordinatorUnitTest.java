/*
 * TS-06 Stage A enqueue coordinator: robots precondition before frontier insert.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.unit.queue.enqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.CrawlScope;
import si.uni_lj.fri.wier.config.CrawlScopes;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.contracts.LinkInsertResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueCoordinator;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueCoordinator.EnqueueKind;

class EnqueueCoordinatorUnitTest {

    private static final Predicate<String> GITHUB_ONLY = CrawlScopes.persistencePredicate(CrawlScope.GITHUB);

    @Test
    void tryEnqueue_temporaryDeny_insertsWithExplicitNextAttempt() {
        Instant denyUntil = Instant.parse("2026-03-10T15:00:00Z");
        RobotsTxtCache robots =
                new ScriptedRobotsCache(RobotDecision.temporaryDeny(denyUntil, "ROBOTS_TRANSIENT_HTTP"));
        RecordingStorage storage = new RecordingStorage();
        EnqueueCoordinator coordinator = new EnqueueCoordinator(robots, storage, GITHUB_ONLY);
        var result = coordinator.tryEnqueue("https://github.com/org/page", 9L, 0.5);
        assertEquals(EnqueueKind.DEFERRED, result.kind());
        assertNotNull(result.insertResult());
        assertEquals(denyUntil, storage.nextAttemptSeen);
        assertEquals("https://github.com/org/page", storage.urlSeen);
    }

    @Test
    void tryEnqueue_disallowed_skipsInsert() {
        RobotsTxtCache robots = new ScriptedRobotsCache(RobotDecision.disallowed("ROBOTS_DISALLOWED"));
        RecordingStorage storage = new RecordingStorage();
        EnqueueCoordinator coordinator = new EnqueueCoordinator(robots, storage, GITHUB_ONLY);
        var result = coordinator.tryEnqueue("https://github.com/org/search", 1L, 0.1);
        assertEquals(EnqueueKind.REJECTED, result.kind());
        assertNull(result.insertResult());
        assertNull(storage.urlSeen);
    }

    @Test
    void tryEnqueue_allowed_insertsImmediately() {
        RobotsTxtCache robots = new ScriptedRobotsCache(RobotDecision.allowed());
        RecordingStorage storage = new RecordingStorage();
        EnqueueCoordinator coordinator = new EnqueueCoordinator(robots, storage, GITHUB_ONLY);
        var result = coordinator.tryEnqueue("https://github.com/org/ok", 2L, 0.8);
        assertEquals(EnqueueKind.ACCEPTED, result.kind());
        assertNotNull(result.insertResult());
        assertEquals("https://github.com/org/ok", storage.urlSeen);
        assertNull(storage.nextAttemptSeen);
    }

    @Test
    void tryEnqueue_offScope_rejectsBeforeRobots() {
        RobotsTxtCache robots = new ScriptedRobotsCache(RobotDecision.allowed());
        RecordingStorage storage = new RecordingStorage();
        EnqueueCoordinator coordinator = new EnqueueCoordinator(robots, storage, GITHUB_ONLY);
        var result = coordinator.tryEnqueue("https://example.com/page", 1L, 0.5);
        assertEquals(EnqueueKind.REJECTED, result.kind());
        assertEquals("CRAWL_SCOPE", result.reason());
        assertNull(storage.urlSeen);
    }

    private static final class ScriptedRobotsCache implements RobotsTxtCache, RateLimiterRegistry {
        private static final BaseRobotRules ALLOW =
                new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

        private final RobotDecision decision;

        private ScriptedRobotsCache(RobotDecision decision) {
            this.decision = decision;
        }

        @Override
        public void ensureLoaded(String domain) {
            // Contract satisfied for this unit test without HTTP.
        }

        @Override
        public RobotDecision evaluate(String canonicalUrl) {
            return decision;
        }

        @Override
        public BaseRobotRules getRulesForDomain(String domain) {
            return ALLOW;
        }

        @Override
        public RateLimitDecision tryAcquire(String domain) {
            return RateLimitDecision.allowed();
        }
    }

    private static final class RecordingStorage implements Storage {
        String urlSeen;
        Instant nextAttemptSeen;

        @Override
        public Optional<Long> ensureSite(String domain) {
            return Optional.empty();
        }

        @Override
        public PersistOutcome persistFetchOutcomeWithLinks(
                si.uni_lj.fri.wier.contracts.FetchContext context,
                si.uni_lj.fri.wier.contracts.FetchResult result,
                si.uni_lj.fri.wier.contracts.ParseResult parsed,
                Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discovered) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LinkInsertResult insertLink(long fromPageId, long toPageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngestResult ingestDiscoveredUrls(
                Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discoveredUrls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InsertFrontierResult insertFrontierIfAbsent(
                String canonicalUrl, long siteId, double relevanceScore) {
            urlSeen = canonicalUrl;
            return new InsertFrontierResult(100L, true);
        }

        @Override
        public InsertFrontierResult insertFrontierIfAbsent(
                String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
            urlSeen = canonicalUrl;
            nextAttemptSeen = nextAttemptAt;
            return new InsertFrontierResult(101L, true);
        }

        @Override
        public void markPageAsError(long pageId, String category, String message) {
            throw new UnsupportedOperationException();
        }
    }
}
