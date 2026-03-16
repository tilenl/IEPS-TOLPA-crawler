# TS-06 Robots Policy And Site Metadata

## Responsibility

Manage robots parsing, caching, and persistence in `site` metadata.

## Rules Engine

- parser: `crawler-commons` `SimpleRobotRulesParser`;
- cache: Caffeine domain-keyed rules cache;
- TTL: 24h default, configurable via `TS-13`.
- temporary deny window and retry cadence MUST be configurable via `TS-13`.
- first-encounter robots load MUST be single-flight per domain (concurrent workers share one in-progress load).

## First Encounter Flow

1. acquire per-domain robots load guard (`domain` single-flight owner);
2. if rules already loaded by another worker while waiting, return cached rules and release guard;
3. fetch `/robots.txt` for domain via same domain limiter;
4. parse rules and extract sitemap directives;
5. upsert `site` with `robots_content` and `sitemap_content`;
6. cache parsed rules for runtime checks and release guard.

Fetch-path trigger:
- Stage B worker flow (`TS-02`) MUST call `robotsTxtCache.ensureLoaded(domain)` before any `fetcher.fetch(url)` call;
- this applies to seed/bootstrap URLs and discovered URLs equally.

Robots limiter contract (normative):
- robots fetch MUST consume one token from the same per-domain bucket used for content fetches;
- if no token is currently available, robots fetch MUST be delayed/rescheduled (never bypass limiter);
- robots fetch and normal page fetches therefore share a single politeness budget per domain.

Fetcher behavior for robots responses:
- 2xx: parse and enforce returned rules;
- 4xx on `/robots.txt`: treat as allow-all for that domain;
- 3xx/5xx on `/robots.txt`: treat as temporary deny-all with bounded duration.

Temporary deny policy (normative):
- initial 3xx/5xx failure sets domain robots state to `TEMPORARY_DENY` with `deny_until = now() + crawler.robots.temporaryDenyRetryMinutes`;
- each consecutive robots failure extends `deny_until` with capped backoff and jitter;
- `deny_until` MUST never exceed `now() + crawler.robots.temporaryDenyMaxMinutes`;
- once max temporary deny duration is exhausted, policy transitions to terminal robots error classification (`ROBOTS_TRANSIENT` budget exhausted in `TS-12`);
- successful robots refresh resets transient failure counter and clears temporary deny status.

## Decision Contract

- ingestion calls `evaluate(canonicalUrl)`:
  - `DISALLOWED` -> drop URL before frontier insertion;
  - `TEMPORARY_DENY` -> defer URL by setting `next_attempt_at` and keeping `FRONTIER` state;
  - `ALLOWED` -> continue ingestion pipeline.
- fetch path precondition:
  - caller MUST ensure rules are loaded for URL domain before calling `evaluate(canonicalUrl)`;
  - for content-fetch decisions, calling `evaluate(...)` without prior `ensureLoaded(domain)` is contract violation.

Representative GitHub path decisions (examples):
- allowed: `/topics/image-segmentation`, repository landing pages `/owner/repo`;
- typically disallowed: search pages and deep repository tree/raw paths.

## Persistence Contract

- robots raw text stored once per domain and refreshed by policy;
- sitemap content persisted even if unused by current crawler logic.
- `site` metadata fields required by this spec:
  - `robots_content` (raw robots text);
  - `sitemap_content` (raw or resolved sitemap directive payload).
- transient robots diagnostics (`robots_last_status`, `robots_failures`, `robots_deny_until`) are runtime in-memory state in this architecture profile and are emitted through observability (`TS-15`) rather than persisted in `site`.

## Required Tests

- allow/disallow decisions for known path patterns;
- cache hit/miss behavior;
- DB persistence on first domain encounter;
- parser behavior on 4xx and 5xx robots fetch responses.
- bounded temporary-deny behavior and max-deny cap;
- recovery from temporary deny after successful robots refresh.
- robots fetch rate-limit test proving token consumption through the same domain bucket.
- concurrent first-encounter test: N workers for same new domain produce a single robots fetch (single-flight guard).
- fetch-path precondition test: worker loads robots before `evaluate(...)` and before content fetch.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/politeness/`, `.../storage/postgres/`
- key file(s): `downloader/politeness/PolitenessGate.java`, `storage/postgres/repositories/SiteRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/politeness/` and `.../integration/storage/postgres/`
