# TS-12 Error Model And Recovery Policy

## Purpose

Define uniform error classification, retry behavior, and terminal failure handling.

## Error Taxonomy

- `INVALID_URL` (non-retryable): malformed input/canonicalization failure.
- `ROBOTS_DISALLOWED` (non-retryable): policy block.
- `FETCH_TIMEOUT` (retryable): timeout/connect issues.
- `FETCH_HTTP_OVERLOAD` (retryable): HTTP 429/503.
- `FETCH_HTTP_CLIENT` (usually non-retryable): stable 4xx (except 429).
- `PARSER_FAILURE` (retryable once, then terminal).
- `DB_TRANSIENT` (retryable): transient SQL/network outage.
- `DB_CONSTRAINT` (non-retryable): integrity violation signaling logic bug.

## Recovery Policy Matrix


| Category            | Retry | Max Attempts | Delay Policy         | Terminal Action               |
| ------------------- | ----- | ------------ | -------------------- | ----------------------------- |
| INVALID_URL         | No    | 0            | None                 | Skip URL, log reason          |
| ROBOTS_DISALLOWED   | No    | 0            | None                 | Drop URL before frontier      |
| ROBOTS_TRANSIENT    | Yes   | 3            | Fixed + jitter       | Temporary deny, then error    |
| FETCH_TIMEOUT       | Yes   | 3            | Exponential          | Mark failed after limit       |
| FETCH_HTTP_OVERLOAD | Yes   | 5            | Domain backoff       | Keep frontier entry           |
| FETCH_HTTP_CLIENT   | No    | 0            | None                 | Persist status and stop retry |
| PARSER_FAILURE      | Yes   | 1            | Short delay          | Mark parse error state        |
| DB_TRANSIENT        | Yes   | 5            | Exponential + jitter | Requeue operation             |
| DB_CONSTRAINT       | No    | 0            | None                 | Log critical and quarantine   |


## State Handling

- failed terminal pages SHOULD be marked with diagnostic metadata;
- retries MUST not duplicate link/page inserts;
- worker loop MUST continue after handled errors;
- queue transitions MUST be durable and transactional:
  - retryable: `PROCESSING -> FRONTIER` with `attempt_count=attempt_count+1`, `next_attempt_at`, and error diagnostics;
  - terminal: `PROCESSING -> ERROR` with final diagnostics and cleared lease fields.
- terminal-state helper contract: `markPageAsError(pageId, category, message)` with last-attempt timestamp.

Retry budget rule:
- `attempt_count` is authoritative and persisted on `page`;
- if `attempt_count >= maxAttempts(category)`, next transition MUST be terminal `ERROR`.

Eligibility rule:
- only rows where `page_type_code='FRONTIER'` and `next_attempt_at <= now()` may be claimed.

## Observability Requirements

- every failure log includes: category, URL, domain, page id, attempt count;
- metrics:
  - error count by category
  - retry count
  - terminal failures.
  - delayed queue age and oldest overdue retry.

## Required Tests

- classification tests for each error class;
- retry limit enforcement;
- non-retryable skip behavior;
- idempotence under repeated transient failures.
- terminal mark-up test:
  - when retry budget exhausted, page is marked with diagnostic metadata exactly once.
- restart persistence test:
  - attempt budget and `next_attempt_at` survive process restart and are re-enforced.
- eligibility test:
  - delayed rows are not claimable before due time.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/error/`, `.../app/`, `.../downloader/worker/`
- key file(s): `error/CrawlerErrorCategory.java`, `error/RecoveryPolicy.java`, `downloader/worker/WorkerLoop.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/error/` and `.../integration/pipeline/`

