# TS-07 Frontier And Priority Dequeue

## Responsibility

Provide lock-safe, preferential claim of next URL to process.

## SQL Dequeue Contract

```sql
SELECT p.id, p.url, p.site_id, p.relevance_score
FROM crawldb.page p
WHERE p.page_type_code = 'FRONTIER'
ORDER BY p.relevance_score DESC, p.accessed_time ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

## Queue Semantics

- frontier is DB-backed (`page` table), not in-memory queue;
- ordering MUST be deterministic by score then age;
- multiple workers MUST never claim same row concurrently.

## Reschedule Semantics

- rate-limited row is released and retried later;
- retry metadata SHOULD be persisted for diagnostics;
- rows must not remain locked while sleeping.

## Termination Semantics

- scheduler can stop when no frontier rows remain and no workers in active processing state.

## Required Tests

- descending priority claim order;
- parallel worker uniqueness (`SKIP LOCKED` behavior);
- reschedule path correctness for delayed domains.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/claim/`, `.../queue/enqueue/`, `.../storage/frontier/`
- key file(s): `queue/claim/ClaimService.java`, `queue/enqueue/EnqueueService.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/`, `.../integration/storage/frontier/`, `.../integration/pipeline/`
