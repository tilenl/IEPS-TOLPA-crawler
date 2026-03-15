# Non-Functional Requirements

## Politeness And Compliance

- MUST enforce per-domain request delay floor of 5 seconds.
- MUST respect `robots.txt` disallow rules.
- SHOULD prioritize robots fetch and caching on first domain encounter.

## Throughput And Latency

- expected single-domain fetch ceiling: ~1 request / 5 seconds.
- worker parallelism SHOULD improve pipeline overlap, not violate domain delay.
- GitHub plain-HTTP path SHOULD reduce renderer overhead for server-rendered pages.

## Reliability

- crawler MUST survive restarts with DB-backed frontier.
- operations SHOULD be idempotent where possible (link insertion, retries, upserts).
- failures MUST be classified and recovered by policy.

## Maintainability

- each component MUST have a stable interface contract.
- SQL operations MUST be documented by method contract.
- tests MUST cover both component behavior and integration boundaries.

## Observability

- structured logs MUST include URL, domain, worker, page state transition.
- metrics SHOULD include queue depth, fetch latency, error counts, dedup hit rates.

