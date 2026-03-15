# TS-06 Robots Policy And Site Metadata

## Responsibility

Manage robots parsing, caching, and persistence in `site` metadata.

## Rules Engine

- parser: `crawler-commons` `SimpleRobotRulesParser`;
- cache: Caffeine domain-keyed rules cache;
- TTL: 24h default, configurable via `TS-13`.

## First Encounter Flow

1. fetch `/robots.txt` for domain via same domain limiter;
2. parse rules and extract sitemap directives;
3. upsert `site` with `robots_content` and `sitemap_content`;
4. cache parsed rules for runtime checks.

Fetcher behavior for robots responses:
- 2xx: parse and enforce returned rules;
- 4xx on `/robots.txt`: treat as allow-all for that domain;
- 3xx/5xx on `/robots.txt`: treat as temporary deny-all until refresh policy permits retry.

## Decision Contract

- ingestion calls `evaluate(canonicalUrl)`:
  - `DISALLOWED` -> drop URL before frontier insertion;
  - `ALLOWED` -> continue ingestion pipeline.

Representative GitHub path decisions (examples):
- allowed: `/topics/image-segmentation`, repository landing pages `/owner/repo`;
- typically disallowed: search pages and deep repository tree/raw paths.

## Persistence Contract

- robots raw text stored once per domain and refreshed by policy;
- sitemap content persisted even if unused by current crawler logic.
- recommended `site` metadata fields used by this spec:
  - `robots_content` (raw robots text);
  - `sitemap_content` (raw or resolved sitemap directive payload).

## Required Tests

- allow/disallow decisions for known path patterns;
- cache hit/miss behavior;
- DB persistence on first domain encounter;
- parser behavior on 4xx and 5xx robots fetch responses.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/politeness/`, `.../storage/postgres/`
- key file(s): `downloader/politeness/PolitenessGate.java`, `storage/postgres/repositories/SiteRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/politeness/` and `.../integration/storage/postgres/`
