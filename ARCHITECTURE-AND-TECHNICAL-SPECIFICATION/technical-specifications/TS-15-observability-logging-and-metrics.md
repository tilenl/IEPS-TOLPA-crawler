# TS-15 Observability Logging And Metrics

## Logging

- structured log format (JSON or key-value);
- required fields: `timestamp`, `level`, `workerId`, `pageId`, `url`, `domain`, `event`, `result`;
- no secrets in logs (DB credentials, tokens).
- queue-state events MUST include `fromState`, `toState`, `attemptCount`, and `nextAttemptAt` when present.
- `workerId` in logs MUST match the frontier lease owner identity used in `claimed_by` (`TS-07`, `TS-14`).

### Parameter-linked remediation fields

- For warnings and errors that occur **because a configured limit, retry ceiling, or validation bound was reached** (see **Parameter-linked diagnostics** in `TS-13`), logs MUST additionally include:
  - `configKey`: string, the dotted runtime property name (for example `crawler.budget.maxTotalPages`);
  - `remediationHint`: string, human-readable guidance; MUST match or closely paraphrase the normative hint for that event class in `TS-13` so operators can grep logs and adjust the same key in CLI, environment, or `application.properties`.
- For events **not** driven by a `TS-13` knob or `crawler.retry.maxAttempts.*` ceiling (for example `ROBOTS_DISALLOWED`, stable `4xx`, malformed URLs), implementations MUST NOT emit misleading `remediationHint` text implying a configuration change will fix site policy or remote behavior (`TS-13` remediation scope).
- Startup validation failures MUST log the failing `configKey` (or equivalent), the invalid value (when safe), and the allowed range from `TS-13`.
- Schema version mismatch diagnostics MUST include `configKey` `crawler.db.expectedSchemaVersion`, plus `expectedVersion`, `dbVersion`, and a remediation hint aligned with `TS-13` / this document.

## Event Types

- frontier claim/release/reschedule;
- lease recovery and lease-expiry events;
- fetch start/end and mode (`PLAIN_HTTP`, `HEADLESS`);
- parse summary (links/images extracted);
- dedup decisions (URL/content);
- persistence outcomes and failures.
- budget-drop (`BUDGET_DROPPED`) and frontier deferral at high-watermark (`FRONTIER_DEFERRED` or equivalent) with `configKey` / `remediationHint` per `TS-13`;
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
- schema mismatch diagnostics MUST include at minimum `configKey` (`crawler.db.expectedSchemaVersion`), `expectedVersion`, `dbVersion`, and `remediationHint` to apply/align DB schema or config (`TS-13` Parameter-linked diagnostics);
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
- limit-driven events include `configKey` and `remediationHint` per Parameter-linked diagnostics (`TS-13`);
- metrics counters increment correctly;
- summary generated at graceful shutdown.
- healthcheck transitions under DB down / saturation scenarios.
- lease-age and delayed-queue metrics correctness tests.
- robots metrics correctness tests (`robots_temporary_deny_domains`, `robots_fetch_failures_total`).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/observability/`, `.../app/`, `.../config/`
- key file(s): `observability/CrawlerMetrics.java`, `observability/RunSummaryReporter.java`, `app/PreferentialCrawler.java`, `config/ConfigRemediation.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/observability/` and `.../integration/pipeline/`

