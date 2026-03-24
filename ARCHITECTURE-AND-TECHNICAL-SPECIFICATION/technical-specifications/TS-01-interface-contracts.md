# TS-01 Interface Contracts

## Purpose

Define stable Java contracts for all crawler components before coding.

Drift resolution for this specification set:
- current architecture docs are source of truth when project-plan wording differs;
- URL deduplication is DB-authoritative (no Bloom filter contract required);
- politeness contract is per-domain for this architecture profile.
- storage write-path source of truth is the atomic Stage B contract in `TS-02` + `TS-10`.

## Global Contract Rules

- methods MUST be deterministic for same input and dependencies;
- checked/typed errors SHOULD be used for recoverable failures;
- side effects MUST be explicit in method docs;
- interfaces MUST avoid leaking implementation details.

## Core Interfaces (Proposed Signatures)

```java
public interface Scheduler {
    void start(int workerCount);
    void stopGracefully(Duration timeout);
}

public interface Frontier {
    Optional<FrontierRow> claimNextFrontier();
    void reschedule(long pageId, Instant nextAttemptAt, String reason);
}

public interface Worker {
    void runLoop();
}

public interface Fetcher {
    FetchResult fetch(String canonicalUrl) throws FetchException;
}

public interface Parser {
    ParseResult parse(String canonicalUrl, String html);
}

public interface Canonicalizer {
    CanonicalizationResult canonicalize(String rawUrl, String baseUrl);
}

public interface RobotsTxtCache {
    void ensureLoaded(String domain);
    RobotDecision evaluate(String canonicalUrl);
    BaseRobotRules getRulesForDomain(String domain);
}

public interface RateLimiterRegistry {
    RateLimitDecision tryAcquire(String domain);
}

public interface RelevanceScorer {
    double compute(String canonicalUrl, String anchorText, String contextText);
}

public interface ContentHasher {
    String sha256(String html);
}

public interface Storage {
    Optional<Long> ensureSite(String domain);
    PersistOutcome persistFetchOutcomeWithLinks(
        FetchContext context,
        FetchResult result,
        ParseResult parsed,
        Collection<DiscoveredUrl> discovered
    );
    LinkInsertResult insertLink(long fromPageId, long toPageId);
    IngestResult ingestDiscoveredUrls(Collection<DiscoveredUrl> discoveredUrls);
    InsertFrontierResult insertFrontierIfAbsent(String canonicalUrl, long siteId, double relevanceScore);
    void markPageAsError(long pageId, String category, String message);
}
```

Ownership clarification:
- `Frontier` owns claim/release/reschedule behavior.
- `Storage` owns persistence contracts and SQL atomicity.
- worker orchestration in `TS-02` must call `frontier.claimNextFrontier()` (not a storage claim API).

## Data Contracts

- `FrontierRow`: `pageId`, `url`, `siteId`, `relevanceScore`.
- `FetchResult`: `statusCode`, `contentType`, `body`, `fetchedAt`.
- `ParseResult`: extracted links, images, extracted metadata.
- `RobotDecision`: `ALLOWED`, `DISALLOWED`, or `TEMPORARY_DENY`, optional reason and optional `denyUntil`.
- `RateLimitDecision`: `ALLOWED` or `DELAYED(waitNs)`.
- `DiscoveredUrl`: `rawUrl`, `baseUrl`, `fromPageId`, `anchorText`, `contextText`.
- `FetchContext`: `pageId`, `canonicalUrl`, `siteId`, `attempt`, `claimedAt`.

`RobotsTxtCache` contract detail:
- `ensureLoaded(domain)` loads or refreshes domain robots rules and MUST apply single-flight semantics per domain;
- callers in fetch path MUST call `ensureLoaded(domain)` before `evaluate(canonicalUrl)`;
- `evaluate(canonicalUrl)` assumes rules are loaded and only interprets policy decision.

`RelevanceScorer` contract detail:
- inputs are canonical URL + normalized text context (`anchorText`, `contextText`);
- output MUST be bounded to `[0.0, 1.0]`;
- on missing text inputs, scorer must still return deterministic value (often URL-only heuristic);
- on scoring failure, fallback score is `0.0` (no exception leak to worker loop).

## Invariants

- canonical URL is the only URL form accepted by `Storage`.
- `Storage` is authoritative for URL uniqueness.
- link insertion is required even when discovered target already exists.
- Stage B completion MUST use `persistFetchOutcomeWithLinks(...)` as the only normative storage write path.
- content-hash deduplication MUST NOT be exposed as separate `Storage` methods (no check-then-act API); ownership and `DUPLICATE` vs `HTML` outcomes are determined only inside `persistFetchOutcomeWithLinks` under [TS-09](TS-09-deduplication-url-and-content.md) / [TS-10](TS-10-storage-and-sql-contracts.md).
- the worker MUST call `persistFetchOutcomeWithLinks(...)` **at most once** per successful completion of a **leased** claim cycle; MUST NOT blind-retry after a commit; full rules in [TS-10](TS-10-storage-and-sql-contracts.md) / [TS-02](TS-02-worker-orchestration-and-pipeline.md).
- `insertFrontierIfAbsent` semantics ([TS-10](TS-10-storage-and-sql-contracts.md)):
  - inserted -> new `FRONTIER` row created;
  - conflict -> **sentinel upsert** returns existing row `id` via **`RETURNING`** in one statement (no mandatory separate `SELECT` as primary contract).

## Required Unit Tests

- contract tests for null/empty input handling;
- score range tests (`0.0` to `1.0`);
- idempotence tests for ingestion contracts;
- error mapping tests for interface exceptions.
- ownership contract tests:
  - frontier claim operations are exercised only via `Frontier`;
  - SQL conflict/dedup operations are exercised only via `Storage`.
- robots contract tests:
  - fetch path calls `ensureLoaded(domain)` before `evaluate(...)`;
  - concurrent `ensureLoaded(domain)` calls produce one fetch side effect.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/contracts/`, `.../cli/`, `.../app/`
- key file(s):
  - `contracts/Scheduler.java`, `contracts/Frontier.java`, `contracts/Worker.java`, `contracts/Fetcher.java`, `contracts/Parser.java`, `contracts/Canonicalizer.java`, `contracts/RelevanceScorer.java`, `contracts/Storage.java`
  - bootstrap boundary contracts documented in `cli/Main.java` and `app/PreferentialCrawler.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/contracts/` and `.../unit/app/`
