# IEPS-TOLPA Crawler (PA1)

This project is a **preferential web crawler** aimed mainly at the `github.com` domain, with scoring biased toward **image segmentation**–related content. It respects `robots.txt`, rate limits per site, and stores the URL frontier and page metadata in **PostgreSQL**. A **domain frontier pump** (virtual thread) schedules per-domain work; up to **`crawler.nCrawlers`** concurrent **pipeline** tasks fetch pages (HTTP with optional headless fallback), parse links, and enqueue new URLs by relevance.

Configuration is driven by `[crawler/src/main/resources/application.properties](crawler/src/main/resources/application.properties)`, optional environment variables (`CRAWLER_*`, see `CrawlerEnvironmentNames`), and the CLI flag `--n-crawlers`.

---

## Prerequisites

Install and verify the following **before** the first run.

1. **JDK 21**
  The Gradle build uses the Java 21 toolchain. On macOS you can point tools at JDK 21 with:
   `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`  
   Newer JDKs may break the build; stay on 21 unless you know the project supports a newer release.
2. **Docker**
  Used to run PostgreSQL locally as documented in `[db/database-setup.md](db/database-setup.md)`. Docker Desktop or Colima (macOS) is fine; `docker ps` should work from your shell.
3. **Chrome or Chromium**
  Required for **Selenium headless** rendering when the plain HTTP fetch path is not enough. Install a normal desktop build; the crawler does not bundle a browser.
4. **Network access**
  The crawler contacts live sites (e.g. GitHub). Firewalls or proxies may require extra configuration not covered here.
5. **Repository layout**
  All Gradle commands below assume your current directory is `**pa1/`** (the folder that contains `gradlew` and `build.gradle.kts`). Paths like `crawler/src/...` are relative to `**pa1/**`.

You do **not** need a global Gradle installation; the wrapper `./gradlew` is enough.

---

## End-to-end: run the crawler

Follow these steps in order the first time (and after a full database wipe).

### Step 1 — Start PostgreSQL and apply the schema

Complete **Steps 1–2** in `[db/database-setup.md](db/database-setup.md)`: create `.docker/pgdata` and `.docker/init-scripts`, copy `pa1/db/crawldb.sql` into the init folder, then start the `postgresql-wier` container.

The guide’s defaults are - used for pgAdmin program, to monitor the data inside the database:


| Setting                  | Value                                                                        |
| ------------------------ | ---------------------------------------------------------------------------- |
| Host (from your machine) | `localhost`                                                                  |
| Port                     | `5432` (or the host port you mapped, e.g. `5433` if you used `-p 5433:5432`) |
| Database                 | `crawldb`                                                                    |
| User                     | `user`                                                                       |
| Password                 | `SecretPassword`                                                             |


After the first successful init, `crawldb.schema_version` must report version `**7`**, matching `crawler.db.expectedSchemaVersion` in `application.properties`.

For **verification queries**, **troubleshooting**, and **resetting crawl data** without recreating Docker, keep using `[db/database-setup.md](db/database-setup.md)`.

### Step 2 — Align JDBC settings in `application.properties`

Edit `[crawler/src/main/resources/application.properties](crawler/src/main/resources/application.properties)` so the database block matches your container:

- `crawler.db.url` — typically `jdbc:postgresql://localhost:5432/crawldb` (adjust the port if you changed Docker’s publish mapping).
- `crawler.db.user` / `crawler.db.password` — must match `POSTGRES_USER` / `POSTGRES_PASSWORD` from Docker (defaults above).
- `crawler.db.expectedSchemaVersion` — must equal the value in `crawldb.schema_version` ( `**7`** for the current `crawldb.sql`).

Also set, according to your crawl plan:

- `crawler.budget.maxTotalPages` — global cap on distinct pages stored in this run (empty uses an internal default; set explicitly for predictable tests).
- `crawler.seedUrls` — comma-separated HTTPS seed URLs. Seeds are inserted **only when `crawldb.page` has zero rows** at startup (see seed bootstrap in the code). If you already have pages and want a fresh seed pass, truncate crawl tables per `[db/database-setup.md](db/database-setup.md)` (“Reset crawl data”) or use a new empty data directory.

Optional: any `crawler.*` property can be overridden via environment variables using the mapping in `si.uni_lj.fri.wier.config.CrawlerEnvironmentNames` (e.g. `CRAWLER_DB_URL`, `CRAWLER_SEEDURLS`).

### Step 3 — Provide `pa1/keywords.json` for `./gradlew run`

