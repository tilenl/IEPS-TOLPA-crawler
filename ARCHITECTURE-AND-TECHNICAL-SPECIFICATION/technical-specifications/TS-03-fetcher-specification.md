# TS-03 Fetcher Specification

## Responsibility

Download/render page content and return normalized fetch response metadata.

## Boundary With Parser

- `Fetcher` is responsible for transport and rendering only (status/content-type/body/timestamps/mode);
- HTML DOM traversal and link/image extraction are parser responsibilities in `TS-04` using `Jsoup`;
- `Fetcher` MUST NOT implement link extraction logic to avoid duplicated parsing behavior.

## Domain-Aware Strategy

- `github.com`: default to Java `HttpClient` fetch + Jsoup parse compatibility.
- external JS-heavy domains: Selenium headless Chrome path.
- fallback MAY switch to Selenium when plain HTTP fetch indicates incomplete shell.

## FetchResult Contract

- `canonicalUrl`
- `httpStatusCode`
- `contentType`
- `body` (optional for binary outcomes)
- `accessedTime`
- `fetchMode` (`PLAIN_HTTP` or `HEADLESS`)

## Timeout Policy

- connect timeout MUST be configured;
- read/render timeout MUST be configured;
- timeout errors map to retryable fetch failures per `TS-12`.

## Content Classification

- HTML-like content -> parse candidate;
- binary document types (`.doc`, `.docx`, `.ppt`, `.pptx`) -> `BINARY` handling;
- unsupported/empty body -> recoverable failure or non-HTML outcome by policy.

## Required Tests

- mode selection tests by domain;
- timeout and HTTP error mapping tests;
- binary-vs-html classification tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/fetch/`
- key file(s): `downloader/fetch/HttpFetcher.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/fetch/` and `.../integration/downloader/fetch/`

