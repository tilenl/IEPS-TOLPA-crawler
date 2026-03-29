# TS-07 Frontier And Priority Dequeue

## Responsibility

Provide lock-safe, preferential claim of next URL to process with an atomic claim-state transition.

## Queue State Model

Page queue state is represented by `page.page_type_code` plus lease/scheduling columns:

- `FRONTIER`: eligible or delayed queue item;
- `PROCESSING`: currently leased by one worker;
- terminal outcomes: `HTML`, `BINARY`, `DUPLICATE`, `ERROR`.

`FRONTIER` rows MUST be considered claimable only when `next_attempt_at <= now()`.

## SQL Claim Contract (Normative)

Claim MUST be implemented as a single statement that both selects and mutates:

```sql
WITH candidate AS (
    SELECT p.id
    FROM crawldb.page p
    WHERE p.page_type_code = 'FRONTIER'
      AND p.next_attempt_at <= now()
    ORDER BY p.relevance_score DESC, p.next_attempt_at ASC, p.accessed_time ASC, p.id ASC
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
UPDATE crawldb.page p
SET page_type_code = 'PROCESSING',
    claimed_by = :worker_id,
    claimed_at = now(),
    claim_expires_at = now() + (:lease_seconds || ' seconds')::interval
FROM candidate c
WHERE p.id = c.id
RETURNING p.id, p.url, p.site_id, p.relevance_score, p.attempt_count, p.next_attempt_at;
```

Standalone `SELECT ... FOR UPDATE SKIP LOCKED` without state mutation is NOT allowed.

## Queue Semantics

- frontier is DB-backed (`page` table), not in-memory queue;
- ordering MUST be deterministic by score then due-time then age then id;
- multiple workers MUST never hold lease on same row concurrently;
- claim lease MUST be durable in DB (`claimed_by`, `claimed_at`, `claim_expires_at`);
- **`PROCESSING` lease invariant ([TS-11](TS-11-database-schema-and-migrations.md)):** whenever `page_type_code = 'PROCESSING'`, **`claimed_by`**, **`claimed_at`**, and **`claim_expires_at`** MUST all be **non-null** (`CHECK` constraint on `crawldb.page`). Otherwise `claim_expires_at < now()` recovery never matches **NULL**, orphaning rows. Non-`PROCESSING` rows keep lease columns **NULL**.
- claim MUST set all three lease columns in the **same** `UPDATE` as `PROCESSING`; terminal/reschedule transitions MUST clear lease fields when leaving `PROCESSING` without violating the `CHECK` (typically one atomic `UPDATE` that changes `page_type_code` and nulls lease columns together).
- index `idx_page_frontier_priority` is required to keep claim performance stable.
- **Domain-scoped claim (pump path):** the implementation MUST support an atomic claim whose candidate set is restricted to rows whose `site.domain` equals a given crawl-domain key, with the **same** ordering as the global claim (score, `next_attempt_at`, `accessed_time`, id). Index `idx_page_frontier_site_priority` (and `idx_site_domain`) supports this path. The **pump** is responsible for scheduling domains; Stage A MUST notify the pump after **committed** FRONTIER inserts (see `PageRepository` wake notifier + post-commit discovery ingest).

Worker identity contract:
- `claimed_by` MUST be a stable internal worker identity (recommended format: startup UUID + host/pid or equivalent);
- worker identity MUST NOT be user-controlled input;
- claim/reschedule/recovery logs MUST include the same worker identity value used in `claimed_by`.

## Lease Recovery Semantics

- rows in `PROCESSING` with `claim_expires_at < now()` MUST be eligible for lease recovery;
- recovery path resets row to `FRONTIER`, increments diagnostic counter, clears stale lease owner;
- startup MUST run a reclaim-only lease recovery loop before worker start (`TS-02`) using `crawler.frontier.startupLeaseRecoveryBatchSize` (default `100`) until no stale leases remain;
- recovery MUST also run as part of `claimNextFrontier()` before candidate selection, with bounded batch size `crawler.frontier.leaseRecoveryBatchSize` (default `10`);
- each claim cycle MUST recover at most `leaseRecoveryBatchSize` stale rows to avoid long recovery transactions;
- row recovery MUST be idempotent.

