# TS-01 Interface Contracts

## Purpose

Define stable Java contracts for all crawler components before coding.

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
    Optional<FrontierRow> claimFrontierRow();
    PersistOutcome persistFetchOutcome(FetchContext context, FetchResult result, ParseResult parsed);
    LinkInsertResult insertLink(long fromPageId, long toPageId);
    IngestResult ingestDiscoveredUrl(DiscoveredUrl discoveredUrl);
}
```

## Data Contracts

- `FrontierRow`: `pageId`, `url`, `siteId`, `relevanceScore`.
- `FetchResult`: `statusCode`, `contentType`, `body`, `fetchedAt`.
- `ParseResult`: extracted links, images, extracted metadata.
- `RobotDecision`: `ALLOWED` or `DISALLOWED`, optional reason.
- `RateLimitDecision`: `ALLOWED` or `DELAYED(waitNs)`.

## Invariants

- canonical URL is the only URL form accepted by `Storage`.
- `Storage` is authoritative for URL uniqueness.
- link insertion is required even when discovered target already exists.

## Required Unit Tests

- contract tests for null/empty input handling;
- score range tests (`0.0` to `1.0`);
- idempotence tests for ingestion contracts;
- error mapping tests for interface exceptions.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/contracts/`, `.../cli/`, `.../app/`
- key file(s):
  - `contracts/Scheduler.java`, `contracts/Frontier.java`, `contracts/Worker.java`, `contracts/Fetcher.java`, `contracts/Parser.java`, `contracts/Storage.java`
  - bootstrap boundary contracts documented in `cli/Main.java` and `app/PreferentialCrawler.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/contracts/` and `.../unit/app/`
