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

## Image references in the database (hash-like names and `BINARY` content type)

During manual inspection of the `crawldb.image` table, some rows have a `filename` that looks like a long opaque string (similar to a content hash) with **no** conventional extension (`.png`, `.webp`, `.jpg`, and so on). That can look odd in a table of “images,” but **manual checks** (opening the URL or inspecting the response) often show **real images** served from CDNs or apps that omit extensions in the path.

The crawler stores **metadata only** for images (URL reference, filename segment, `accessed_time`); it does **not** download image bytes. Per the assignment, **`content_type` is always the literal `BINARY`** for every `img[src]` row—not a MIME type such as `image/png` and not `NULL`. The filename column still reflects the last path segment when present, which may or may not include an extension.

**Migrating older databases:** if you crawled before this rule, run  
`UPDATE crawldb.image SET content_type = 'BINARY' WHERE content_type IS NULL OR content_type <> 'BINARY';`  
(adjust as needed; back up first).

For future work, true MIME types would require an extra request or sniffing and are out of current scope.

---

### IMPORTANT: Submission Note
The final submission in the `pa1` directory should only contain the `report.pdf` version of this report. This markdown file is for development purposes only.

To convert this markdown file into a PDF, you can use tools like `pandoc`, `grip`, or VSCode's "Markdown PDF" extension.
