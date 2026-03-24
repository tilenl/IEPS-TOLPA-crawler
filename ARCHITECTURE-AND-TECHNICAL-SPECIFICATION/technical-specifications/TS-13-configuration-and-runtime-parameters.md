# TS-13 Configuration And Runtime Parameters

## Purpose

Define all runtime settings, defaults, validation, and precedence rules.

## Sources And Precedence

1. CLI arguments (highest)
2. environment variables
3. config file (`application.properties` or equivalent)
4. hardcoded defaults (lowest)

## Core Parameters

Parameters are grouped by subsystem. Each row lists the **crawler impact**: what behavior changes when the value changes.

### Workers and frontier

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.nCrawlers` | `min(cpu*2, 8)` | `>=1` | Number of parallel worker loops claiming and processing frontier rows. Raises throughput and contention on DB pool, rate buckets, and headless slots. MUST stay consistent with `crawler.db.poolSize` and `crawler.fetch.maxHeadlessSessions` (see Startup Validation). |
| `crawler.frontier.pollMs` | `500` | `50..5000` | How long workers sleep when the frontier queue appears empty before polling again. Lower values increase CPU/wakeups; higher values increase latency to pick up new work. |
| `crawler.frontier.leaseSeconds` | `60` | `10..900` | Duration of a claimed row lease (`PROCESSING`). Affects how long a crashed worker blocks a URL before stale-lease recovery requeues it. |
| `crawler.frontier.leaseRecoveryBatchSize` | `10` | `1..1000` | Upper bound on stale leases reclaimed per periodic recovery cycle during the crawl. Larger batches drain stuck work faster but increase burst DB load. |
| `crawler.frontier.startupLeaseRecoveryBatchSize` | `100` | `1..5000` | Upper bound per batch when reclaiming all expired leases before workers start. |
| `crawler.frontier.terminationGraceMs` | `2000` | `0..60000` | Scheduler requires **zero** rows in `FRONTIER` or `PROCESSING` ([TS-02](TS-02-worker-orchestration-and-pipeline.md)) to remain true for this continuous window before declaring completion. Reduces premature shutdown flapping. |

### Fetch and headless

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.fetch.connectTimeoutMs` | `5000` | `>=100` | Max wait to establish TCP/TLS to origin. Failures contribute to fetch retry policy (`TS-12`). |
| `crawler.fetch.readTimeoutMs` | `10000` | `>=1000` | Max wait for response body or headless render. Same retry interaction as connect timeout. |
| `crawler.fetch.maxHeadlessSessions` | `2` | `>=1` | Concurrent headless browser slots. When saturated, workers wait up to `headlessAcquireTimeoutMs` then follow deterministic fallback (`TS-03`). MUST be `<= crawler.nCrawlers`. |
| `crawler.fetch.headlessAcquireTimeoutMs` | `2000` | `100..30000` | Max wait for a free headless slot before fallback/error path. |
| `crawler.fetch.headlessCircuitOpenThreshold` | `20` | `>=1` | After this many repeated saturation events (semantics per `TS-03`), circuit behavior changes to protect the system. |
| `crawler.fetch.maxRedirects` | `10` | `0..20` | Max HTTP redirect hops `HttpClient` follows per request ([TS-03](TS-03-fetcher-specification.md)). Excess hops or loops map to fetch error per `TS-12`. |

### Rate limiting

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.rateLimit.minDelayMs` | `5000` | `>=5000` | Minimum spacing between requests **per domain** (assignment floor). Directly limits peak request rate to a site. |
| `crawler.rateLimit.maxBackoffMs` | `300000` | `>=5000` | Upper cap on politeness backoff when overload signals apply (`TS-08`). |

### Robots and politeness buckets (caches)

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.robots.cacheTtlHours` | `24` | `>=1` | How long cached `robots.txt` rules stay valid before refresh. |
| `crawler.robots.cacheMaxEntries` | `10000` | `>=100` | Max distinct domains in robots cache; eviction when full. |
| `crawler.robots.temporaryDenyMaxMinutes` | `10` | `1..120` | Max wall-clock window robots fetch failures can mark a domain as temporarily denied. |
| `crawler.robots.temporaryDenyRetryMinutes` | `2` | `1..60` | Cadence for retrying robots refresh after transient failure. |
| `crawler.buckets.cacheTtlHours` | `8760` | `>=1` | Hours after last access before an idle per-domain **rate-limiter** bucket entry may expire in the Caffeine registry. Default **8760** (one year) so TTL eviction does **not** reset Bucket4j spacing during typical assignment crawls ([TS-08](TS-08-rate-limiting-and-backoff.md)). |
| `crawler.buckets.cacheMaxEntries` | `100000` | `>=100` | Max distinct per-domain buckets in memory; size eviction when full. Default **100000** to avoid eviction for bounded host cardinality in the assignment profile ([TS-08](TS-08-rate-limiting-and-backoff.md)). |

