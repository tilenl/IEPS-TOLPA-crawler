# TS-15 Observability Logging And Metrics

## Logging

- structured log format (JSON or key-value);
- required fields: `timestamp`, `level`, `workerId`, `pageId`, `url`, `domain`, `event`, `result`;
- no secrets in logs (DB credentials, tokens).

## Event Types

- frontier claim/release/reschedule;
- fetch start/end and mode (`PLAIN_HTTP`, `HEADLESS`);
- parse summary (links/images extracted);
- dedup decisions (URL/content);
- persistence outcomes and failures.

## Metrics

- queue depth (`FRONTIER` count);
- fetch latency histogram by domain;
- success/error counts by category (`TS-12`);
- rate-limit waits and backoff events;
- dedup hit rates.

## Run Summary

At crawler stop, emit summary:

- total URLs seen;
- total HTML, BINARY, DUPLICATE pages;
- total errors by category;
- top domains by fetch count.

## Required Tests

- required log fields present on critical events;
- metrics counters increment correctly;
- summary generated at graceful shutdown.

