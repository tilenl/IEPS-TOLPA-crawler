# TS-02 Worker Orchestration And Pipeline

## Purpose

Specify single ownership for Stage A and Stage B orchestration.

## Ownership Model

- `Worker` owns Stage B execution and dispatches Stage A ingestion for extracted links.
- `Storage` owns DB transaction details and dedup authority.
- `Frontier` owns claim/reschedule semantics.

## Canonical Worker Loop

```java
while (!shutdownRequested) {
    Optional<FrontierRow> row = storage.claimFrontierRow();
    if (row.isEmpty()) {
        if (terminationConditionMet()) break;
        sleep(shortPollMs);
        continue;
    }

    String domain = domainOf(row.get().url());
    RateLimitDecision decision = rateLimiter.tryAcquire(domain);
    if (decision.isDelayed()) {
        frontier.reschedule(row.get().pageId(), Instant.now().plusNanos(decision.waitNs()), "rate_limited");
        continue;
    }

    FetchResult fetched = fetcher.fetch(row.get().url());
    ParseResult parsed = parser.parse(row.get().url(), fetched.bodyOrEmpty());
    storage.persistFetchOutcome(FetchContext.from(row.get()), fetched, parsed);

    for (DiscoveredUrl discovered : parsed.discoveredUrls()) {
        storage.ingestDiscoveredUrl(discovered.withSource(row.get().pageId()));
    }
}
```

## Stage A Contract (Ingestion)

1. Canonicalize URL.
2. Apply parameter policy and fragment stripping.
3. Robots decision.
4. Insert-if-absent into `page` with `FRONTIER` + score.
5. Insert `link` edge in all cases where target exists or was created.

## Stage B Contract (Fetch Path)

1. Claim highest-priority frontier row (`SKIP LOCKED`).
2. Apply rate-limit gate.
3. Fetch and classify (`HTML`, `BINARY`, `DUPLICATE`).
4. Persist outcome and emit discovered URLs to Stage A.

## Transaction Boundaries

- claim transaction: short-lived; lock only long enough to claim;
- persistence transaction: page outcome + related inserts atomically;
- ingestion transaction: per discovered URL or small batch, idempotent.

## Failure Handling Hook

Errors are classified by `TS-12`. Worker must call policy-aware recovery path and never leave claimed rows in undefined state.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/`, `.../scheduler/`, `.../downloader/worker/`
- key file(s):
  - `app/PreferentialCrawler.java` (startup sequence and lifecycle owner)
  - `scheduler/policies/SchedulingPolicy.java`
  - `downloader/worker/WorkerLoop.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/app/`, `.../unit/downloader/worker/`, `.../integration/pipeline/`
