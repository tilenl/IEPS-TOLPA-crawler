# TS-05 URL Canonicalization And Normalization

## Responsibility

Transform raw/discovered URLs (`href`/`onclick`) into deterministic, dedup-safe canonical URLs before any URL-level deduplication or frontier insertion.

Canonicalization is a **Stage A ingestion concern**: it happens before robots checks and DB URL existence checks.

## Library Constraint

- MUST use `iipc/urlcanon` WHATWG canonicalization.
- MUST NOT use `crawler4j` URL canonicalizer.
- MUST preserve browser-compatible parsing semantics (WHATWG behavior), not ad-hoc regex-only URL rewriting.

## Normalization Pipeline

1. resolve relative URL against page base URL;
2. canonicalize via WHATWG (`iipc/urlcanon`);
3. strip fragment (`#...`);
4. apply GitHub query parameter allowlist;
5. emit canonical URL string used by DB dedup and frontier insertion.

## Step-By-Step Semantics (with examples)

### 1) Resolve Relative URL Against Base

Convert extracted relative or scheme-relative links into absolute URLs using the current page URL as base.

Why this is required:
- the same target can appear as relative on one page and absolute on another;
- deduplication is impossible if both forms are stored separately.

Examples:
- base: `https://github.com/topics/image-segmentation`
- extracted: `../torvalds/linux` -> absolute: `https://github.com/torvalds/linux`
- extracted: `//github.com/explore` -> absolute: `https://github.com/explore`

### 2) WHATWG Canonicalize (`iipc/urlcanon`)

Run WHATWG canonicalization to normalize equivalent URLs using browser rules.

Why this is required:
- users/pages can emit many syntactic variants that point to the same resource;
- deterministic canonical output prevents false "new URL" insertions.

Typical effects:
- lowercase scheme and host;
- remove/default-normalize port where applicable;
- resolve path segments (`.` / `..`);
- normalize percent-encoding as defined by WHATWG.

Example:
- input: `HTTP://EXAMPLE.com:80/foo/../bar`
- canonical: `http://example.com/bar`

### 3) Strip Fragment (`#...`)

`Strip fragment` means removing everything from `#` to end of URL (inclusive), e.g. `#section`, `#L20`, `#readme`.

Why this is required:
- fragments are client-side navigation hints and are not sent in HTTP requests;
- they do not change server-side content retrieval for crawl purposes;
- keeping them creates duplicate frontier entries for the same fetch target.

Examples:
- `https://github.com/org/repo#readme` -> `https://github.com/org/repo`
- `https://example.com/page?x=1#intro` -> `https://example.com/page?x=1`

### 4) Apply GitHub Query Parameter Allowlist

After canonicalization and fragment stripping, keep only semantically meaningful query parameters and drop tracking/noise.

Current policy:
- keep: `page` (pagination semantics);
- drop: tracking parameters (e.g. `utm_*`) and non-essential noise;
- sort preserved params deterministically.

Why this is required:
- query noise causes false non-duplicates;
- preserving meaningful params avoids collapsing genuinely distinct resources.

Examples:
- `/topics/image-segmentation?page=2` -> keep `?page=2`
- `/topics/image-segmentation?page=2&utm_source=homepage` -> `?page=2`
- `?ref_cta=signup` -> removed query
- `?tab=readme` -> removed query (also disallowed by robots in this project context)

### 5) Emit Canonical URL

Output a deterministic canonical URL string for DB dedup and frontier operations.

## Query Policy

- allowlist filtering happens **after** WHATWG canonicalization;
- keep meaningful parameters (currently `page`);
- drop noisy/tracking params (`utm_*`, referral-style noise, etc.);
- deterministic ordering of preserved params is mandatory.

Important note:
- `iipc/urlcanon` WHATWG canonicalizer does not remove query params by itself; this filtering is a separate, required step.

## Error Policy

- scheme allowlist is mandatory: only `http` and `https` URLs are eligible for ingestion;
- non-http(s) schemes (e.g., `javascript:`, `data:`, `file:`, `vbscript:`) MUST be rejected as non-retryable ingestion skips with diagnostic log;
- canonical URL length guard is mandatory for assignment scope:
  - if `finalCanonicalUrl.length() > 3000`, URL MUST be rejected as non-retryable `URL_TOO_LONG`;
  - check MUST run after canonicalization/filtering and before any Stage A DB insert/upsert;
  - rejection MUST be logged with source context and MUST NOT crash or block batch processing.
- invalid URL -> non-retryable ingestion skip with diagnostic log;
- canonicalization failure MUST NOT crash worker loop.
- malformed relative URL or illegal URI reconstruction after filtering -> skip URL, log reason and source page context.
- processing continues for remaining extracted URLs in the same page batch.

## Contract With Other Stages

- **Input:** raw discovered URL + base page URL.
- **Output:** canonical absolute URL used by robots check, dedup checks, scoring, and frontier insertion.
- robots evaluation must use canonical URL (not raw extracted URL).
- DB uniqueness checks must use canonical URL only.
- URL dedup authority is `page.url` uniqueness in PostgreSQL; normative insert contract is **`insertFrontierIfAbsent`** in [TS-10](TS-10-storage-and-sql-contracts.md) (sentinel `ON CONFLICT ... DO UPDATE ... RETURNING id`)—not `ON CONFLICT DO NOTHING` plus a follow-up `SELECT` as the primary path.

## Reference Implementation Sketch

```java
ParsedUrl parsed = ParsedUrl.parseUrl(resolvedAbsoluteUrl);
Canonicalizer.WHATWG.canonicalize(parsed);
String canonical = parsed.toString();

String noFragment = stripFragment(canonical);          // remove '#...'
String filtered = applyQueryAllowlist(noFragment);     // keep only meaningful params
String finalCanonicalUrl = filtered;
```

## Required Tests

- relative-to-absolute resolution against base URL;
- scheme/host normalization and path simplification (`..`, `.`);
- percent-encoding normalization behavior as produced by WHATWG;
- fragment stripping correctness (`#...` removed, query preserved);
- allowlist behavior with mixed params (`page` kept, `utm_*` dropped);
- deterministic query ordering for preserved params;
- malformed URL handling without worker-loop crash;
- end-to-end idempotence: canonicalizing an already canonical URL yields identical output.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/normalize/`
- key file(s): `downloader/normalize/UrlCanonicalizer.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/normalize/` and `.../integration/pipeline/`

