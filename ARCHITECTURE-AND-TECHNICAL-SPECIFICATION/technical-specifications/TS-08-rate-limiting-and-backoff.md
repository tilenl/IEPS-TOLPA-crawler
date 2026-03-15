# TS-08 Rate Limiting And Backoff

## Responsibility

Prevent excessive requests to same domain/server and handle overload responses.

## Policy

- base floor: 1 request per 5 seconds per domain;
- if robots `Crawl-delay` is larger, use the larger value;
- policy is enforced via Bucket4j buckets keyed by domain.
- architecture choice: politeness is per-domain in this project profile.

## Bucket Contract

- `tryConsumeAndReturnRemaining(1)` MUST be used for exact wait time;
- worker MUST reschedule when token unavailable (never busy-wait block).
- robots fetch (`/robots.txt`) MUST use the same domain bucket and consume a token exactly like content-page fetches.
- limiter bypass for robots fetch is forbidden.

Delay computation example:

```java
long buildCrawlDelayMs(long robotsDelayMs) {
    return Math.max(robotsDelayMs > 0 ? robotsDelayMs : 0, 5000L);
}
```

## Backoff State Machine

- on HTTP `429` or `503`: exponential backoff by domain;
- backoff resets on successful or non-overload response;
- cap maximum backoff with configurable upper bound.

Reference formula:

```java
long delayMs = Math.min(5000L * (1L << failures), maxBackoffMs);
```

## Cache

- bucket registry uses Caffeine with inactivity eviction;
- size and eviction policy configurable in `TS-13`.

## Required Tests

- 5-second floor enforcement;
- robots-delay override;
- overload backoff growth and reset behavior;
- non-blocking worker reschedule behavior.
- robots fetch token-consumption test using shared domain limiter bucket.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/scheduler/policies/`, `.../downloader/politeness/`
- key file(s): `scheduler/policies/SchedulingPolicy.java`, `downloader/politeness/PolitenessGate.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/scheduler/policies/`, `.../unit/downloader/politeness/`, `.../integration/pipeline/`
