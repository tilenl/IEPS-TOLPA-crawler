# TS-14 Concurrency And Threading Model

## Threading Model

- Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`;
- one worker loop per configured crawler worker;
- blocking IO is acceptable under virtual-thread model.

## Shared State Rules

- shared mutable state minimized and guarded by component ownership;
- domain backoff counters and caches MUST be thread-safe;
- DB row-level locking is the source of truth for frontier claim safety.

## Worker Lifecycle

1. scheduler starts worker pool;
2. workers run claim/process loop;
3. scheduler signals graceful stop;
4. workers finish in-flight page and exit;
5. executor shutdown with timeout.

Operational throughput note:
- for a single high-volume domain with 5-second floor, effective throughput is limited by politeness gate;
- additional workers improve overlap across different domains and CPU-bound parsing/persistence segments.

## Contention Points

- DB contention on frontier claim/update;
- shared caches (robots and buckets);
- Selenium resource usage when many external-domain fetches exist.
- high-rate duplicate discoveries converging on same `page.url` unique constraint.

## Required Tests

- parallel claim uniqueness;
- graceful shutdown with active workers;
- thread-safe cache usage under concurrent access.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/`, `.../scheduler/`, `.../downloader/worker/`, `.../config/`, `.../cli/`
- key file(s):
  - `app/PreferentialCrawler.java` (lifecycle start/stop and preflight gate before worker launch)
  - `scheduler/policies/SchedulingPolicy.java`
  - `downloader/worker/WorkerLoop.java`
  - `config/RuntimeConfig.java`
  - `cli/Main.java` (entrypoint delegation only)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/app/`, `.../unit/downloader/worker/`, `.../integration/pipeline/`

