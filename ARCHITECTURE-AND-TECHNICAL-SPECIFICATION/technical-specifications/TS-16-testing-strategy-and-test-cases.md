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
- `FOR UPDATE SKIP LOCKED` no double-claim under parallel workers;
- robots disallow blocks before frontier insertion;
- 5-second delay floor and overload backoff handling.

## Quality Gates (Pre-Coding/Pre-Merge)

- all unit tests pass;
- integration suite for DB contracts passes;
- no flaky test accepted without quarantine plan;
- critical-path coverage for Stage A + Stage B behavior documented.

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
