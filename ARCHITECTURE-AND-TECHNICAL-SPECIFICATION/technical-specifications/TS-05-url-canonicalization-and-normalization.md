# TS-05 URL Canonicalization And Normalization

## Responsibility

Transform raw/discovered URLs into canonical, dedup-safe forms.

## Library Constraint

- MUST use `iipc/urlcanon` WHATWG canonicalization.
- MUST NOT use `crawler4j` URL canonicalizer.

## Normalization Pipeline

1. resolve relative URL against base;
2. canonicalize via WHATWG rules;
3. strip fragment;
4. apply query parameter allowlist;
5. emit canonical URL string.

## Query Policy

- keep meaningful parameters (e.g., `page` for topic pagination);
- drop noisy/tracking params;
- deterministic ordering of preserved query params.

## Error Policy

- invalid URL -> non-retryable ingestion skip with diagnostic log;
- canonicalization failure MUST NOT crash worker loop.

## Required Tests

- case normalization and path simplification;
- fragment stripping;
- allowlist behavior with mixed query params;
- malformed URL handling.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/normalize/`
- key file(s): `downloader/normalize/UrlCanonicalizer.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/normalize/` and `.../integration/pipeline/`

