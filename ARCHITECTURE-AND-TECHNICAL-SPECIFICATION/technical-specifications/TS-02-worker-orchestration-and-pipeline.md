# TS-02 Worker Orchestration And Pipeline

## Purpose

Specify single ownership for Stage A and Stage B orchestration.

## Ownership Model

- `Worker` owns Stage B execution and dispatches Stage A ingestion for extracted links.
- `Storage` owns DB transaction details and dedup authority.
- `Frontier` owns claim/reschedule semantics.
- `RobotsTxtCache` owns allow/disallow decisions before frontier insertion.
- queue state transitions are authoritative in DB and must follow `TS-07` and `TS-12`.

## Canonical Worker Loop

```java
while (true) {
    Optional<FrontierRow> row = frontier.claimNextFrontier();
    if (row.isEmpty()) {
        if (shutdownRequested) break;
        if (terminationConditionMet()) break;
        sleep(shortPollMs);
        continue;
    }

    try {
        String domain = domainOf(row.get().url());
        RateLimitDecision decision = rateLimiter.tryAcquire(domain);
        if (decision.isDelayed()) {
            frontier.reschedule(row.get().pageId(), Instant.now().plusNanos(decision.waitNs()), "rate_limited");
            continue;
        }

        robotsTxtCache.ensureLoaded(domain);
        RobotDecision robotDecision = robotsTxtCache.evaluate(row.get().url());
        if (robotDecision == RobotDecision.DISALLOWED) {
            storage.markPageAsError(
                row.get().pageId(),
                "ROBOTS_DISALLOWED",
                "Disallowed after robots refresh for claimed URL"
            );
            continue;
        }
        if (robotDecision == RobotDecision.TEMPORARY_DENY) {
            frontier.reschedule(
                row.get().pageId(),
                robotDecision.denyUntilOrDefault(),
                "ROBOTS_TRANSIENT"
            );
            continue;
        }

        FetchResult fetched = fetcher.fetch(row.get().url());
        ParseResult parsed = parser.parse(row.get().url(), fetched.bodyOrEmpty());
        List<DiscoveredUrl> discovered = parsed.discoveredUrls().stream()
            .map(url -> url.withSource(row.get().pageId()))
            .toList();
        storage.persistFetchOutcomeWithLinks(
            FetchContext.from(row.get()),
            fetched,
            parsed,
            discovered
        );
    } catch (CrawlerException e) {
        if (e.isRetryable()) {
            frontier.reschedule(
                row.get().pageId(),
                e.nextAttemptAt(),
                e.category().name()
            );
        } else {
            storage.markPageAsError(
                row.get().pageId(),
                e.category().name(),
                e.getMessage()
            );
        }
    }
}
```

Important execution rule:
- `claimNextFrontier()` MUST execute an atomic claim mutation (`FRONTIER -> PROCESSING`) and return leased row metadata.
- worker MUST treat missing or expired lease as non-owning state and skip processing.
- graceful shutdown MUST be drain-safe:
  - if shutdown is requested while no row is leased, worker exits loop;
  - if shutdown is requested while a row is leased, worker MUST finish outcome persistence or call `frontier.reschedule(...)` before exiting.

## Stage A Contract (Ingestion)

1. Canonicalize URL.
2. Apply parameter policy and fragment stripping.
3. Robots decision.
4. Insert-if-absent into `page` with `FRONTIER` + score.
5. Insert `link` edge in all cases where target exists or was created.

Concrete URL-exists branch:
- if `insertFrontierIfAbsent` reports conflict, worker must still resolve target page id and call `insertLink(from=current, to=existing)`;
- this preserves assignment-required link graph completeness.

## Stage B Contract (Fetch Path)

1. Claim highest-priority frontier row (`SKIP LOCKED`).
2. Apply rate-limit gate.
3. Ensure robots rules are loaded for claimed row domain before any content fetch.
4. Evaluate robots decision for claimed URL.
5. Fetch and classify (`HTML`, `BINARY`, `DUPLICATE`) only when robots decision is `ALLOWED`.
6. Persist outcome and discovered-link ingestion in one transaction.

Stage B claim semantics are updated:
1. Claim highest-priority **eligible** frontier row where `next_attempt_at <= now()` using atomic `UPDATE ... RETURNING`.
2. Page enters `PROCESSING` with lease owner and expiration.
3. Any retryable outcome transitions row back to `FRONTIER` with future `next_attempt_at`.
4. Any terminal outcome transitions row to `HTML`, `BINARY`, `DUPLICATE`, or `ERROR`.

