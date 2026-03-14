-------
The following text is a description of the `crawler-architecture.png` image:
-------



### High-level view

The diagram shows a **classic pull-based web crawler pipeline** with four main internal components:

- **Scheduler**
- **Queue**
- **Multi-threaded downloader**
- **Storage**

and one external system:

- **World Wide Web** (set of target websites).

The focus is on **URL flow** and **data flow** between these components.

---

### Components and connections

- **Scheduler → Multi-threaded downloader**
  - The **Scheduler** sends **URLs** to the **Multi-threaded downloader**.
  - This represents the crawler’s decision logic: picking which URL to fetch next according to policies (priority, politeness, deduplication, etc.).
  - The transport is unidirectional in the diagram: Scheduler *pushes* URLs to the downloader.

- **Multi-threaded downloader → World Wide Web**
  - The **Multi-threaded downloader** issues HTTP requests for the received URLs to the **World Wide Web**.
  - The diagram abstracts this as the downloader pulling **Web pages** from the Web.

- **World Wide Web → Multi-threaded downloader**
  - Arrows labeled **“Web pages”** come back from the Web to the **Multi-threaded downloader**, representing HTTP responses (HTML, JSON, etc.).

- **Multi-threaded downloader → Storage**
  - From the downloader there is an outgoing arrow labeled **“Data”** into **Storage**.
  - This means fetched content (and potentially metadata like status codes, headers, parsed fields) is persisted in Storage (e.g., database, file system, index).

- **Multi-threaded downloader → Queue**
  - There is a downward arrow from the **Multi-threaded downloader** to the **Queue**, labeled **“URLs”**.
  - This models the **discovery of new URLs**: the downloader (or associated parsing logic) extracts links from downloaded pages and enqueues them as crawl candidates.

- **Queue → Scheduler**
  - The **Queue** has an arrow pointing into the **Scheduler**.
  - This indicates the Scheduler **consumes** URLs from the Queue: it pulls candidates and decides which to schedule next (possibly reordering, rate limiting, or filtering).

- **External input → Scheduler**
  - On the left edge, there is an unlabeled arrow into the **Scheduler** (bypassing the Queue).
  - This represents **initial seeds or external injection of URLs/config**: bootstrap URLs, manual additions, or other producers feeding URLs directly to the Scheduler.

- **Scheduler ↔ Queue loop**
  - Together, `Queue → Scheduler → Multi-threaded downloader → Queue` form a **feedback loop**:
    - Downloader discovers new URLs
    - New URLs go into Queue
    - Scheduler selects from Queue and feeds downloader again.

---

### Flow summary as a sequence

1. **Seed / external source** provides initial URLs directly into the **Scheduler**.
2. **Scheduler**:
   - Pulls URLs from the **Queue** (once it has items).
   - Applies scheduling policies and sends selected **URLs** to the **Multi-threaded downloader**.
3. **Multi-threaded downloader**:
   - Concurrently fetches those URLs from the **World Wide Web**.
   - Receives **Web pages** in response.
4. **Downloader → Storage**:
   - Persists fetched **Data** into **Storage**.
5. **Downloader → Queue**:
   - Extracts new URLs from content, pushes them as **URLs** into the **Queue**.
6. **Queue → Scheduler**:
   - Stores and feeds these URLs back to the Scheduler, closing the loop.

From an architecture perspective, you have:

- A **producer/consumer system** around URLs (Downloader produces URLs; Scheduler consumes, then produces more download work).
- A **separate data path** (Downloader → Storage) for persisted content.
- Clear separation of concerns:
  - **Scheduler**: crawl policy & ordering.
  - **Queue**: buffering and backpressure for URL candidates.
  - **Multi-threaded downloader**: high-throughput I/O and parsing.
  - **Storage**: durable persistence for crawled data.

