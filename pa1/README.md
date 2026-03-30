# IEPS-TOLPA Crawler (PA1)

A **preferential web crawler** targeting `github.com`, biased toward **image segmentation**–related content. It respects `robots.txt`, enforces per-domain rate limits (5 s floor), scores discovered URLs by keyword relevance, and stores the URL frontier and page metadata in **PostgreSQL**. A domain frontier pump schedules per-domain work; up to `crawler.nCrawlers` concurrent pipeline tasks fetch pages (plain HTTP with headless Selenium fallback), parse links, and enqueue new URLs ordered by relevance score.

---

## Prerequisites


| Tool                  | Notes                                                                                         |
| --------------------- | --------------------------------------------------------------------------------------------- |
| **JDK 21**            | `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` (macOS). Newer JDKs may break the build. |
| **Docker**            | Run `docker ps` to confirm the daemon is up. macOS: use Docker Desktop or `colima start`.     |
| **Chrome / Chromium** | Required for Selenium headless rendering. Install a normal desktop build.                     |


No global Gradle installation needed — use `./gradlew` (the wrapper).

---

## Setup and run

All `./gradlew` commands must be run from the `**pa1/`** directory.

### 1. Start PostgreSQL

From the **repository root** (`IEPS-TOLPA-crawler/`):

```bash
mkdir -p .docker/pgdata .docker/init-scripts
cp pa1/db/crawldb.sql .docker/init-scripts/01-crawldb.sql

docker run --name postgresql-wier \
    -e POSTGRES_PASSWORD=SecretPassword \
    -e POSTGRES_USER=user \
    -e POSTGRES_DB=crawldb \
    -v "$PWD/.docker/pgdata:/var/lib/postgresql/data" \
    -v "$PWD/.docker/init-scripts:/docker-entrypoint-initdb.d" \
    -p 5432:5432 \
    -d pgvector/pgvector:pg17
```

The `crawldb.sql` schema is applied automatically on first container start. Verify the schema version:

```bash
docker exec postgresql-wier psql -U user -d crawldb \
    -c "SELECT version FROM crawldb.schema_version WHERE id = 1;"
# expected: version = 7
```

> **Restarting an existing container:** `docker start postgresql-wier`  
> **Full reset** (wipe data and re-apply schema): stop and remove the container, delete `.docker/pgdata`, then repeat the commands above.

### 2. Configure the crawler

Edit `crawler/src/main/resources/application.properties` (paths relative to `pa1/`):


| Property                           | Default                                    | Notes                                                                    |
| ---------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------ |
| `crawler.db.url`                   | `jdbc:postgresql://localhost:5432/crawldb` | Adjust port if you changed Docker's `-p` mapping.                        |
| `crawler.db.user`                  | `user`                                     | Must match `POSTGRES_USER`.                                              |
| `crawler.db.password`              | `SecretPassword`                           | Must match `POSTGRES_PASSWORD`.                                          |
| `crawler.db.expectedSchemaVersion` | `7`                                        | Must equal the value in `crawldb.schema_version`.                        |
| `crawler.budget.maxTotalPages`     | `5000`                                     | Global page cap for the run.                                             |
| `crawler.seedUrls`                 | *(see file)*                               | Comma-separated HTTPS seeds. Only inserted when `crawldb.page` is empty. |


Any property can also be overridden via environment variables (e.g. `CRAWLER_DB_URL`).

### 3. Prepare `keywords.json`

The relevance scorer reads a keyword file from the JVM working directory (`pa1/` when using `./gradlew run`):

```bash
# run from pa1/
cp crawler/src/main/resources/keywords.json keywords.json
```

Edit `pa1/keywords.json` to keep or adjust the `{"primary":[...],"secondary":[...]}` lists. Matching is case-insensitive substring over URL + anchor text + surrounding context.

### 4. Build

```bash
cd pa1
./gradlew build
```

### 5. Run

```bash
cd pa1
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS
./gradlew run --args="--n-crawlers 4"
```

Replace `4` with the desired number of concurrent pipeline tasks. Use `--args="--help"` to see all CLI options.

A successful run logs schema validation, optional seed bootstrap, periodic heartbeats, and exits with code **0** after printing a run summary. Press **Ctrl+C** for a graceful stop.

---

## Import the database schema in pgAdmin

### Register the server

Open pgAdmin and add a new server (**Object → Register → Server**):


| Field                    | Value                        |
| ------------------------ | ---------------------------- |
| **Host**                 | `localhost`                  |
| **Port**                 | `5432` (or your mapped port) |
| **Maintenance database** | `crawldb`                    |
| **Username**             | `user`                       |
| **Password**             | `SecretPassword`             |


SSL can stay disabled for local Docker.

### Import the schema (if auto-init did not run)

If the tables are missing (e.g. the container was started against an already-initialized data directory), import the schema manually:

**Option A — pgAdmin Query Tool**

1. Connect to the `crawldb` database.
2. Open **Tools → Query Tool**.
3. Click the folder icon, open `pa1/db/crawldb.sql`, then press **Execute (F5)**.

**Option B — Docker CLI (from the repository root)**

```bash
docker exec -i postgresql-wier psql -U user -d crawldb < pa1/db/crawldb.sql
```

After import, `crawldb.schema_version` must report **version 7**. Crawler tables live under the `crawldb` schema (e.g. `crawldb.page`, `crawldb.site`, `crawldb.link`).

### Reset crawl data (keep schema)

To wipe all crawl rows and start fresh without recreating the container, run in pgAdmin's **Query Tool**:

```sql
TRUNCATE TABLE
  crawldb.content_owner,
  crawldb.link,
  crawldb.image,
  crawldb.page_data,
  crawldb.page,
  crawldb.site
RESTART IDENTITY CASCADE;
```

---

## Optional tooling


| Tool                    | Location                     | Purpose                                                                         |
| ----------------------- | ---------------------------- | ------------------------------------------------------------------------------- |
| Live terminal dashboard | `scripts/db-crawl-status.sh` | Colorized crawl progress with ETA. Run with `--docker` if no local `psql`.      |
| Link graph UI           | `../visualization/`          | Interactive browser graph of crawled pages and links (Node/Express, port 3001). |


