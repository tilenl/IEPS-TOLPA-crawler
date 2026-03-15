# TS-17 Implementation Roadmap And Definition Of Done

## Recommended Implementation Order

1. `TS-11` schema extensions and migration scripts.
2. `TS-10` storage contracts with integration tests.
3. `TS-05` canonicalizer + `TS-06` robots policy.
4. `TS-07` frontier claim/dequeue + scheduler skeleton.
5. `TS-03` fetcher and `TS-04` parser.
6. `TS-09` dedup logic + `TS-08` limiter/backoff.
7. `TS-02` worker orchestration integration.
8. `TS-15` observability finalization and run summary.

## Milestones

- **M1**: DB + frontier claim path works with tests.
- **M2**: fetch + parse + ingestion pipeline operational.
- **M3**: dedup + rate-limit + robots fully enforced.
- **M4**: observability + quality gates ready for report/demo.

## Definition Of Done (Coding Phase Entry)

- interfaces in `TS-01` are stable and agreed by team;
- SQL contracts in `TS-10` validated on local PostgreSQL;
- worker flow in `TS-02` implemented with deterministic behavior;
- error policy in `TS-12` and configuration in `TS-13` enforced in runtime;
- test gates from `TS-16` pass in local and CI runs.

## Definition Of Done (Feature Completion)

- assignment compliance checks pass (`TS-02`, `TS-04`, `TS-06`, `TS-08`);
- no critical open issues in dedup/frontier correctness;
- run summary generated and reproducible with seed set.
