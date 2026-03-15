# TS-16 Testing Strategy And Test Cases

## Baseline Framework

- test framework: JUnit 5 (Jupiter) aligned with `pa1/build.gradle.kts` dependencies:
  - `org.junit:junit-bom`
  - `org.junit.jupiter:junit-jupiter`
- run contract: `./gradlew test`

## Test Organization

- unit tests: `*Test` under component package;
- integration tests: optional `*IT` naming and grouped by DB/network behavior;
- deterministic fixtures/builders for parser, scorer, canonicalizer cases.

## Component Test Matrix

| Component | Unit Tests | Integration Tests |
| --- | --- | --- |
| Canonicalizer | normalization, allowlist, malformed URLs | end-to-end ingestion normalization |
| Parser | `href`, `onclick`, image extraction | HTML samples from fetched pages |
| Frontier/Storage | SQL contract behavior with mocks | real PostgreSQL claim/insert/update |
| RateLimiter | delay floor, backoff transitions | multi-worker delayed requeue behavior |
| Worker | orchestration branching by outcome | pipeline flow across fetch/parse/persist |

## Mandatory Scenario Cases

- `href` + `onclick` extraction correctness;
- URL duplicate vs content duplicate separation;
- atomic claim transition (`UPDATE ... RETURNING`) no double-lease under parallel workers;
- robots disallow blocks before frontier insertion;
- 5-second delay floor and overload backoff handling.
- preferential ordering check:
  - populate frontier with mixed `relevance_score` values and verify descending dequeue order.
- scorer contract check:
  - output always in `[0.0, 1.0]` and deterministic for same normalized inputs.
- delayed eligibility check:
  - rows with `next_attempt_at > now()` are not claimable.
- retry durability check:
  - attempt count and due time survive restart and enforce max-attempt transition to `ERROR`.
- lease recovery check:
  - stale `PROCESSING` rows are recovered to `FRONTIER` exactly once.
- content dedup race check:
  - concurrent same-hash pages produce one canonical owner and deterministic duplicates.
- headless saturation check:
  - max headless session cap enforced and fallback path is deterministic.
- robots temporary-deny TTL check:
  - 3xx/5xx robots failures honor max deny window and recover on successful refresh.
- budget guardrail checks:
  - global cap `5000` prevents new page insertion beyond limit;
  - per-domain and depth caps enforce deterministic ingestion decisions.
- observability checks:
  - lease age, delayed queue age, DB pool saturation, and healthcheck transitions are emitted correctly.

## Quality Gates (Pre-Coding/Pre-Merge)

- all unit tests pass;
- integration suite for DB contracts passes;
- no flaky test accepted without quarantine plan;
- critical-path coverage for Stage A + Stage B behavior documented.
- concurrency/race tests required for claim and content dedup before merge;
- restart-resilience tests required for retry durability before merge.

## CI Recommendation

- run unit tests on every push;
- run integration tests on PR and nightly;
- publish test report artifacts for failures.

## Implementation Location

- primary folder(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/`, `.../integration/`, `pa1/crawler/src/test/resources/`
- key file(s): `*UnitTest` for deterministic unit tests, `*IT` for integration tests with DB/network behavior
- test location(s):
  - `unit/` mirrors owned main packages
  - `integration/` contains cross-component and external-system tests
  - tests MUST NOT be colocated in `src/main/java`
