# TS-13 Configuration And Runtime Parameters

## Purpose

Define all runtime settings, defaults, validation, and precedence rules.

## Sources And Precedence

1. CLI arguments (highest)
2. environment variables
3. config file (`application.properties` or equivalent)
4. hardcoded defaults (lowest)

## Core Parameters

| Key | Default | Validation | Notes |
| --- | --- | --- | --- |
| `crawler.nCrawlers` | `min(cpu*2, 8)` | `>=1` | worker count |
| `crawler.frontier.pollMs` | `500` | `50..5000` | empty-queue polling |
| `crawler.fetch.connectTimeoutMs` | `5000` | `>=100` | HTTP connect timeout |
| `crawler.fetch.readTimeoutMs` | `10000` | `>=1000` | read/render timeout |
| `crawler.rateLimit.minDelayMs` | `5000` | `>=5000` | assignment floor |
| `crawler.rateLimit.maxBackoffMs` | `300000` | `>=5000` | overload cap |
| `crawler.robots.cacheTtlHours` | `24` | `>=1` | rules cache TTL |
| `crawler.buckets.cacheTtlHours` | `1` | `>=1` | bucket cache TTL |
| `crawler.scoring.keywordConfig` | path | must exist | scorer dictionary |
| `crawler.db.url` | none | required | JDBC url |
| `crawler.db.user` | none | required | DB user |
| `crawler.db.password` | none | required | DB password |

## Startup Validation

- startup MUST fail fast on missing required DB credentials;
- startup MUST fail on invalid numeric ranges;
- startup SHOULD print effective configuration (without secrets).

## Profiles

- `dev`: lower timeouts, smaller worker count, verbose logging.
- `demo`: deterministic seed set and stable scoring config.
- `stress`: higher workers, strict metrics, long-running mode.

## Required Tests

- precedence resolution tests;
- invalid value rejection tests;
- effective config snapshot tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/config/`, `.../cli/`, `.../app/`
- key file(s):
  - `config/RuntimeConfig.java`
  - `cli/Main.java` (CLI argument precedence and binding)
  - `app/PreferentialCrawler.java` (startup preflight validation and effective config logging without secrets)
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/config/`, `.../unit/cli/`, `.../integration/app/`
