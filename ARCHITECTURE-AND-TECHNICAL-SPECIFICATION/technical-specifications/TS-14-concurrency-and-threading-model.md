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

## Contention Points

- DB contention on frontier claim/update;
- shared caches (robots and buckets);
- Selenium resource usage when many external-domain fetches exist.

## Required Tests

- parallel claim uniqueness;
- graceful shutdown with active workers;
- thread-safe cache usage under concurrent access.