Stage B robots pre-fetch gate (normative):
- for any claimed URL, worker MUST call `robotsTxtCache.ensureLoaded(domain)` before calling `fetcher.fetch(url)`;
- `ensureLoaded(domain)` MUST fetch `/robots.txt` on first encounter via the same per-domain limiter budget (`TS-06`, `TS-08`);
- worker MUST call `robotsTxtCache.evaluate(canonicalUrl)` only after rules are loaded;
- if decision is `DISALLOWED`, row transitions to terminal non-retryable outcome (`ROBOTS_DISALLOWED`);
- if decision is `TEMPORARY_DENY`, row is rescheduled with future `next_attempt_at`;
- seed/bootstrap URLs follow the same Stage B gate and MUST NOT bypass this contract.

Concrete outcome mapping:
- HTML response -> `page_type_code='HTML'`, `html_content` persisted;
- binary target (`.doc/.docx/.ppt/.pptx`) -> `page_type_code='BINARY'`, `page_data` row inserted, `html_content=NULL`;
- content hash collision -> `page_type_code='DUPLICATE'`, `html_content=NULL`, duplicate linkage persisted.

## Transaction Boundaries

- claim transaction: short-lived; lock only long enough for atomic claim-state mutation;
- persistence transaction: page terminal/retry outcome + link ingestion batch atomically;
- discovered-link ingestion for current source page MUST run as one batch operation (`persistFetchOutcomeWithLinks(...)`), idempotent under retries;
- retry metadata (`attempt_count`, `next_attempt_at`, error diagnostics) MUST update in same transaction as state transition.
- crash window between terminal page persistence and discovered-link ingestion is NOT allowed; both effects must commit or roll back together.

## Failure Handling Contract

Errors are classified by `TS-12`. Worker must call policy-aware recovery path and never leave claimed rows in undefined state.

Minimal recovery path example:
```java
catch (CrawlerException e) {
    if (e.isRetryable()) {
        frontier.reschedule(row.get().pageId(), e.nextAttemptAt(), e.category().name());
    } else {
        storage.markPageAsError(row.get().pageId(), e.category().name(), e.getMessage());
    }
}
```

Recovery guarantees:
- retryable categories: increment attempt budget and set `next_attempt_at`;
- non-retryable categories: transition to terminal `ERROR` exactly once;
- all recovery transitions MUST clear or preserve lease ownership deterministically (no dangling `PROCESSING`).
- assignment-scope minimal policy: if `reschedule`/`markPageAsError` fails transiently, worker may rely on lease expiry + stale-lease recovery (`TS-07`) rather than nested recovery loops.
- recovery-path failures MUST emit structured logs including `workerId`, `pageId`, error category, and lease fields.

## Backpressure And Crawl Budget Contract

- ingestion MUST enforce global crawl budget and queue budget from `TS-13`;
- when budget is exhausted, discovered URL is not inserted to frontier and decision is logged as `BUDGET_DROPPED`;
- budget-hit path MUST remain idempotent and must not mutate already-processed terminal pages.
- required decision order for discovered URLs:
  1. reject if global max pages reached;
  2. reject if per-domain cap reached;
  3. if frontier high-watermark reached, defer low-score URLs by setting future `next_attempt_at`;
  4. otherwise ingest normally.

## Termination Evaluation Contract

`terminationConditionMet()` MUST evaluate all of the following conditions:

1. no claimable rows remain in frontier:
   - `SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='FRONTIER' AND next_attempt_at <= now()`
2. no active leases remain:
   - `SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='PROCESSING' AND claim_expires_at > now()`
3. conditions (1) and (2) remain true continuously for `crawler.frontier.terminationGraceMs` before scheduler declares completion.

## Bootstrap And Startup Sequence

Startup sequence is normative and MUST execute in this order:

1. validate runtime configuration (`TS-13`);
2. verify DB connectivity and schema compatibility;
3. if frontier is empty:
   - canonicalize each configured seed URL (`TS-05`);
   - compute seed relevance score;
   - ensure `site` row exists per seed domain;
   - insert seed page rows as `FRONTIER` with `next_attempt_at=now()` and `attempt_count=0` idempotently;
4. start worker loops.

Bootstrap note:
- startup does not prefetch robots for all seeds in bulk;
- first worker claiming a seed/domain performs robots loading via Stage B robots pre-fetch gate before page content fetch.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/`, `.../scheduler/`, `.../downloader/worker/`
- key file(s):
  - `app/PreferentialCrawler.java` (startup sequence and lifecycle owner)
  - `scheduler/policies/SchedulingPolicy.java`
  - `downloader/worker/WorkerLoop.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/app/`, `.../unit/downloader/worker/`, `.../integration/pipeline/`
