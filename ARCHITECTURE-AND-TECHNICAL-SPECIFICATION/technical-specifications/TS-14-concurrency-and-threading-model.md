# TS-14 Concurrency And Threading Model

## Threading Model

The crawler implements a **Producer-Consumer architecture** using a database-backed queue:
- **Producers**: Workers discovering new URLs during parsing and ingesting them into the `FRONTIER`.
- **Consumers**: Workers claiming rows from the `FRONTIER` for processing.
- **Buffer**: The PostgreSQL `FRONTIER` table, providing persistence and thread-safe coordination.

- Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`;
- one worker loop per configured crawler worker;
- blocking IO is acceptable under virtual-thread model.

## Shared State Rules

- shared mutable state minimized and guarded by component ownership;
- domain backoff counters and caches MUST be thread-safe;
- DB row-level locking is the source of truth for frontier claim safety.
- queue leases (`claimed_by`, `claim_expires_at`) are shared durability state and must be treated as authoritative.
- robots first-encounter loading MUST be coordinated with per-domain single-flight guard so only one worker fetches `/robots.txt` per domain at a time.

Worker identity rules:
- each worker loop MUST use a stable generated `workerId` for its lifetime;
- `workerId` SHOULD be generated at startup (UUID or host+pid+worker-index equivalent);
- `workerId` MUST be propagated consistently to frontier lease ownership (`claimed_by`) and observability fields.

## Worker Lifecycle

1. scheduler starts worker pool;
2. workers run claim/process loop;
3. scheduler signals graceful stop;
4. workers finish in-flight leased page (persist terminal outcome or reschedule retry) and only then exit;
5. executor shutdown with timeout.

Graceful-stop invariant:
- worker MUST NOT exit while holding a leased `PROCESSING` row;
- if stop signal arrives mid-page, worker completes persistence path for that lease before loop termination.

Operational throughput note:
- for a single high-volume domain with 5-second floor, effective throughput is limited by politeness gate;
- additional workers improve overlap across different domains and CPU-bound parsing/persistence segments.

## Contention Points

- DB contention on frontier claim/update;
- shared caches (robots and buckets);
- robots single-flight guard contention on first domain encounter;
- Selenium resource usage when many external-domain fetches exist.
- high-rate duplicate discoveries converging on same `page.url` unique constraint.
- JDBC connection-pool starvation when `poolSize` is below active worker concurrency.

## Capacity Budgets

- maximum concurrent headless fetches is bounded by `crawler.fetch.maxHeadlessSessions` (`TS-13`);
- DB connection pool size is controlled by `crawler.db.poolSize` (`TS-13`) and SHOULD satisfy `poolSize >= nCrawlers + 1`;
- JDBC connections SHOULD be held **only** for database work (claim, persist, Stage A writes)—not across entire fetch/parse/headless segments—so the pool is not exhausted while workers block on network or WebDriver ([TS-13](TS-13-configuration-and-runtime-parameters.md)).
- headless capacity exhaustion MUST not block worker indefinitely or stall frontier progression;
- queue lease duration (`crawler.frontier.leaseSeconds`) must exceed expected fetch/parse/persist critical path for healthy workers;
- expired leases are recoverable and returned to `FRONTIER` before starvation occurs.

## Saturation Handling

- when headless capacity is saturated, worker follows deterministic fallback path from `TS-03`;
- repeated saturation events SHOULD trigger temporary circuit-open behavior for headless mode;
- saturation events must emit observability signals used by `TS-15` alerts.

## Required Tests

- parallel claim uniqueness;
- graceful shutdown with active workers;
- thread-safe cache usage under concurrent access.
- concurrent first-encounter test proving a single robots fetch for N workers claiming same new domain.
- bounded headless concurrency under load;
- lease expiration and recovery behavior under simulated worker crash;
- no deadlock under mixed DB contention and headless saturation.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/`, `.../scheduler/`, `.../downloader/worker/`, `.../config/`, `.../cli/`
- key file(s):
  - `app/PreferentialCrawler.java` (lifecycle start/stop and preflight gate before worker launch)
  - `scheduler/policies/SchedulingPolicy.java`
  - `downloader/worker/WorkerLoop.java`
  - `config/RuntimeConfig.java`
  - `cli/Main.java` (entrypoint delegation only)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/app/`, `.../unit/downloader/worker/`, `.../integration/pipeline/`