### Retry, recovery path, and attempt budgets

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.retry.jitterMs` | `250` | `0..10000` | Randomized component added to retry delays for transient categories (`TS-12`). |
| `crawler.recoveryPath.maxAttempts` | `3` | `1..10` | Retries for transient DB failures during `frontier.reschedule` / `markPageAsError` (`TS-02`). |
| `crawler.recoveryPath.baseBackoffMs` | `100` | `10..5000` | Base delay for exponential backoff on recovery-path retries. |
| `crawler.retry.maxAttempts.fetchTimeout` | `3` | `0..20` | Max persisted attempts for `FETCH_TIMEOUT` before terminal error (`TS-12`). |
| `crawler.retry.maxAttempts.fetchOverload` | `5` | `0..20` | Max attempts for `FETCH_HTTP_OVERLOAD` / 429–503 style backoff. |
| `crawler.retry.maxAttempts.fetchCapacity` | `3` | `0..20` | Max attempts for `FETCH_CAPACITY_EXHAUSTED` (headless slot timeout / capacity path, [TS-03](TS-03-fetcher-specification.md), [TS-12](TS-12-error-model-and-recovery-policy.md)). |
| `crawler.retry.maxAttempts.dbTransient` | `5` | `0..20` | Max attempts for `DB_TRANSIENT` during worker-visible operations. |

### Budget

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.budget.maxTotalPages` | `5000` | `>=1` | Hard cap on **new** distinct pages inserted for the run (assignment scope). When reached, Stage A stops inserting new frontier rows and emits `BUDGET_DROPPED` (`TS-02`, `TS-15`). |
| `crawler.budget.maxFrontierRows` | `20000` | `>=100` | High watermark on frontier queue size. When at or above this level, Stage A **defers** low-priority discoveries (future `next_attempt_at`) instead of dropping them terminally (`TS-02`). |

**Single-domain profile:** This crawler targets one primary domain (`04-domain-and-scope-definition.md`). A separate per-domain page cap is **not** defined: `maxTotalPages` is the only page-count guardrail.

### Scoring

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.scoring.keywordConfig` | path | must exist | Path to JSON keyword lists used by `RelevanceScorer` at Stage A; drives frontier priority ordering. |

### Database

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.db.url` | none | required | JDBC connection target. |
| `crawler.db.user` | none | required | DB user (never log). |
| `crawler.db.password` | none | required | DB password (never log). |
| `crawler.db.poolSize` | `min(nCrawlers + 2, 20)` | `>=2` | Hikari (or equivalent) pool size. MUST be `>= crawler.nCrawlers + 1` so claim and persistence can overlap without exhaustion. |
| `crawler.db.expectedSchemaVersion` | none | required | Expected `crawldb.schema_version.version` for singleton row `id=1`; mismatch fails startup/readiness (`TS-15`). |

### Health (heartbeat logs)

| Key | Default | Validation | Crawler impact |
| --- | --- | --- | --- |
| `crawler.health.heartbeatIntervalMs` | `45000` | `5000..300000` | Interval for structured **`CRAWLER_HEARTBEAT`** events ([TS-15](TS-15-observability-logging-and-metrics.md)). Each event **MUST** include **`frontierDepth`** and **`processingCount`**; **SHOULD** include **`pagesTerminalTotal`** (or terminal counts by type). |

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

**Interaction note:** Increasing `crawler.nCrawlers` without raising `crawler.db.poolSize` (subject to `poolSize >= nCrawlers + 1`) or without raising `crawler.fetch.maxHeadlessSessions` (subject to `maxHeadlessSessions <= nCrawlers`) causes startup validation failure or runtime contention; see Parameter-linked diagnostics for log field expectations (`TS-15`).

