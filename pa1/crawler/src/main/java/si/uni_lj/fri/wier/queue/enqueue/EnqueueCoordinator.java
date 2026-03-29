/*
 * Stage A frontier enqueue with TS-06 robots precondition (ensureLoaded + evaluate).
 *
 * Callers: TS-02 worker / discovery pipeline should invoke {@link #tryEnqueue} before inserting URLs into the
 * frontier. The live path {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#ingestDiscoveredUrls}
 * remains unwired until worker orchestration lands; this coordinator is the supported integration point.
 *
 * Invariants: pass the same {@link PolitenessGate} instance used by {@link si.uni_lj.fri.wier.downloader.fetch.HttpFetcher}
 * so first-encounter robots loads stay single-flight across Stage A and Stage B.
 *
 * Created: 2026-03 for TS-06.
 */

package si.uni_lj.fri.wier.queue.enqueue;

import java.util.Objects;
import java.util.function.Predicate;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;

/**
 * Applies robots policy before durable frontier insert (TS-06 decision contract).
 */
public final class EnqueueCoordinator {

    private final RobotsTxtCache robotsTxtCache;
    private final Storage storage;
    private final Predicate<String> hostAllowedForCrawl;

    public EnqueueCoordinator(
            RobotsTxtCache robotsTxtCache, Storage storage, Predicate<String> hostAllowedForCrawl) {
        this.robotsTxtCache = Objects.requireNonNull(robotsTxtCache, "robotsTxtCache");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.hostAllowedForCrawl = Objects.requireNonNull(hostAllowedForCrawl, "hostAllowedForCrawl");
    }

    /**
     * Ensures robots rules are loaded for the URL host, evaluates the canonical URL, then inserts or skips.
     *
     * @param canonicalUrl absolute http(s) URL after TS-05 canonicalization
     * @param siteId owning site row
     * @param relevanceScore frontier priority input
     * @return whether a row was inserted and the robots outcome
     */
    public EnqueueResult tryEnqueue(String canonicalUrl, long siteId, double relevanceScore) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        String domain = HostKeys.domainKey(canonicalUrl);
        if (!hostAllowedForCrawl.test(domain)) {
            return EnqueueResult.rejected("CRAWL_SCOPE");
        }
        robotsTxtCache.ensureLoaded(domain);
        RobotDecision decision = robotsTxtCache.evaluate(canonicalUrl);
        if (decision.type() == RobotDecisionType.DISALLOWED) {
            return EnqueueResult.rejected(decision.reason());
        }
        if (decision.type() == RobotDecisionType.TEMPORARY_DENY) {
            InsertFrontierResult inserted =
                    storage.insertFrontierIfAbsent(
                            canonicalUrl, siteId, relevanceScore, decision.denyUntilOrDefault());
            return EnqueueResult.deferred(inserted, decision.reason());
        }
        InsertFrontierResult inserted = storage.insertFrontierIfAbsent(canonicalUrl, siteId, relevanceScore);
        return EnqueueResult.accepted(inserted);
    }

    /**
     * Outcome of {@link #tryEnqueue} for logging and tests.
     *
     * @param kind REJECTED (robots disallow), DEFERRED (TEMPORARY_DENY with future next_attempt_at), ACCEPTED
     * @param insertResult null when rejected
     * @param reason optional robots reason / diagnostic
     */
    public record EnqueueResult(EnqueueKind kind, InsertFrontierResult insertResult, String reason) {

        public static EnqueueResult rejected(String reason) {
            return new EnqueueResult(EnqueueKind.REJECTED, null, reason);
        }

        public static EnqueueResult deferred(InsertFrontierResult insertResult, String reason) {
            return new EnqueueResult(EnqueueKind.DEFERRED, insertResult, reason);
        }

        public static EnqueueResult accepted(InsertFrontierResult insertResult) {
            return new EnqueueResult(EnqueueKind.ACCEPTED, insertResult, null);
        }
    }

    public enum EnqueueKind {
        REJECTED,
        DEFERRED,
        ACCEPTED
    }
}
