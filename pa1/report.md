# PA1 Assignment Report

## Introduction
This report describes the implementation of a preferential web crawler for the `github.com` domain, focused on **image segmentation**.

## Crawler Architecture
The crawler consists of five main components as specified in the assignment:
1. **HTTP Downloader and Renderer**: Uses Selenium (headless Chromium) for rendering and Java 21 `HttpClient` for optimized fetching.
2. **Data Extractor**: Extracts hyperlinks (including `onclick` events) and image references.
3. **Duplicate Detector**: Implements SHA-256 content hashing for detecting duplicate pages.
4. **URL Frontier**: A database-backed priority queue using PostgreSQL `FOR UPDATE SKIP LOCKED` for multi-worker safety.
5. **Datastore**: A PostgreSQL database following the `crawldb.sql` schema.

## Preferential Crawling Strategy
Relevance is computed at discovery time based on repository metadata (name, description, topic tags) and anchor text. Higher relevance scores correspond to higher crawl priority in the frontier.

## Deduplication Strategy
- **URL Deduplication**: Handled by the database unique constraint on the `url` column.
- **Content Deduplication**: SHA-256 hash of the HTML body is compared against existing hashes in the database.

## Statistics
(To be populated after crawling)

---

### IMPORTANT: Submission Note
The final submission in the `pa1` directory should only contain the `report.pdf` version of this report. This markdown file is for development purposes only.

To convert this markdown file into a PDF, you can use tools like `pandoc`, `grip`, or VSCode's "Markdown PDF" extension.