## Parameter-linked diagnostics

When the crawler hits a **config-defined limit, retry ceiling, or validation boundary**, structured logs MUST include `configKey` and `remediationHint` per `TS-15`. The table below is **normative**: implementation hints MUST match or closely paraphrase these strings so operators can search logs and align configuration.

| Event / situation | Primary `configKey` (log field) | Normative `remediationHint` (log field) |
| --- | --- | --- |
| Global page budget hit (`BUDGET_DROPPED`) | `crawler.budget.maxTotalPages` | To crawl more distinct pages in a run, increase `crawler.budget.maxTotalPages` and ensure database and disk capacity are sufficient for the larger crawl. |
| Frontier high-watermark deferral (`FRONTIER_DEFERRED` or equivalent) | `crawler.budget.maxFrontierRows` | To admit more URLs into the frontier sooner, increase `crawler.budget.maxFrontierRows`, or reduce discovery breadth via scoring seeds and link policy; very large queues increase memory and DB load. |
| Fetch timeout retries exhausted (terminal `FETCH_TIMEOUT`) | `crawler.retry.maxAttempts.fetchTimeout` (and optionally `crawler.fetch.connectTimeoutMs`, `crawler.fetch.readTimeoutMs`) | To allow more attempts before giving up, increase `crawler.retry.maxAttempts.fetchTimeout`; to wait longer per attempt, increase `crawler.fetch.connectTimeoutMs` or `crawler.fetch.readTimeoutMs` if appropriate for slow origins. |
| Overload retries exhausted (terminal overload path) | `crawler.retry.maxAttempts.fetchOverload` (and optionally `crawler.rateLimit.maxBackoffMs`) | To tolerate longer overload episodes, increase `crawler.retry.maxAttempts.fetchOverload` or adjust `crawler.rateLimit.maxBackoffMs` within policy limits. |
| Headless capacity retries exhausted (`FETCH_CAPACITY_EXHAUSTED`) | `crawler.retry.maxAttempts.fetchCapacity` (and optionally `crawler.fetch.maxHeadlessSessions`, `crawler.fetch.headlessAcquireTimeoutMs`) | To allow more capacity-exhaustion retries before terminal handling, increase `crawler.retry.maxAttempts.fetchCapacity`; to reduce saturation, increase `crawler.fetch.maxHeadlessSessions` (must remain `<= crawler.nCrawlers`) or `crawler.fetch.headlessAcquireTimeoutMs` per headless guidance. |
| DB transient retries exhausted in worker path | `crawler.retry.maxAttempts.dbTransient` | To tolerate longer DB outages per row, increase `crawler.retry.maxAttempts.dbTransient`; persistent failures require infrastructure or schema fixes, not only config. |
| Recovery-path retries exhausted (`reschedule` / `markPageAsError`) | `crawler.recoveryPath.maxAttempts` | To allow more transient DB retries on state transitions, increase `crawler.recoveryPath.maxAttempts` or tune `crawler.recoveryPath.baseBackoffMs` / `crawler.retry.jitterMs`. |
| Rate-limit reschedule (long observed waits) | `crawler.rateLimit.minDelayMs`, `crawler.rateLimit.maxBackoffMs` | The assignment enforces a minimum delay per domain; lowering below 5000 ms is not valid. To change politeness, adjust `crawler.rateLimit.minDelayMs` (within `>=5000`) or overload cap `crawler.rateLimit.maxBackoffMs`. |
| Headless slot acquire timeout / saturation | `crawler.fetch.maxHeadlessSessions`, `crawler.fetch.headlessAcquireTimeoutMs` | To reduce timeouts, increase `crawler.fetch.maxHeadlessSessions` (must remain `<= crawler.nCrawlers`) or increase `crawler.fetch.headlessAcquireTimeoutMs`; alternatively reduce `crawler.nCrawlers` to match headless capacity. |
| Headless circuit open (repeated saturation) | `crawler.fetch.headlessCircuitOpenThreshold` | To make the circuit less sensitive, increase `crawler.fetch.headlessCircuitOpenThreshold`; to reduce saturation events, add headless slots or workers per headless guidance above. |
| Robots cache eviction / short TTL pressure | `crawler.robots.cacheTtlHours`, `crawler.robots.cacheMaxEntries` | To reduce robots refetch churn, increase `crawler.robots.cacheTtlHours` or `crawler.robots.cacheMaxEntries` within operational memory limits. |
| Robots temporary deny persists | `crawler.robots.temporaryDenyMaxMinutes`, `crawler.robots.temporaryDenyRetryMinutes` | To shorten deny windows or change retry cadence, adjust `crawler.robots.temporaryDenyMaxMinutes` and `crawler.robots.temporaryDenyRetryMinutes` within validated bounds. |
| Bucket cache eviction (politeness risk if too aggressive) | `crawler.buckets.cacheTtlHours`, `crawler.buckets.cacheMaxEntries` | Defaults are assignment-safe per [TS-08](TS-08-rate-limiting-and-backoff.md). If you lower TTL or max entries and see burst traffic to origins, increase `crawler.buckets.cacheMaxEntries` or `crawler.buckets.cacheTtlHours`. |
| Startup validation failure (any numeric or relational bound) | (the key that failed validation) | Logs MUST name the property and valid range from this document; fix the config file, environment variable, or CLI flag that sets that key. |
| Schema version mismatch | `crawler.db.expectedSchemaVersion` | Align `crawler.db.expectedSchemaVersion` with the migrated database version, or run migrations to match the configured expectation (`TS-15`). |
| JDBC pool exhaustion (if surfaced as warning) | `crawler.db.poolSize`, `crawler.nCrawlers` | Increase `crawler.db.poolSize` to at least `crawler.nCrawlers + 1`, or reduce `crawler.nCrawlers` to match pool capacity. |
| Lease recovery falling behind (operational warning) | `crawler.frontier.leaseRecoveryBatchSize`, `crawler.frontier.leaseSeconds` | Increase `crawler.frontier.leaseRecoveryBatchSize` to reclaim more stale leases per cycle, or shorten `crawler.frontier.leaseSeconds` so stuck leases expire sooner (trade-off with crash recovery latency). |
| Premature or delayed shutdown vs queue state | `crawler.frontier.terminationGraceMs` | Increase `crawler.frontier.terminationGraceMs` if the scheduler stops while `FRONTIER`/`PROCESSING` rows still exist or conditions oscillate; decrease only if intentional faster exit is desired and flapping is acceptable. |
| Redirect hop limit exceeded (`TS-03` / `TS-12`) | `crawler.fetch.maxRedirects` | Increase `crawler.fetch.maxRedirects` within `0..20` if legitimate chains are cut off; investigate redirect loops if failures persist. |

