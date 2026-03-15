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
- index `idx_page_frontier_priority` is required to keep claim performance stable.

## Lease Recovery Semantics

- rows in `PROCESSING` with `claim_expires_at < now()` MUST be eligible for lease recovery;
- recovery path resets row to `FRONTIER`, increments diagnostic counter, clears stale lease owner;
- recovery MUST happen before normal claim in each poll cycle or via dedicated sweeper task;
- row recovery MUST be idempotent.

## Reschedule Semantics

- retryable failure or rate-limit delay transitions row from `PROCESSING` to `FRONTIER`;
- reschedule MUST set `next_attempt_at` to computed due time;
- reschedule MUST clear lease fields (`claimed_by`, `claimed_at`, `claim_expires_at`);
- retry metadata MUST be persisted for diagnostics and retry-budget enforcement.

Seed bootstrap behavior:
- when frontier is empty at startup, configured seed URLs are inserted as `page_type_code='FRONTIER'`;
- seeded rows MUST set `next_attempt_at = now()` and `attempt_count = 0`;
- repeated bootstrap runs MUST remain idempotent by URL uniqueness.

## Termination Semantics

- scheduler can stop when no claimable frontier rows remain (`FRONTIER` with `next_attempt_at <= now()`) and no workers have active leases;
- rows delayed into the future MUST be treated as pending work, not completion.

## Required Tests

- descending priority claim order with deterministic tie-breaks;
- parallel worker uniqueness for atomic claim (`UPDATE ... RETURNING` path);
- no immediate reclaim of delayed rows (`next_attempt_at > now()`);
- lease expiration recovery path returns stale `PROCESSING` rows to `FRONTIER`;
- reschedule path correctness for delayed domains.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/claim/`, `.../queue/enqueue/`, `.../storage/frontier/`
- key file(s): `queue/claim/ClaimService.java`, `queue/enqueue/EnqueueService.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/`, `.../integration/storage/frontier/`, `.../integration/pipeline/`
