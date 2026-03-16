# TS-15 Observability Logging And Metrics

## Logging

- structured log format (JSON or key-value);
- required fields: `timestamp`, `level`, `workerId`, `pageId`, `url`, `domain`, `event`, `result`;
- no secrets in logs (DB credentials, tokens).
- queue-state events MUST include `fromState`, `toState`, `attemptCount`, and `nextAttemptAt` when present.
- `workerId` in logs MUST match the frontier lease owner identity used in `claimed_by` (`TS-07`, `TS-14`).

## Event Types

- frontier claim/release/reschedule;
- lease recovery and lease-expiry events;
- fetch start/end and mode (`PLAIN_HTTP`, `HEADLESS`);
- parse summary (links/images extracted);
- dedup decisions (URL/content);
- persistence outcomes and failures.
- budget-drop and budget-defer events.
- URL-length rejection events (`URL_TOO_LONG`) for canonical URLs rejected before DB insert.
- robots fetch outcomes (`2xx`, `4xx`, `3xx/5xx`) and robots decision outcomes.

## Metrics

- queue depth (`FRONTIER` count);
- fetch latency histogram by domain;
- success/error counts by category (`TS-12`);
- rate-limit waits and backoff events;
- dedup hit rates.
- in-progress lease count and oldest active lease age;
- delayed frontier age (`now - min(next_attempt_at)` for overdue rows);
- DB pool utilization and timeout counts;
- headless slot utilization, saturation count, and circuit-open count.
- `robots_temporary_deny_domains` gauge;
- `robots_fetch_failures_total` counter (at minimum by domain and status class).

## Healthcheck Contracts

- readiness MUST fail when required dependencies are unavailable (DB connectivity, schema/version mismatch);
- schema mismatch diagnostics MUST include at minimum `expectedVersion`, `dbVersion`, and a remediation hint to apply/align DB schema;
- readiness SHOULD degrade when DB pool or headless saturation exceeds configured threshold;
- liveness MUST fail for unrecoverable worker-loop stall (no successful state transitions beyond configured timeout);
- health endpoints/status reporters MUST expose lease-recovery pressure and retry backlog age.

Healthcheck exposure contract:
- minimum required exposure is structured health status events in logs at startup and at fixed heartbeat intervals;
- optional HTTP health server MAY be enabled via runtime config and must expose `/health/live` and `/health/ready`;
- when HTTP health server is disabled, orchestrators MUST rely on process exit code and structured health log events.

## Run Summary

At crawler stop, emit summary:

- total URLs seen;
- total HTML, BINARY, DUPLICATE pages;
- total errors by category;
- top domains by fetch count.
- total rate-limit delays and cumulative delayed time.
- total lease recoveries and max observed lease age.
- total budget drops (`BUDGET_DROPPED`) and deferred ingestions.

Summary should also include seed bootstrap metadata:
- number of seeds configured;
- number of seeds inserted vs already existing.

## Required Tests

- required log fields present on critical events;
- metrics counters increment correctly;
- summary generated at graceful shutdown.
- healthcheck transitions under DB down / saturation scenarios.
- lease-age and delayed-queue metrics correctness tests.
- robots metrics correctness tests (`robots_temporary_deny_domains`, `robots_fetch_failures_total`).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/observability/`, `.../app/`
- key file(s): `observability/CrawlerMetrics.java`, `observability/RunSummaryReporter.java`, `app/PreferentialCrawler.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/observability/` and `.../integration/pipeline/`

