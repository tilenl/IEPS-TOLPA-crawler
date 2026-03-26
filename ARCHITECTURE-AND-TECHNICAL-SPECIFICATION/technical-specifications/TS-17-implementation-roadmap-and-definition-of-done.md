# TS-17 Implementation Roadmap And Definition Of Done

## Recommended Implementation Order

1. `TS-01` interface contracts — stable module boundaries and types before storage and runtime wiring.
2. `TS-11` schema extensions for queue leases/retry durability/content ownership.
   - execute schema manually in local workflow before crawler runtime; app startup performs validation only (no DDL-on-start).
3. `TS-10` storage contracts with atomic claim/reschedule/dedup transactions.
4. `TS-05` URL canonicalization and normalization — consistent keys for frontier enqueue and dedup (`TS-09`).
5. `TS-07` frontier claim/dequeue with due-time eligibility and lease recovery.
6. `TS-12` error transitions wired to durable retry state.
7. `TS-13` runtime configuration for retry, budgets, and headless capacity.
8. `TS-03` fetcher capacity governance + `TS-14` concurrency saturation handling + `TS-08` rate limiting and backoff.
9. `TS-06` bounded robots temporary-deny policy.
10. `TS-02` worker orchestration + `TS-04` parser and extraction — end-to-end pipeline with backpressure and budget decisions.
11. `TS-09` atomic content dedup integration path validation.
12. `TS-15` observability + healthcheck finalization.
13. `TS-16` concurrency/restart race suite as release gate.

## Milestones

- **M1**: `TS-01` interfaces agreed; schema and SQL contracts (`TS-11`, `TS-10`) enforce atomic claim, retry durability, and dedup ownership; `TS-05` normalization rules align with storage keys.
- **M2**: Worker pipeline (`TS-02`, `TS-04`) handles retries, budgets, and saturation (`TS-03`, `TS-08`, `TS-14`) without duplicate processing.
- **M3**: Robots temporary-deny (`TS-06`) and headless-capacity policies (`TS-13`) are bounded and recoverable.
- **M4**: Observability/health checks (`TS-15`) and race-condition tests (`TS-16`) pass as release gates.

## Definition Of Done (Coding Phase Entry)

- interfaces in `TS-01` are stable and agreed by team;
- `TS-05` canonicalization/normalization behavior is implemented and consistent at frontier and dedup boundaries (`TS-07`, `TS-09`);
- SQL contracts in `TS-10` validated on local PostgreSQL;
- migration execution path and rollback runbook are documented and reviewed (`TS-11`, `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/migration-rollback-runbook.md`);
- worker flow in `TS-02` implemented with deterministic behavior; parser/extraction per `TS-04` matches pipeline contracts;
- fetch, rate limiting, and backoff per `TS-03` and `TS-08` enforced in runtime;
- error policy in `TS-12` and configuration in `TS-13` enforced in runtime;
- test gates from `TS-16` pass in local and CI runs.
- no unresolved contradictions among `TS-07`, `TS-10`, `TS-11`, and `TS-12` for claim/retry semantics.

## Definition Of Done (Feature Completion)

- assignment compliance checks pass (`TS-02`, `TS-04`, `TS-05`, `TS-06`, `TS-08`);
- no critical open issues in dedup/frontier correctness (`TS-05`, `TS-07`, `TS-09`);
- run summary generated and reproducible with seed set.
- global page cap (`5000`) and queue budget behavior verified in integration tests.

## Implementation Location

- primary folder(s): all mapped implementation folders in `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/07-implementation-directory-structure-and-ts-mapping.md`
- key file(s):
  - `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/07-implementation-directory-structure-and-ts-mapping.md`
  - `pa1/crawler/src/main/java/si/uni_lj/fri/wier/cli/Main.java`
  - `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/PreferentialCrawler.java`
- test location(s): roadmap milestones are validated through `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/` and `.../integration/`