Reference bounded recovery SQL pattern:

```sql
WITH stale AS (
    SELECT p.id
    FROM crawldb.page p
    WHERE p.page_type_code = 'PROCESSING'
      AND p.claim_expires_at < now()
    ORDER BY p.claim_expires_at ASC, p.id ASC
    LIMIT :lease_recovery_batch_size
    FOR UPDATE SKIP LOCKED
)
UPDATE crawldb.page p
SET page_type_code = 'FRONTIER',
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL
FROM stale s
WHERE p.id = s.id;
```

## Reschedule Semantics

- retryable failure or rate-limit delay transitions row from `PROCESSING` to `FRONTIER`;
- reschedule MUST set `next_attempt_at` to computed due time;
- reschedule MUST clear lease fields (`claimed_by`, `claimed_at`, `claim_expires_at`);
- retry metadata MUST be persisted for diagnostics and retry-budget enforcement.
- if a reschedule/error-mark update fails transiently, worker MUST apply bounded transient retries first (`crawler.recoveryPath.maxAttempts=3`, short exponential backoff + jitter), then MAY rely on lease expiry + stale-lease recovery path as final fallback.
- abnormal termination may still leave leased rows transiently until expiry; this is expected and handled by stale-lease recovery.

Seed bootstrap behavior:
- when frontier is empty at startup, configured seeds MUST be canonicalized (`TS-05`) before insertion;
- each canonicalized seed MUST receive a relevance score before insertion (from configured seed score policy or `RelevanceScorer`);
- when frontier is empty at startup, canonicalized seed URLs are inserted as `page_type_code='FRONTIER'`;
- seeded rows MUST set `next_attempt_at = now()` and `attempt_count = 0`;
- repeated bootstrap runs MUST remain idempotent by canonical URL uniqueness.

## Termination Semantics

- scheduler can stop when all of the following are true:
  1. **no `FRONTIER` or `PROCESSING` rows exist** — delayed `FRONTIER` rows (`next_attempt_at > now()`) are **pending work** and MUST keep the crawl **not** complete until they are processed, terminalized, or removed by an explicit abandonment/drain policy;
  2. condition (1) remains true continuously for `crawler.frontier.terminationGraceMs`.
- aligns with [TS-02](TS-02-worker-orchestration-and-pipeline.md) `terminationConditionMet()` and [03-system-sequence-and-dataflow.md](../03-system-sequence-and-dataflow.md).

Reference termination check:

```sql
SELECT COUNT(*) FROM crawldb.page
WHERE page_type_code IN ('FRONTIER', 'PROCESSING');
```

Completion requires this count to be **zero** (plus the grace window in [TS-02](TS-02-worker-orchestration-and-pipeline.md)).

## Required Tests

- descending priority claim order with deterministic tie-breaks;
- parallel worker uniqueness for atomic claim (`UPDATE ... RETURNING` path);
- no immediate reclaim of delayed rows (`next_attempt_at > now()`);
- lease expiration recovery path returns stale `PROCESSING` rows to `FRONTIER` with per-cycle cap enforcement;
- schema / invariant test: `PROCESSING` row cannot be inserted or updated with null `claim_expires_at` (DB `CHECK`);
- startup lease-recovery test proving stale lease backlog is reclaimed before first worker claim;
- reschedule path correctness for delayed domains.
- domain-scoped claim ordering and isolation from other domains’ FRONTIER rows.
- termination grace-window test preventing premature completion when frontier/leases oscillate.
- seed bootstrap canonicalization test (seed variants collapse to one canonical URL);
- seed bootstrap scoring test (seed rows have deterministic non-null score at insertion).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/claim/`, `.../queue/enqueue/`, `.../storage/frontier/`
- key file(s): `queue/claim/ClaimService.java`, `queue/enqueue/EnqueueService.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/`, `.../integration/storage/frontier/`, `.../integration/pipeline/`
