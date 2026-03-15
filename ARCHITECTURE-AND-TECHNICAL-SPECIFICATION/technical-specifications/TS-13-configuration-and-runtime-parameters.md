# TS-13 Configuration And Runtime Parameters

## Purpose

Define all runtime settings, defaults, validation, and precedence rules.

## Sources And Precedence

1. CLI arguments (highest)
2. environment variables
3. config file (`application.properties` or equivalent)
4. hardcoded defaults (lowest)

## Core Parameters


| Key                              | Default         | Validation | Notes                |
| -------------------------------- | --------------- | ---------- | -------------------- |
| `crawler.nCrawlers`              | `min(cpu*2, 8)` | `>=1`      | worker count         |
| `crawler.frontier.pollMs`        | `500`           | `50..5000` | empty-queue polling  |
| `crawler.frontier.leaseSeconds`  | `60`            | `10..900`  | claim lease duration |
| `crawler.fetch.connectTimeoutMs` | `5000`          | `>=100`    | HTTP connect timeout |
| `crawler.fetch.readTimeoutMs`    | `10000`         | `>=1000`   | read/render timeout  |
| `crawler.fetch.maxHeadlessSessions` | `2`          | `>=1`      | bounded browser slots |
| `crawler.fetch.headlessAcquireTimeoutMs` | `2000`  | `100..30000` | wait before fallback |
| `crawler.fetch.headlessCircuitOpenThreshold` | `20` | `>=1` | repeated saturation threshold |
| `crawler.rateLimit.minDelayMs`   | `5000`          | `>=5000`   | assignment floor     |
| `crawler.rateLimit.maxBackoffMs` | `300000`        | `>=5000`   | overload cap         |
| `crawler.robots.cacheTtlHours`   | `24`            | `>=1`      | rules cache TTL      |
| `crawler.robots.temporaryDenyMaxMinutes` | `10`   | `1..120`   | max robots failure deny window |
| `crawler.robots.temporaryDenyRetryMinutes` | `2`   | `1..60`    | robots refresh retry cadence |
| `crawler.buckets.cacheTtlHours`  | `1`             | `>=1`      | bucket cache TTL     |
| `crawler.retry.jitterMs`         | `250`           | `0..10000` | retry jitter amplitude |
| `crawler.retry.maxAttempts.fetchTimeout` | `3`    | `0..20`    | must match TS-12 policy |
| `crawler.retry.maxAttempts.fetchOverload` | `5`   | `0..20`    | must match TS-12 policy |
| `crawler.retry.maxAttempts.dbTransient` | `5`     | `0..20`    | must match TS-12 policy |
| `crawler.budget.maxTotalPages`   | `5000`          | `>=1`      | hard global crawl cap |
| `crawler.budget.maxFrontierRows` | `20000`         | `>=100`    | queue high-watermark |
| `crawler.budget.maxPerDomainPages` | `2000`        | `>=1`      | per-domain guardrail |
| `crawler.budget.maxDepth`        | `8`             | `0..64`    | link depth cap from seeds |
| `crawler.scoring.keywordConfig`  | path            | must exist | scorer dictionary    |
| `crawler.db.url`                 | none            | required   | JDBC url             |
| `crawler.db.user`                | none            | required   | DB user              |
| `crawler.db.password`            | none            | required   | DB password          |


CLI entry example:

- `ieps-tolpa --n-crawlers 5`

Worker default heuristic:

- `nCrawlers = min(availableCpuCores * 2, 8)` when explicit override is absent.

## Startup Validation

- startup MUST fail fast on missing required DB credentials;
- startup MUST fail on invalid numeric ranges;
- startup SHOULD print effective configuration (without secrets).
- startup SHOULD validate `crawler.rateLimit.minDelayMs >= 5000`.
- startup MUST validate retry-attempt parameters against `TS-12` policy categories.
- startup MUST validate budget values (`maxTotalPages=5000` assignment cap; positive frontier/domain/depth limits).
- startup MUST validate `crawler.fetch.maxHeadlessSessions <= crawler.nCrawlers`.
- startup MUST validate robots temporary-deny bounds and retry cadence consistency.

## Runtime Enforcement Rules

- dequeue eligibility MUST use persisted `next_attempt_at <= now()`;
- retry delays MUST include jitter for transient categories;
- when `crawler.budget.maxTotalPages` is reached, Stage A insertion stops and logs budget-drop events;
- headless slot acquisition timeout triggers deterministic fallback/error path (defined in `TS-03`);
- effective retry and budget configuration snapshot MUST be emitted at startup.

## Profiles

- `dev`: lower timeouts, smaller worker count, verbose logging.
- `demo`: deterministic seed set and stable scoring config.
- `stress`: higher workers, strict metrics, long-running mode.

## Required Tests

- precedence resolution tests;
- invalid value rejection tests;
- effective config snapshot tests.
- retry/budget parameter wiring tests in integration pipeline;
- assignment cap enforcement test for `crawler.budget.maxTotalPages=5000`;
- headless config validation tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/config/`, `.../cli/`, `.../app/`
- key file(s):
  - `config/RuntimeConfig.java`
  - `cli/Main.java` (CLI argument precedence and binding)
  - `app/PreferentialCrawler.java` (startup preflight validation and effective config logging without secrets)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/config/`, `.../unit/cli/`, `.../integration/app/`

