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

## Scalability ceiling (architecture note)

- The **preferential frontier** is implemented as **PostgreSQL `page` rows** with atomic **`UPDATE ... RETURNING`** claims and a priority index ([TS-07](technical-specifications/TS-07-frontier-and-priority-dequeue.md)). This design is **appropriate for assignment scale** (bounded pages, modest parallelism) and keeps **durability and simplicity**.
- It is **not** targeted at **internet-scale** throughput: at very high worker counts or queue depth, **claim churn** can become **Postgres-bound** (hot index pages, write contention). Horizontal scale-out of multiple JVMs against one DB without external coordination would also break in-process politeness assumptions ([01-crawler-architecture-overview.md](01-crawler-architecture-overview.md)).
- **Future** mitigations (out of assignment scope unless required): table partitioning, partial indexes, external queue, or sharded databases—documented here only as **known limits**, not required work.