### Remediation hint scope (normative)

- **MUST** include `configKey` and `remediationHint` when the outcome is directly governed by a key in this document or by a `crawler.retry.maxAttempts.*` ceiling aligned with `TS-12`.
- **MUST NOT** attach misleading “tune config” hints to outcomes that are not limit-driven (for example `ROBOTS_DISALLOWED`, stable `4xx`, malformed URLs, or logic bugs): log category and facts only per `TS-12` / `TS-15`.
- **URL_TOO_LONG** (`>3000` canonical length) is a **schema contract** fix (canonicalization / filtering), not a numeric TS-13 knob; logs SHOULD NOT invent a TS-13 remediation unless a future key is added.

## Startup Validation

- startup MUST fail fast on missing required DB credentials;
- startup MUST fail on invalid numeric ranges;
- startup SHOULD print effective configuration (without secrets).
- startup SHOULD validate `crawler.rateLimit.minDelayMs >= 5000`.
- startup MUST validate retry-attempt parameters against `TS-12` policy categories (including `crawler.retry.maxAttempts.fetchCapacity` in `0..20`).
- startup MUST validate budget values: positive `crawler.budget.maxTotalPages` and `crawler.budget.maxFrontierRows`.
- startup MUST validate `crawler.fetch.maxHeadlessSessions <= crawler.nCrawlers`.
- startup MUST validate `crawler.fetch.maxRedirects` in `0..20` (inclusive).
- startup MUST validate `crawler.health.heartbeatIntervalMs` in `5000..300000` (inclusive).
- startup MUST validate `crawler.db.poolSize >= crawler.nCrawlers + 1` for claim + persistence overlap.
- startup MUST validate DB schema version equality (`crawler.db.expectedSchemaVersion` vs `crawldb.schema_version.version`) before worker start.
- startup MUST validate `crawler.frontier.leaseRecoveryBatchSize >= 1`.
- startup MUST validate `crawler.frontier.startupLeaseRecoveryBatchSize >= 1`.
- startup MUST validate `crawler.recoveryPath.maxAttempts >= 1` and `crawler.recoveryPath.baseBackoffMs >= 10`.
- startup MUST validate robots temporary-deny bounds and retry cadence consistency.
- startup MUST validate robots/bucket cache size bounds (`crawler.robots.cacheMaxEntries`, `crawler.buckets.cacheMaxEntries`).
- startup MUST parse and validate `crawler.scoring.keywordConfig` structure (not just path existence).

