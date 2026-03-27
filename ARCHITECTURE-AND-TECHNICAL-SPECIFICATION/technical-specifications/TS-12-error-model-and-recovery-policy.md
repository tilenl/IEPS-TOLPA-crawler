# TS-12 Error Model And Recovery Policy

## Purpose

Define uniform error classification, retry behavior, and terminal failure handling.

## Error Taxonomy

- `INVALID_URL` (non-retryable): malformed input/canonicalization failure.
- `ROBOTS_DISALLOWED` (non-retryable): policy block from stable robots evaluation.
- `ROBOTS_TRANSIENT` (retryable): robots fetch or transient deny window per [TS-06](TS-06-robots-policy-and-site-metadata.md); bounded retries then terminal robots error when policy exhausts.
- `FETCH_TIMEOUT` (retryable): timeout/connect issues.
- `FETCH_HTTP_OVERLOAD` (retryable): HTTP 429/503.
- `FETCH_CAPACITY_EXHAUSTED` (retryable): headless browser slot not acquired within timeout ([TS-03](TS-03-fetcher-specification.md)); deterministic plain-HTTP fallback and/or requeue per fetch policy.
- `FETCH_HTTP_CLIENT` (usually non-retryable): stable 4xx (except 429).
- `PARSER_FAILURE` (retryable once for parser stage, then terminal): HTML parse/extraction failure after a successful fetch handoff to the parser.
- `DB_TRANSIENT` (retryable): transient SQL/network outage.
- `DB_CONSTRAINT` (non-retryable): integrity violation signaling logic bug.

## Recovery Policy Matrix

Max attempt counts for categories below marked with `TS-13` keys are **governed** by the listed `crawler.retry.maxAttempts.*` property (validated at startup).

| Category                   | Retry | Max Attempts                         | Delay Policy         | Terminal Action                              |
| -------------------------- | ----- | ------------------------------------ | -------------------- | -------------------------------------------- |
| INVALID_URL                | No    | 0                                    | None                 | Skip URL, log reason                         |
| ROBOTS_DISALLOWED          | No    | 0                                    | None                 | Drop URL before frontier                     |
| ROBOTS_TRANSIENT           | Yes   | 3                                    | Fixed + jitter       | Temporary deny, then error                   |
| FETCH_TIMEOUT              | Yes   | `crawler.retry.maxAttempts.fetchTimeout` | Exponential      | Mark failed after limit                      |
| FETCH_HTTP_OVERLOAD        | Yes   | `crawler.retry.maxAttempts.fetchOverload` | Domain backoff  | Keep frontier entry                          |
| FETCH_CAPACITY_EXHAUSTED   | Yes   | `crawler.retry.maxAttempts.fetchCapacity` | Short delay     | Plain-HTTP fallback / requeue per [TS-03](TS-03-fetcher-specification.md), then terminal |
| FETCH_HTTP_CLIENT          | No    | 0                                    | None                 | Persist status and stop retry                |
| PARSER_FAILURE             | Yes   | 1 (parser stage only; see below)     | Short delay          | Mark parse error state                       |
| DB_TRANSIENT               | Yes   | `crawler.retry.maxAttempts.dbTransient` | Exponential + jitter | Requeue operation                         |
| DB_CONSTRAINT              | No    | 0                                    | None                 | Log critical and quarantine                  |

## State Handling

- failed terminal pages SHOULD be marked with diagnostic metadata;
- retries MUST not duplicate link/page inserts;
- worker loop MUST continue after handled errors;
- queue transitions MUST be durable and transactional:
  - retryable (fetch-stage): `PROCESSING -> FRONTIER` with `attempt_count=attempt_count+1`, `next_attempt_at`, and bound error diagnostics (`last_error_*`);
  - retryable (`PARSER_FAILURE`): same transition but `parser_retry_count=parser_retry_count+1` and **no** increment to `attempt_count` (see normative subsection below);
  - terminal: `PROCESSING -> ERROR` with final diagnostics and cleared lease fields.
- terminal-state helper contract: `markPageAsError(pageId, category, message)` with last-attempt timestamp; MUST fail fast if the row is not `PROCESSING` (strict single terminal write).
- persistence: `crawldb.page.parser_retry_count` (schema v4+) holds parser-stage reschedule consumption; reset to `0` on successful HTML/DUPLICATE/BINARY completion paths.

Retry budget rule:
- `attempt_count` is authoritative and persisted on `page`;
- if `attempt_count >= maxAttempts(category)`, next transition MUST be terminal `ERROR`.

### `PARSER_FAILURE` vs fetch-stage `attempt_count` (normative)

When applying the `PARSER_FAILURE` row (one parser retry before terminal parse error), implementations MUST **not** treat prior **fetch-stage** attempts (`FETCH_TIMEOUT`, `FETCH_HTTP_OVERLOAD`, `FETCH_CAPACITY_EXHAUSTED`, etc.) as having consumed that single parser retry. Only failures **after** a successful fetch body is handed to the parser, classified as `PARSER_FAILURE`, count toward the parser retry cap. Equivalently: **for `PARSER_FAILURE`, ignore previous fetch attempts when applying the one parser retry.** Other categories continue to use persisted `attempt_count` against `maxAttempts(category)` as usual.

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
- `PARSER_FAILURE` precedence: after one or more fetch retries, first parser failure still receives one parser retry before terminal parse error.
- `FETCH_CAPACITY_EXHAUSTED` and `ROBOTS_TRANSIENT` classification and retry limits per matrix.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/error/`, `.../app/`, `.../downloader/worker/`, `.../observability/`, `.../storage/postgres/repositories/`
- key file(s): `error/CrawlerErrorCategory.java`, `error/RecoveryPolicy.java`, `error/RecoveryDecision.java`, `error/FailureContext.java`, `error/ProcessingFailureHandler.java`, `observability/CrawlerMetrics.java`, `downloader/worker/WorkerLoop.java` (integration point), `PageRepository.java` (reschedule / terminal / `parser_retry_count`), migration `pa1/db/migrations/V004__parser_retry_count_schema_v4.sql`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/error/` and `.../integration/storage/postgres/`

