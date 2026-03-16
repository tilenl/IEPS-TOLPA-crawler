# TS-13 Configuration And Runtime Parameters

## Purpose

Define all runtime settings, defaults, validation, and precedence rules.

## Sources And Precedence

1. CLI arguments (highest)
2. environment variables
3. config file (`application.properties` or equivalent)
4. hardcoded defaults (lowest)

## Core Parameters

| Key                                          | Default                  | Validation   | Notes                          |
| -------------------------------------------- | ------------------------ | ------------ | ------------------------------ |
| `crawler.nCrawlers`                          | `min(cpu*2, 8)`          | `>=1`        | worker count                   |
| `crawler.frontier.pollMs`                    | `500`                    | `50..5000`   | empty-queue polling            |
| `crawler.frontier.leaseSeconds`              | `60`                     | `10..900`    | claim lease duration           |
| `crawler.frontier.leaseRecoveryBatchSize`    | `10`                     | `1..1000`    | max stale leases recovered per claim cycle |
| `crawler.frontier.terminationGraceMs`        | `2000`                   | `0..60000`   | stable-termination observation window |
| `crawler.fetch.connectTimeoutMs`             | `5000`                   | `>=100`      | HTTP connect timeout           |
| `crawler.fetch.readTimeoutMs`                | `10000`                  | `>=1000`     | read/render timeout            |
| `crawler.fetch.maxHeadlessSessions`          | `2`                      | `>=1`        | bounded browser slots          |
| `crawler.fetch.headlessAcquireTimeoutMs`     | `2000`                   | `100..30000` | wait before fallback           |
| `crawler.fetch.headlessCircuitOpenThreshold` | `20`                     | `>=1`        | repeated saturation threshold  |
| `crawler.rateLimit.minDelayMs`               | `5000`                   | `>=5000`     | assignment floor               |
| `crawler.rateLimit.maxBackoffMs`             | `300000`                 | `>=5000`     | overload cap                   |
| `crawler.robots.cacheTtlHours`               | `24`                     | `>=1`        | rules cache TTL                |
| `crawler.robots.cacheMaxEntries`             | `10000`                  | `>=100`      | max robots cache entries       |
| `crawler.robots.temporaryDenyMaxMinutes`     | `10`                     | `1..120`     | max robots failure deny window |
| `crawler.robots.temporaryDenyRetryMinutes`   | `2`                      | `1..60`      | robots refresh retry cadence   |
| `crawler.buckets.cacheTtlHours`              | `1`                      | `>=1`        | bucket cache TTL               |
| `crawler.buckets.cacheMaxEntries`            | `10000`                  | `>=100`      | max bucket cache entries       |
| `crawler.retry.jitterMs`                     | `250`                    | `0..10000`   | retry jitter amplitude         |
| `crawler.retry.maxAttempts.fetchTimeout`     | `3`                      | `0..20`      | must match TS-12 policy        |
| `crawler.retry.maxAttempts.fetchOverload`    | `5`                      | `0..20`      | must match TS-12 policy        |
| `crawler.retry.maxAttempts.dbTransient`      | `5`                      | `0..20`      | must match TS-12 policy        |
| `crawler.budget.maxTotalPages`               | `5000`                   | `>=1`        | hard global crawl cap          |
| `crawler.budget.maxPerDomainPages`           | `3000`                   | `>=1`        | per-domain expansion cap       |
| `crawler.budget.maxFrontierRows`             | `20000`                  | `>=100`      | queue high-watermark           |
| `crawler.scoring.keywordConfig`              | path                     | must exist   | scorer dictionary              |
| `crawler.db.url`                             | none                     | required     | JDBC url                       |
| `crawler.db.user`                            | none                     | required     | DB user                        |
| `crawler.db.password`                        | none                     | required     | DB password                    |
| `crawler.db.poolSize`                        | `min(nCrawlers + 2, 20)` | `>=2`        | JDBC connection pool size      |

`crawler.scoring.keywordConfig` file format (normative):

- JSON UTF-8 document with object root and two required arrays:
  - `primary`: non-empty array of case-insensitive keyword strings;
  - `secondary`: non-empty array of case-insensitive keyword strings.
- keywords MUST be trimmed, deduplicated, and normalized to lowercase at load time.
- invalid JSON, missing keys, non-string items, or empty arrays MUST fail startup validation.

Example:

```json
{
  "primary": ["image segmentation", "semantic segmentation", "u-net"],
  "secondary": ["object detection", "feature extraction"]
}
```

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
- startup MUST validate budget values (`maxTotalPages=5000` assignment cap; positive frontier/domain limits).
- startup MUST validate `crawler.fetch.maxHeadlessSessions <= crawler.nCrawlers`.
- startup MUST validate `crawler.budget.maxPerDomainPages <= crawler.budget.maxTotalPages`.
- startup MUST validate `crawler.db.poolSize >= crawler.nCrawlers + 1` for claim + persistence overlap.
- startup MUST validate `crawler.frontier.leaseRecoveryBatchSize >= 1`.
- startup MUST validate robots temporary-deny bounds and retry cadence consistency.
- startup MUST validate robots/bucket cache size bounds (`crawler.robots.cacheMaxEntries`, `crawler.buckets.cacheMaxEntries`).
- startup MUST parse and validate `crawler.scoring.keywordConfig` structure (not just path existence).

## Runtime Enforcement Rules

- dequeue eligibility MUST use persisted `next_attempt_at <= now()`;
- retry delays MUST include jitter for transient categories;
- when `crawler.budget.maxTotalPages` is reached, Stage A insertion stops and logs budget-drop events;
- when `crawler.budget.maxPerDomainPages` is reached for a domain, Stage A rejects additional URLs for that domain and logs `BUDGET_DROPPED`;
- lease recovery MUST run in bounded batches using `crawler.frontier.leaseRecoveryBatchSize`;
- scheduler termination decision MUST honor `crawler.frontier.terminationGraceMs` continuous-stability window;
- headless slot acquisition timeout triggers deterministic fallback/error path (defined in `TS-03`);
- robots and bucket caches MUST apply both TTL and maximum-size eviction from runtime config.
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
- per-domain budget enforcement test for `crawler.budget.maxPerDomainPages`;
- DB pool-size validation test (`poolSize >= nCrawlers + 1`);
- lease-recovery batch-size wiring test;
- headless config validation tests.
- robots and bucket cache max-entry validation tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/config/`, `.../cli/`, `.../app/`
- key file(s):
  - `config/RuntimeConfig.java`
  - `cli/Main.java` (CLI argument precedence and binding)
  - `app/PreferentialCrawler.java` (startup preflight validation and effective config logging without secrets)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/config/`, `.../unit/cli/`, `.../integration/app/`
