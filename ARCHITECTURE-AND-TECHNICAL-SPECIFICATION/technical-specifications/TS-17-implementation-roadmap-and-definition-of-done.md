# TS-17 Implementation Roadmap And Definition Of Done

## Recommended Implementation Order

1. `TS-11` schema extensions for queue leases/retry durability/content ownership.
   - execute schema manually in local workflow before crawler runtime; app startup performs validation only (no DDL-on-start).
2. `TS-10` storage contracts with atomic claim/reschedule/dedup transactions.
3. `TS-07` frontier claim/dequeue with due-time eligibility and lease recovery.
4. `TS-12` error transitions wired to durable retry state.
5. `TS-13` runtime configuration for retry, budgets, and headless capacity.
6. `TS-03` fetcher capacity governance + `TS-14` concurrency saturation handling.
7. `TS-06` bounded robots temporary-deny policy.
8. `TS-02` worker orchestration integration with backpressure and budget decisions.
9. `TS-09` atomic content dedup integration path validation.
10. `TS-15` observability + healthcheck finalization.
11. `TS-16` concurrency/restart race suite as release gate.

## Milestones

- **M1**: Schema and SQL contracts enforce atomic claim, retry durability, and dedup ownership.
- **M2**: Worker pipeline handles retries, budgets, and saturation without duplicate processing.
- **M3**: Robots temporary-deny and headless-capacity policies are bounded and recoverable.
- **M4**: Observability/health checks and race-condition tests pass as release gates.

## Definition Of Done (Coding Phase Entry)

- interfaces in `TS-01` are stable and agreed by team;
- SQL contracts in `TS-10` validated on local PostgreSQL;
- migration execution path and rollback runbook are documented and reviewed (`TS-11`, `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/migration-rollback-runbook.md`);
- worker flow in `TS-02` implemented with deterministic behavior;
- error policy in `TS-12` and configuration in `TS-13` enforced in runtime;
- test gates from `TS-16` pass in local and CI runs.
- no unresolved contradictions among `TS-07`, `TS-10`, `TS-11`, and `TS-12` for claim/retry semantics.

## Definition Of Done (Feature Completion)

- assignment compliance checks pass (`TS-02`, `TS-04`, `TS-06`, `TS-08`);
- no critical open issues in dedup/frontier correctness;
- run summary generated and reproducible with seed set.
- global page cap (`5000`) and queue budget behavior verified in integration tests.

## Implementation Location

- primary folder(s): all mapped implementation folders in `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/07-implementation-directory-structure-and-ts-mapping.md`
- key file(s):
  - `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/07-implementation-directory-structure-and-ts-mapping.md`
  - `pa1/crawler/src/main/java/si/uni_lj/fri/wier/cli/Main.java`
  - `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/PreferentialCrawler.java`
- test location(s): roadmap milestones are validated through `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/` and `.../integration/`
