# TS-01 Required Tests Tracking

This file tracks TS-01 required unit-test coverage that is not yet implemented in code.

Source of requirements:
- `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/technical-specifications/TS-01-interface-contracts.md`

Current status: partially covered by existing contract tests under `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/contracts/`.

## Covered

- null/empty handling in `ParseResult` (`ParseResultContractTest`)
- deterministic hashing behavior in `ContentHasherImpl` (`ContentHasherContractTest`)
- frontier ownership path through `Frontier` facade (`ContractFrontierTest`)

## Deferred coverage (open items)

- relevance score range tests (`0.0` to `1.0`) for `RelevanceScorer` behavior
- idempotence tests for ingestion contracts
- error mapping tests for interface exceptions
- ownership tests for SQL conflict/dedup operations via `Storage` boundary
- robots contract test: fetch path calls `ensureLoaded(domain)` before `evaluate(...)`
- robots contract test: concurrent `ensureLoaded(domain)` produces single fetch side effect

## Next implementation pass

When resumed, add missing tests in:
- `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/contracts/`
- `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/`

Then execute:

```bash
cd pa1
./gradlew compileTestJava
./gradlew test --tests '*contracts*'
```
