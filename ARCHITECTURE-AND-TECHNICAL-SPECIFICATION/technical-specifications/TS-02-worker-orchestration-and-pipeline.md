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
            frontier.reschedule(
                row.get().pageId(),
                Instant.now().plusNanos(decision.waitNs()),
                "FETCH_HTTP_OVERLOAD",
                "rate_limited");
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
                "ROBOTS_TRANSIENT",
                "temporary robots deny");
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
                e.category().name(),
                e.getMessage()
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
  - if shutdown is requested while a row is leased, worker MUST finish outcome persistence or apply `frontier.reschedule(...)` / `storage.markPageAsError(...)` after bounded recovery-path retries before exiting.
- abnormal termination (process crash, `kill -9`, OOM) MAY leave leased rows in `PROCESSING`; this is expected and resolved by lease expiry plus stale-lease recovery (`TS-07`).

## Stage A Contract (Ingestion)

1. Canonicalize URL.
2. Apply parameter policy and fragment stripping.
3. Robots decision.
4. Insert-if-absent into `page` with `FRONTIER` + score.
5. Insert `link` edge in all cases where target exists or was created.

Concrete URL-exists branch:
- `insertFrontierIfAbsent` MUST return `page_id` for both insert and conflict paths ([TS-10](TS-10-storage-and-sql-contracts.md) sentinel upsert + `RETURNING`);
- worker MUST call `insertLink(from=current, to=targetPageId)` using that `page_id` so the link graph stays complete.

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

**Stage B content fetch and redirect chain (normative):** `fetcher.fetch(claimedUrl)` MUST implement **hop-by-hop** HTTP redirects per [TS-03](TS-03-fetcher-specification.md): **robots** + **rate limit** run **before every hop** (each hop’s URL/domain). The worker’s **first** `rateLimiter.tryAcquire(domain)` on the **claimed** row (before `fetch`) remains: if delayed, **`frontier.reschedule`** — no busy-wait ([TS-08](TS-08-rate-limiting-and-backoff.md)). **Inside** the fetcher’s redirect loop, if the limiter delays a **subsequent** hop, the implementation MAY **block** (e.g. virtual-thread sleep) **within remaining `claim_expires_at` margin** and then continue the same chain; if the delay would exceed safe lease margin, the fetch MUST surface a **rate-limit / lease** outcome so the worker **`frontier.reschedule`s** the row and the next claim **restarts from the original claimed URL** (in-memory redirect state is discarded). Document lease-margin rule in implementation notes.

Concrete outcome mapping:
- HTML response -> `page_type_code='HTML'`, `html_content` persisted;
- non-HTML response -> `page_type_code='BINARY'`, `html_content=NULL`; when the resource maps to seeded types `PDF`/`DOC`/`DOCX`/`PPT`/`PPTX`, a `page_data` row is upserted with **`data` NULL** (type-only placeholder; no blob persistence);
- content hash collision -> `page_type_code='DUPLICATE'`, `html_content=NULL`, duplicate linkage persisted.

## Transaction Boundaries

- claim transaction: short-lived; lock only long enough for atomic claim-state mutation;
- persistence transaction: page terminal/retry outcome + link ingestion batch atomically; **`persistFetchOutcomeWithLinks`** MUST run the **entire** unit under **`SERIALIZABLE`** isolation with **`40001`** bounded retries per [TS-09](TS-09-deduplication-url-and-content.md) / [TS-10](TS-10-storage-and-sql-contracts.md);
- discovered-link ingestion for current source page MUST run as one batch operation (`persistFetchOutcomeWithLinks(...)`), idempotent under retries;
- retry metadata (`attempt_count`, `next_attempt_at`, error diagnostics) MUST update in same transaction as state transition.
- crash window between terminal page persistence and discovered-link ingestion is NOT allowed; both effects must commit or roll back together.

## Stage B `persistFetchOutcomeWithLinks` invocation (normative)

- **Process contract** (not a DB dedupe key for this assignment): the worker MUST call **`persistFetchOutcomeWithLinks` at most once** per **successful** handling of a **single lease** (claim → fetch → parse → persist). **Only `Storage`** performs writes for that outcome and discovered-link batch; see [TS-10](TS-10-storage-and-sql-contracts.md) and [TS-01](TS-01-interface-contracts.md).

## Failure Handling Contract

Errors are classified by `TS-12`. Worker must call policy-aware recovery path and never leave claimed rows in undefined state.

Minimal recovery path example:
```java
catch (CrawlerException e) {
    if (e.isRetryable()) {
        frontier.reschedule(row.get().pageId(), e.nextAttemptAt(), e.category().name(), e.getMessage());
    } else {
        storage.markPageAsError(row.get().pageId(), e.category().name(), e.getMessage());
    }
}
```