## Runtime Enforcement Rules

- dequeue eligibility MUST use persisted `next_attempt_at <= now()`;
- retry delays MUST include jitter for transient categories;
- when `crawler.budget.maxTotalPages` is reached, Stage A insertion stops and logs budget-drop events with `configKey` and `remediationHint` (`TS-15`);
- when `crawler.budget.maxFrontierRows` is at the high watermark, Stage A defers low-priority discoveries per `TS-02` and logs with `configKey` and `remediationHint` where applicable;
- when canonical URL length exceeds DB contract (`>3000`), Stage A rejects URL as non-retryable `URL_TOO_LONG` and logs structured diagnostics;
- lease recovery MUST run in bounded batches using `crawler.frontier.leaseRecoveryBatchSize`;
- startup lease recovery MUST run before worker loops and continue until no stale leases remain, using `crawler.frontier.startupLeaseRecoveryBatchSize`;
- recovery-path transition writes (`reschedule` / `markPageAsError`) MUST apply bounded transient retries using `crawler.recoveryPath.maxAttempts`, `crawler.recoveryPath.baseBackoffMs`, and `crawler.retry.jitterMs`.
- scheduler termination decision MUST honor `crawler.frontier.terminationGraceMs` continuous-stability window while `COUNT(FRONTIER)+COUNT(PROCESSING)=0` per [TS-02](TS-02-worker-orchestration-and-pipeline.md);
- the runtime MUST emit structured **heartbeat** logs on `crawler.health.heartbeatIntervalMs` per [TS-15](TS-15-observability-logging-and-metrics.md);
- headless slot acquisition timeout triggers deterministic fallback/error path (defined in `TS-03`);
- robots cache MUST apply both TTL and maximum-size eviction from runtime config;
- rate-limiter **bucket** cache MUST honor `crawler.buckets.cacheTtlHours` and `crawler.buckets.cacheMaxEntries`; default values are chosen so assignment-scale crawls do not lose per-domain spacing to eviction ([TS-08](TS-08-rate-limiting-and-backoff.md)).
- effective retry and budget configuration snapshot MUST be emitted at startup.

## Profiles

- `dev`: lower timeouts, smaller worker count, verbose logging.
- `demo`: deterministic seed set and stable scoring config.
- `stress`: higher workers, strict metrics, long-running mode.

## Required Tests

- precedence resolution tests;
- invalid value rejection tests;
- effective config snapshot tests.
- retry/budget parameter wiring tests in integration pipeline (including `crawler.retry.maxAttempts.fetchCapacity` validation);
- assignment cap enforcement test for `crawler.budget.maxTotalPages=5000`;
- frontier high-watermark deferral test for `crawler.budget.maxFrontierRows`;
- DB pool-size validation test (`poolSize >= nCrawlers + 1`);
- lease-recovery batch-size wiring test;
- startup lease-recovery batch-size wiring test;
- recovery-path bounded-retry config wiring test;
- headless config validation tests.
- `crawler.fetch.maxRedirects` and `crawler.health.heartbeatIntervalMs` validation tests.
- robots and bucket cache max-entry validation tests.
- where structured logging is implemented: assert `configKey` and `remediationHint` on at least one `BUDGET_DROPPED` event and one frontier-deferral event (`TS-15`).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/config/`, `.../cli/`, `.../app/`, `.../observability/`
- key file(s):
  - `config/RuntimeConfig.java`
  - `config/ConfigRemediation.java` (centralized `configKey` / `remediationHint` strings aligned with Parameter-linked diagnostics)
  - `cli/Main.java` (CLI argument precedence and binding)
  - `app/PreferentialCrawler.java` (startup preflight validation and effective config logging without secrets)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/config/`, `.../unit/cli/`, `.../integration/app/`
