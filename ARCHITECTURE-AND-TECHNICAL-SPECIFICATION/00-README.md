# ARCHITECTURE-AND-TECHNICAL-SPECIFICATION

This folder is the implementation-ready replacement for `project-plan.md`.

## Purpose

- provide one architecture source of truth for reporting and onboarding;
- provide technical contracts that can be coded without major ambiguity;
- define testing and readiness gates before implementation starts.

## Reading Order

1. `01-crawler-architecture-overview.md`
2. `02-assignment-compliance-mapping.md`
3. `03-system-sequence-and-dataflow.md`
4. `04-domain-and-scope-definition.md`
5. `05-non-functional-requirements.md`
6. `06-library-rationale-and-usage-matrix.md`
7. `07-implementation-directory-structure-and-ts-mapping.md`
8. `technical-specifications/TS-01-interface-contracts.md` to `technical-specifications/TS-17-implementation-roadmap-and-definition-of-done.md`

## Document Rules

- **Normative words**:
  - `MUST` = mandatory requirement
  - `SHOULD` = recommended default
  - `MAY` = optional
- every technical spec file must include:
  - interfaces/contracts
  - library constraints
  - data flow and edge cases
  - testing requirements
  - implementation location (`primary folder(s)`, `key file(s)`, `test location(s)`)
- implementation location convention:
  - crawler code is implemented under `pa1/crawler/src/main/java/si/uni_lj/fri/wier/`
  - tests are implemented under `pa1/crawler/src/test/java/si/uni_lj/fri/wier/`
  - `cli/Main.java` is the only CLI entrypoint and delegates lifecycle orchestration to `app/PreferentialCrawler.java`
- changes must keep alignment with `assignment.md` and `pa1/db/crawldb.sql`.

## Versioning

- update this folder in the same commit as implementation changes that alter behavior;
- if interface contracts change, update:
  - `TS-01-interface-contracts.md`
  - affected component spec file(s)
  - `TS-16-testing-strategy-and-test-cases.md` acceptance criteria.

## Scope Summary

The crawler focuses on GitHub repositories related to image segmentation, uses a DB-backed preferential frontier, enforces robots/rate-limit policy, extracts required links (`href` and `onclick`) and image references, and stores outputs in PostgreSQL with assignment-compliant schema usage plus allowed extensions.