Recovery guarantees:
- retryable categories: increment attempt budget and set `next_attempt_at`;
- non-retryable categories: transition to terminal `ERROR` exactly once;
- all recovery transitions MUST clear or preserve lease ownership deterministically (no dangling `PROCESSING`).
- recovery-path transitions (`frontier.reschedule`, `storage.markPageAsError`) MUST use bounded transient retry: `crawler.recoveryPath.maxAttempts=3` with short exponential backoff + jitter (`crawler.recoveryPath.baseBackoffMs`, `crawler.retry.jitterMs`).
- retries apply only to transient DB failures; non-transient failures MUST be rethrown immediately.
- if bounded retries are exhausted, worker MAY rely on lease expiry + stale-lease recovery (`TS-07`) as final fallback.
- recovery-path failures MUST emit structured logs including `workerId`, `pageId`, error category, and lease fields.

**Optional enhancement (recommended):** after recovery-path retries are **exhausted**, the implementation MAY issue a **last-chance idempotent** `UPDATE` via `Storage` (conditional on `page.id`, `page_type_code='PROCESSING'`, `claimed_by` = this worker) to either (1) transition **`PROCESSING → FRONTIER`**, clear lease fields, and set `next_attempt_at` / attempt metadata per the same rules as `reschedule`, or (2) apply **`markPageAsError`** when the failure category is terminal—**without** waiting for natural lease expiry. The update MUST be safe to retry and MUST NOT overwrite a row another worker has already completed. If this path is **not** implemented, stale-lease recovery remains the **authoritative** fallback ([TS-07](TS-07-frontier-and-priority-dequeue.md)).

## Backpressure And Crawl Budget Contract

- ingestion MUST enforce global crawl budget and queue budget from `TS-13`;
- when budget is exhausted, discovered URL is not inserted to frontier and decision is logged as `BUDGET_DROPPED`;
- budget-hit path MUST remain idempotent and must not mutate already-processed terminal pages.
- required decision order for discovered URLs:
  1. reject if global max pages reached;
  2. if frontier high-watermark reached, defer low-score URLs by setting future `next_attempt_at`;
  3. otherwise ingest normally.

## Termination Evaluation Contract

`terminationConditionMet()` MUST evaluate all of the following conditions:

1. **no queue or in-flight work remains** — there are **zero** rows still in `FRONTIER` or `PROCESSING` (including `FRONTIER` rows with `next_attempt_at > now()`, which are delayed pending work, not completion):
   - `SELECT COUNT(*) FROM crawldb.page WHERE page_type_code IN ('FRONTIER', 'PROCESSING')` MUST be `0`
2. condition (1) remains true **continuously** for `crawler.frontier.terminationGraceMs` before the scheduler declares completion.

Rationale: treating “no claimable frontier” alone as completion contradicts [TS-07](TS-07-frontier-and-priority-dequeue.md) (delayed `FRONTIER` rows are still pending work). A single aggregate over `FRONTIER` and `PROCESSING` matches “all pages have reached a terminal `page_type_code` (`HTML`, `BINARY`, `DUPLICATE`, `ERROR`).”

For **intentional abandonment** of deferred backlog, the run MUST transition or remove those rows (e.g. explicit drain/pause policy) so the count above can reach zero; see [03-system-sequence-and-dataflow.md](../03-system-sequence-and-dataflow.md).

## Bootstrap And Startup Sequence

Startup sequence is normative and MUST execute in this order:

1. validate runtime configuration (`TS-13`);
2. verify DB connectivity and schema compatibility;
3. if frontier is empty:
   - canonicalize each configured seed URL (`TS-05`);
   - compute seed relevance score;
   - ensure `site` row exists per seed domain;
   - insert seed page rows as `FRONTIER` with `next_attempt_at=now()` and `attempt_count=0` idempotently;
4. run startup lease recovery loop (`PROCESSING` with expired `claim_expires_at` -> `FRONTIER`) in bounded batches until no stale leases remain;
5. start the scheduler: one virtual thread runs the **domain frontier pump** (priority wake queue); the same executor runs up to `crawler.nCrawlers` concurrent **pipeline** tasks (`WorkerLoop.runClaimedPipeline`). The pump performs **domain-scoped** `claim` then outer `tryAcquire` before handing a lease to a pipeline (see [TS-07](TS-07-frontier-and-priority-dequeue.md), [TS-08](TS-08-rate-limiting-and-backoff.md)).

Bootstrap note:
- startup does not prefetch robots for all seeds in bulk;
- first worker claiming a seed/domain performs robots loading via Stage B robots pre-fetch gate before page content fetch.
- startup lease recovery is reclaim-only (it does not fetch or claim work for execution).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/`, `.../scheduler/`, `.../downloader/worker/`
- key file(s):
  - `app/PreferentialCrawler.java` (startup sequence and lifecycle owner)
  - `scheduler/VirtualThreadCrawlerScheduler.java`, `scheduler/DomainFrontierPump.java`
  - `scheduler/policies/SchedulingPolicy.java`
  - `downloader/worker/WorkerLoop.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/app/`, `.../unit/downloader/worker/`, `.../integration/pipeline/`
