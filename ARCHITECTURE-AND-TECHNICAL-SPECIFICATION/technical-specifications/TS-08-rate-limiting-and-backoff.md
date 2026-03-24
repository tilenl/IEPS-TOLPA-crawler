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
- **Worker loop (before `fetcher.fetch`):** when `tryAcquire` indicates delay for the **claimed URL’s domain**, the worker MUST **`frontier.reschedule`** — **no busy-wait** on that outer gate ([TS-02](TS-02-worker-orchestration-and-pipeline.md)).
- **Redirect-chain exception ([TS-03](TS-03-fetcher-specification.md)):** during **hop-by-hop** content fetch **inside** `Fetcher`, after a `3xx` targets another host (or same host), if `tryAcquire` for **that hop’s** domain returns a delay, the implementation **MAY block** the calling thread for the wait **only while** remaining **`claim_expires_at`** margin is sufficient to finish fetch + persist; otherwise the fetch MUST end so the worker can **`frontier.reschedule`** and restart from the **claimed URL** on the next claim.
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

## Per-domain bucket cache (normative)

- Bucket4j state for a domain encodes spacing relative to the **last** request. If a Caffeine entry is **evicted** and a **new** bucket is created, the process can immediately send another request **without** respecting the 5s floor since the real last HTTP request—**lost state**, not a skipped `tryAcquire` check. That violates [05-non-functional-requirements.md](../05-non-functional-requirements.md) (“MUST enforce per-domain request delay floor of 5 seconds”) for domains that are still crawlable in the same run.
- **Assignment-scale policy:** Configure the bucket registry so entries are **not** dropped under normal assignment loads—use **very large** `crawler.buckets.cacheMaxEntries` and a **TTL far longer** than any realistic idle gap between requests to the same host (normative defaults in [TS-13](TS-13-configuration-and-runtime-parameters.md)). Operators who tighten eviction MUST verify politeness under load.
- **Future / internet-scale:** If strong eviction or multiple processes are required, add a **durable** last-request timestamp per domain (e.g. on `site`) and combine with buckets; that is **out of assignment scope** unless explicitly added.

## Required Tests

- 5-second floor enforcement;
- robots-delay override;
- overload backoff growth and reset behavior;
- non-blocking worker reschedule behavior on the **pre-fetch** gate;
- redirect mid-chain: bounded block within lease vs reschedule when lease margin insufficient ([TS-03](TS-03-fetcher-specification.md)).
- robots fetch token-consumption test using shared domain limiter bucket.
- regression: with default `crawler.buckets.*` from [TS-13](TS-13-configuration-and-runtime-parameters.md), per-domain spacing is not reset by TTL eviction during a bounded assignment crawl (no burst below the floor after long idle on a domain still in scope).

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/scheduler/policies/`, `.../downloader/politeness/`
- key file(s): `scheduler/policies/SchedulingPolicy.java`, `downloader/politeness/PolitenessGate.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/scheduler/policies/`, `.../unit/downloader/politeness/`, `.../integration/pipeline/`