`crawler.scoring.keywordConfig` is a **filesystem path** resolved relative to the **JVM working directory**. For `./gradlew run`, that directory is `**pa1/`**, so the default `keywords.json` means `**pa1/keywords.json**`.

1. Copy the template into `**pa1/**`:
  - If your shell is already in `**pa1/**`:  
   `cp crawler/src/main/resources/keywords.json keywords.json`
  - If your shell is in the **repository root** (`IEPS-TOLPA-crawler/`):  
  `cp pa1/crawler/src/main/resources/keywords.json pa1/keywords.json`
2. Edit `**pa1/keywords.json`**: keep the JSON shape `{"primary":[...],"secondary":[...]}`. Use **discriminative singletons** (or short phrases) and avoid **overlapping entries** where one keyword is a substring of another in the same tier — otherwise the same anchor text can increment the score multiple times for one conceptual hit. Matching is **case-insensitive substring** over the canonical URL plus anchor text plus surrounding context (so `semantic` matches `semantic-segmentation`, `semanticsegmentation`, etc.).
3. **Scoring weights and seeds (TS-13):** `crawler.scoring.primaryWeight` and `crawler.scoring.secondaryWeight` (defaults `0.18` / `0.09`) add to the relevance score for each matching keyword; the sum is **not capped** above, so very rich snippets rank higher. `crawler.scoring.seedRelevanceScore` (default `1000`) is the fixed score for bootstrap seed URLs; startup requires it to be **strictly greater** than the maximum possible keyword sum for your file (`nPrimary × primaryWeight + nSecondary × secondaryWeight` after trim/lowercase/dedupe), so seeds always stay ahead of keyword-scored discoveries in the frontier.
4. The file under `crawler/src/main/resources/keywords.json` is intended as a **small classpath fixture for tests** (it may include a `_note`); production-like runs should rely on `**pa1/keywords.json`**.

If startup fails with “must refer to an existing file” for the keyword config, the process is not seeing `pa1/keywords.json` — confirm you launched Gradle from `**pa1/**` and the filename matches `crawler.scoring.keywordConfig`. If validation fails on `crawler.scoring.seedRelevanceScore`, raise that property or reduce weights / keyword list size so the seed score stays above the theoretical maximum keyword sum.

### Step 4 — Build

```bash
cd pa1
./gradlew build
```

Fix any compile errors before continuing.

### Step 5 — Run the crawler

```bash
cd pa1
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS example
./gradlew run --args="--n-crawlers 4"
```

Replace `4` with the maximum number of **concurrent pipeline** tasks (global parallelism). Stay consistent with DB pool size and headless limits (see TS-13 / `RuntimeConfig` validation).

Other CLI options:

```bash
./gradlew run --args="--help"
```

### Step 6 — What a successful run looks like

- Logs show schema validation, optional seed bootstrap (`seedBootstrap inserted` / skipped if `page` was non-empty), worker activity, and periodic `**CRAWLER_HEARTBEAT**`-style observability.
- On normal completion the process prints a **run summary** (counts, domains, rate-limit waits, etc.) and exits with code **0**.
- **SIGINT / SIGTERM** should stop the scheduler gracefully.

