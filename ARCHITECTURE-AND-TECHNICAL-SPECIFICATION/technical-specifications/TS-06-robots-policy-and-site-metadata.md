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

## Decision Contract

- ingestion calls `evaluate(canonicalUrl)`:
  - `DISALLOWED` -> drop URL before frontier insertion;
  - `ALLOWED` -> continue ingestion pipeline.

## Persistence Contract

- robots raw text stored once per domain and refreshed by policy;
- sitemap content persisted even if unused by current crawler logic.

## Required Tests

- allow/disallow decisions for known path patterns;
- cache hit/miss behavior;
- DB persistence on first domain encounter;
- parser behavior on 4xx and 5xx robots fetch responses.
