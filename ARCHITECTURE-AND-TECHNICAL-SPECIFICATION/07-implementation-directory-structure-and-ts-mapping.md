# Implementation Directory Structure And TS Mapping

## Purpose

Define one implementation ownership map so any developer can pick a technical specification (`TS-01` to `TS-17`) and immediately know where to implement code and tests.

## Source Roots

- main source root: `pa1/crawler/src/main/java/si/uni_lj/fri/wier/`
- test source root: `pa1/crawler/src/test/java/si/uni_lj/fri/wier/`
- test resources root: `pa1/crawler/src/test/resources/`

## Directory Tree (Normative)

```text
pa1/crawler/
  src/main/java/si/uni_lj/fri/wier/
    cli/
      Main.java
    app/
      PreferentialCrawler.java
    scheduler/
      policies/
    queue/
      claim/
      enqueue/
      dedup/
    downloader/
      worker/
      fetch/
      politeness/
      extract/
      normalize/
      dedup/
    storage/
      postgres/
        repositories/
        mappers/
        transactions/
      frontier/
    contracts/
    config/
    error/
    observability/
  src/test/java/si/uni_lj/fri/wier/
    unit/
    integration/
  src/test/resources/
```

## Canonical Files Policy

Predefine canonical files and interfaces for the first implementation pass. Helper classes and internal utilities MAY evolve during implementation.

- required bootstrap files:
  - `cli/Main.java`
  - `app/PreferentialCrawler.java`
- required contract interfaces:
  - `contracts/Scheduler.java`
  - `contracts/Frontier.java`
  - `contracts/Worker.java`
  - `contracts/Fetcher.java`
  - `contracts/Parser.java`
  - `contracts/Canonicalizer.java`
  - `contracts/RelevanceScorer.java`
  - `contracts/Storage.java`

Canonical first-pass classes per owned submodule:

- `scheduler/policies/SchedulingPolicy.java`
- `queue/claim/ClaimService.java`
- `queue/enqueue/EnqueueService.java`
- `queue/dedup/UrlSeenIndex.java`
- `downloader/worker/WorkerLoop.java`
- `downloader/fetch/HttpFetcher.java`
- `downloader/politeness/PolitenessGate.java`
- `downloader/extract/HtmlParser.java`
- `downloader/normalize/UrlCanonicalizer.java`
- `downloader/dedup/ContentHasherImpl.java`
- `storage/frontier/FrontierStore.java`
- `storage/postgres/repositories/PageRepository.java`

## TS Ownership Matrix

| Technical Specification | Primary Folder(s) | Key File(s) |
| --- | --- | --- |
| `TS-01` Interface Contracts | `contracts/` | `Scheduler.java`, `Frontier.java`, `Worker.java`, `Fetcher.java`, `Parser.java`, `Canonicalizer.java`, `RelevanceScorer.java`, `Storage.java` |
| `TS-02` Worker Orchestration And Pipeline | `app/`, `scheduler/`, `downloader/worker/` | `PreferentialCrawler.java`, `SchedulingPolicy.java`, `WorkerLoop.java` |
| `TS-03` Fetcher Specification | `downloader/fetch/` | `HttpFetcher.java` |
| `TS-04` Parser And Extraction | `downloader/extract/` | `HtmlParser.java` |
| `TS-05` URL Canonicalization | `downloader/normalize/` | `UrlCanonicalizer.java` |
| `TS-06` Robots Policy And Site Metadata | `downloader/politeness/` | `PolitenessGate.java` |
| `TS-07` Frontier And Priority Dequeue | `queue/claim/`, `queue/enqueue/`, `storage/frontier/` | `ClaimService.java`, `EnqueueService.java`, `FrontierStore.java` |
| `TS-08` Rate Limiting And Backoff | `scheduler/policies/`, `downloader/politeness/` | `SchedulingPolicy.java`, `PolitenessGate.java` |
| `TS-09` Deduplication URL And Content | `queue/dedup/`, `downloader/dedup/`, `storage/postgres/` | `UrlSeenIndex.java`, `ContentHasherImpl.java`, `PageRepository.java` |
| `TS-10` Storage And SQL Contracts | `storage/` | `PageRepository.java`, `FrontierStore.java` |
| `TS-11` Database Schema And Migrations | `storage/postgres/`, `pa1/db/` | `PageRepository.java`, migration SQL files in `pa1/db/` |
| `TS-12` Error Model And Recovery Policy | `error/` | `CrawlerErrorCategory.java`, `RecoveryPolicy.java` |
| `TS-13` Configuration And Runtime Parameters | `config/`, `cli/`, `app/` | `RuntimeConfig.java`, `Main.java`, `PreferentialCrawler.java` |
| `TS-14` Concurrency And Threading Model | `app/`, `scheduler/`, `downloader/worker/`, `config/` | `PreferentialCrawler.java`, `SchedulingPolicy.java`, `WorkerLoop.java`, `RuntimeConfig.java` |
| `TS-15` Observability Logging And Metrics | `observability/` | `CrawlerMetrics.java`, `RunSummaryReporter.java` |
| `TS-16` Testing Strategy And Test Cases | `src/test/java/.../unit/`, `src/test/java/.../integration/`, `src/test/resources/` | `*UnitTest` and `*IT` suites by component |
| `TS-17` Implementation Roadmap And Definition Of Done | architecture docs and all owned folders above | `TS-17-implementation-roadmap-and-definition-of-done.md` cross-references all owned paths |

## Test Organization Convention

- tests MUST remain under `src/test/java`; tests MUST NOT be colocated in `src/main/java`.
- `unit/` contains deterministic component tests (`*UnitTest`).
- `integration/` contains DB/network/cross-component tests (`*IT`).
- shared fixtures live in `src/test/resources/`.

## Bootstrap And Preflight Convention

- `cli/Main.java` is the only entrypoint for CLI argument parsing and startup invocation.
- `app/PreferentialCrawler.java` owns composition, lifecycle startup, and preflight checks before worker loops start.
- minimum preflight checks:
  - required runtime configuration present and valid;
  - required DB connection parameters present;
  - DB reachability check before starting workers.

## Drift Prevention Rules

- every TS must include an `Implementation Location` section.
- every owned submodule must declare at least one canonical class before implementation.
- no new top-level package may be added without updating this mapping.
- when a mapped file/folder moves, update both this file and affected TS documents in the same commit.
- implementation classes SHOULD include a short TS ownership tag in class-level Javadoc (for example `TS-04`).