To inspect data, use **pgAdmin** or `psql` against `crawldb` (see below), run the **live terminal dashboard** in [Live crawl status (terminal)](#live-crawl-status-terminal), or open the **link graph** in [Link graph visualization (browser)](#link-graph-visualization-browser).

---

## Live crawl status (terminal)

[`scripts/db-crawl-status.sh`](scripts/db-crawl-status.sh) polls PostgreSQL and redraws a **colorized** dashboard (budget bar vs `crawler.budget.maxTotalPages`, separate bars for fetched / frontier / processing, totals, and a rough **ETA** from queue depth using **5 seconds per URL** in the queue by default). The screen **clears** before each update; default refresh is **every 5 seconds**. Press **Ctrl+C** to exit.

**You need:** Bash, and either **`psql`** on your `PATH` with credentials matching `application.properties` (`localhost:5432`, database `crawldb`, user `user`, password `SecretPassword`, unless you override `PG*`), **or** Docker with the **`postgresql-wier`** container up—then pass **`--docker`** so the script runs `psql` inside the container.

From the **repository root**:

```bash
chmod +x pa1/scripts/db-crawl-status.sh   # once, if needed
./pa1/scripts/db-crawl-status.sh
```

Without a local client:

```bash
./pa1/scripts/db-crawl-status.sh --docker
```

Use **`--interval SECONDS`** (or `CRAWLER_STATUS_REFRESH_SEC`) to change how often the display refreshes. Other overrides (budget cap for the bar, ETA seconds per queued URL, container name) are documented in:

```bash
./pa1/scripts/db-crawl-status.sh --help
```

---

## Link graph visualization (browser)

The repository includes a small **Node/Express** app under [`../visualization/`](../visualization/) that reads `crawldb.link`, `crawldb.page`, and `crawldb.site` and draws an interactive graph (pages or aggregated domains). Use it to explore how discovered URLs link to each other after a crawl.

**You need:** **Node.js** (current LTS is fine) and **npm**, with shell cwd at the **repository root** (`IEPS-TOLPA-crawler/`) or inside `visualization/`.

1. **Configure the database profile**  
   Copy the template and point the **real** profile at the same PostgreSQL instance the crawler uses (same host, port, database, user, and password as in [`crawler/src/main/resources/application.properties`](crawler/src/main/resources/application.properties) and the [Step 1](#step-1--start-postgresql-and-apply-the-schema) table above):

   ```bash
   cd visualization
   cp .env.example .env
   ```

   Edit `REAL_VIZ_DB_*` in `.env` if your JDBC URL or credentials differ from the defaults.

2. **Install and run the server**

   ```bash
   cd visualization
   npm install
   npm run dev
   ```

   The app loads `.env` automatically. It listens on **port 3001** by default (`VIZ_PORT` in `.env`).

3. **Open the UI**  
   In a browser, go to [http://localhost:3001](http://localhost:3001). The UI defaults to the **real** profile (crawler database) and remembers **mock** vs **real** in the browser. Use **Load** to fetch the graph; tune **maxNodes**, **domains**, and **onlyTypes** if the graph is too large or noisy.

**Do not** run `npm run seed` against a database that holds real crawl data: the seed script **truncates** crawl tables.

For **mock data** (optional, for UI development), start the stack in [`../visualization/docker-compose.yml`](../visualization/docker-compose.yml), run `npm run seed`, then choose profile **mock** in the UI. Full options (TLS, API query parameters) are documented in [`../visualization/README.md`](../visualization/README.md).

---

## pgAdmin: register a server

Use the same parameters as in Step 1:


| Field                | Value                                                                 |
| -------------------- | --------------------------------------------------------------------- |
| Host                 | `localhost`                                                           |
| Port                 | `5432` (or your mapped port)                                          |
| Maintenance database | `crawldb` (or `postgres` if your client insists, then open `crawldb`) |
| Username             | `user`                                                                |
| Password             | `SecretPassword`                                                      |


SSL can stay disabled for local Docker. Crawler tables live under schema `**crawldb`** (e.g. `crawldb.page`, `crawldb.site`).

The schema is created by `**crawldb.sql**` via Docker init (or manual import per `database-setup.md`), not by restoring an ad-hoc binary dump from `pa1/db`.

---

## Resetting data for another test run

To clear all crawl rows but keep the schema and lookup tables, follow **“Reset crawl data (retain schema)”** in `[db/database-setup.md](db/database-setup.md)` (SQL `TRUNCATE` commands for pgAdmin or `docker exec`).

---

## Troubleshooting (quick)

- `**Schema version mismatch`** — Database `crawldb.schema_version` does not match `crawler.db.expectedSchemaVersion`. Apply the current `crawldb.sql` / migrations, or align the property with the real DB version.
- `**seedBootstrap skipped**` — `crawldb.page` already had rows. Truncate crawl tables (see database guide) or use a fresh PostgreSQL data directory.
- **Keyword file missing** — Ensure `pa1/keywords.json` exists and you run `./gradlew` from `**pa1/`**, or set an absolute path / env override for `crawler.scoring.keywordConfig`.
- **Cannot connect to PostgreSQL** — Container stopped, wrong port, or credentials differ from `application.properties`.

---

## Automated tests (developers)

For unit and integration tests (Testcontainers, Docker socket on macOS/Colima, JDK 21), see `**HOW_TO_TEST.md`** at the **repository root** (`IEPS-TOLPA-crawler/HOW_TO_TEST.md`), not under `pa1/`.

---

## Further reading

- Domain and seeds: `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/04-domain-and-scope-definition.md`
- Database setup and reset SQL: `[db/database-setup.md](db/database-setup.md)`
- Schema source: `[db/crawldb.sql](db/crawldb.sql)`
- Link graph UI (install, env, API): [`../visualization/README.md`](../visualization/README.md)

