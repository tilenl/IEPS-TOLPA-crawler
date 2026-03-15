# TS-08 Rate Limiting And Backoff

## Responsibility

Prevent excessive requests to same domain/server and handle overload responses.

## Policy

- base floor: 1 request per 5 seconds per domain;
- if robots `Crawl-delay` is larger, use the larger value;
- policy is enforced via Bucket4j buckets keyed by domain.

## Bucket Contract

- `tryConsumeAndReturnRemaining(1)` MUST be used for exact wait time;
- worker MUST reschedule when token unavailable (never busy-wait block).

## Backoff State Machine

- on HTTP `429` or `503`: exponential backoff by domain;
- backoff resets on successful or non-overload response;
- cap maximum backoff with configurable upper bound.

## Cache

- bucket registry uses Caffeine with inactivity eviction;
- size and eviction policy configurable in `TS-13`.

## Required Tests

- 5-second floor enforcement;
- robots-delay override;
- overload backoff growth and reset behavior;
- non-blocking worker reschedule behavior.
