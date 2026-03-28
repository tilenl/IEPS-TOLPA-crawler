# PA1 Database Setup Guide

This guide contains the full staged setup process for the local PostgreSQL database used by the crawler.

All commands assume you are running from the repository root (`IEPS-TOLPA-crawler`).

## Prerequisites

1. Docker is installed.
2. Docker daemon is running.
   - Quick check: `docker ps`
   - On macOS (if Docker daemon is not active): `colima start`, then run `docker ps` again.
3. You execute commands from the repository root so relative paths resolve correctly.

## Step 1 - Prepare local folders for persistence and init scripts

Create one folder for PostgreSQL data (so database state survives container restarts) and one folder for initialization SQL scripts.

```bash
mkdir -p .docker/pgdata .docker/init-scripts
cp pa1/db/crawldb.sql .docker/init-scripts/01-crawldb.sql
```

What this does:
- `.docker/pgdata` stores PostgreSQL cluster files persistently.
- `.docker/init-scripts/01-crawldb.sql` is automatically executed by the official PostgreSQL image on first database initialization.

## Step 2 - Start PostgreSQL container (Docker)

```bash
docker run --name postgresql-wier \
    -e POSTGRES_PASSWORD=SecretPassword \
    -e POSTGRES_USER=user \
    -e POSTGRES_DB=crawldb \
    -v "$PWD/.docker/pgdata:/var/lib/postgresql/data" \
    -v "$PWD/.docker/init-scripts:/docker-entrypoint-initdb.d" \
    -p 5432:5432 \
    -d pgvector/pgvector:pg17
```

Flag-by-flag explanation:
- `--name postgresql-wier`: stable container name for later `docker logs`, `docker exec`, `docker stop`.
- `-e POSTGRES_PASSWORD=SecretPassword`: password for the DB user.
- `-e POSTGRES_USER=user`: creates database user `user`.
- `-e POSTGRES_DB=crawldb`: creates the target DB named `crawldb`.
- `-v "$PWD/.docker/pgdata:/var/lib/postgresql/data"`: persist DB files on host.
- `-v "$PWD/.docker/init-scripts:/docker-entrypoint-initdb.d"`: mounts SQL init scripts.
- `-p 5432:5432`: exposes PostgreSQL on `localhost:5432`.
- `-d pgvector/pgvector:pg17`: runs in detached mode using the tutorial image family.

Initialization scripts in `/docker-entrypoint-initdb.d` run only when the data directory is empty (first container initialization).

## Step 3 - Monitor startup and schema initialization

```bash
docker logs -f postgresql-wier
```

Expected log behavior:
- PostgreSQL starts cleanly.
- The init script `01-crawldb.sql` is executed.
- No SQL errors are printed.

## Step 4 - Alternative initialization path (manual import)

If the container already exists and auto-init did not run (because `pgdata` was already initialized), import schema manually:

```bash
docker exec -i postgresql-wier psql -U user -d crawldb < pa1/db/crawldb.sql
```

Use this manual path when:
- You reused an old data directory.
- You intentionally want to reapply schema in an existing database.

## Expected End State

After successful setup, all of the following should be true:
- `postgresql-wier` container is running (`docker ps` shows it).
- Database `crawldb` exists and is reachable on `localhost:5432`.
- Schema `crawldb` exists in the database.
- Core tables exist: `site`, `page`, `link`, `image`, `page_data`, `page_type`, `data_type`.
- Seed values from the SQL script are present in `crawldb.page_type` and `crawldb.data_type`.
- Extension column `crawldb.page.relevance_score` exists.
- Index `idx_page_frontier_priority` exists for preferential frontier dequeue.

## Reset crawl data (retain schema)

Use this when you want to **wipe all crawler state** (pages, sites, links, images, dedup metadata) but **keep** the database, schema, and lookup rows (`page_type`, `data_type`, `schema_version`). Afterward `crawldb.page` is empty, so the next crawler start will run **seed bootstrap** again (`PreferentialCrawler.bootstrapSeedsIfEmpty`).

**Do not** truncate `crawldb.page_type`, `crawldb.data_type`, or `crawldb.schema_version`.

### pgAdmin (or any SQL client)

Connect to database `crawldb`, open **Query Tool**, and run:

```sql
TRUNCATE TABLE
  crawldb.content_owner,
  crawldb.link,
  crawldb.image,
  crawldb.page_data,
  crawldb.page,
  crawldb.site
RESTART IDENTITY;
```

`RESTART IDENTITY` resets serial sequences on those tables so new `id` values start from 1.

If PostgreSQL reports a foreign-key error, use the same table list with `CASCADE`:

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

### Docker CLI (equivalent)

From the repository root:

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "
TRUNCATE TABLE
  crawldb.content_owner,
  crawldb.link,
  crawldb.image,
  crawldb.page_data,
  crawldb.page,
  crawldb.site
RESTART IDENTITY CASCADE;
"
```

For a **full** reset (new empty cluster and re-run `crawldb.sql` init), remove the container and delete `.docker/pgdata`, then repeat Steps 1–2 instead of truncating.

## Troubleshooting (Common Issues)

- **Docker daemon not running**
  - Symptom: `Cannot connect to the Docker daemon`.
  - Fix: start Docker Desktop or run `colima start` (macOS), then retry `docker ps`.

- **Port `5432` already occupied**
  - Symptom: bind error on container start.
  - Fix: publish another host port, for example `-p 5433:5432`, and update crawler DB connection config accordingly.

- **Init script did not execute**
  - Symptom: container starts but tables are missing.
  - Cause: mounted data directory already contained an initialized PostgreSQL cluster.
  - Fix: use manual import command (`docker exec ... < pa1/db/crawldb.sql`) or start with a fresh empty `.docker/pgdata`.

- **Wrong SQL file path**
  - Symptom: `No such file or directory` when running import.
  - Fix: run command from repository root or provide full absolute path to `pa1/db/crawldb.sql`.

## How to Verify the Database Is Correctly Set Up

Run these checks in order.

### 1) Connectivity check

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT 1;"
```

Expected result: one row with value `1`.

### 2) Schema existence check

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'crawldb';"
```

Expected result: exactly one row (`crawldb`).

### 3) Table existence check

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "\\dt crawldb.*"
```

Expected result includes at least:
- `crawldb.site`
- `crawldb.page`
- `crawldb.link`
- `crawldb.image`
- `crawldb.page_data`
- `crawldb.page_type`
- `crawldb.data_type`

### 4) Index and priority-column check

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT indexname FROM pg_indexes WHERE schemaname='crawldb' AND indexname='idx_page_frontier_priority';"
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT column_name FROM information_schema.columns WHERE table_schema='crawldb' AND table_name='page' AND column_name='relevance_score';"
```

Expected result: both queries return one row.

### 5) Seed lookup data check

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT code FROM crawldb.page_type ORDER BY code;"
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT code FROM crawldb.data_type ORDER BY code;"
```

Expected result:
- `page_type`: `BINARY`, `DUPLICATE`, `FRONTIER`, `HTML`
- `data_type`: `DOC`, `DOCX`, `PDF`, `PPT`, `PPTX`

### 6) Write/read smoke test (plus cleanup)

Insert minimal test records, read them back, then remove them:

```bash
docker exec postgresql-wier psql -U user -d crawldb -c "INSERT INTO crawldb.site(domain) VALUES ('example.test') RETURNING id;"
docker exec postgresql-wier psql -U user -d crawldb -c "INSERT INTO crawldb.page(site_id, page_type_code, url, relevance_score) SELECT id, 'FRONTIER', 'https://example.test/health', 0.5 FROM crawldb.site WHERE domain='example.test';"
docker exec postgresql-wier psql -U user -d crawldb -c "SELECT p.url, p.page_type_code, p.relevance_score FROM crawldb.page p JOIN crawldb.site s ON p.site_id=s.id WHERE s.domain='example.test';"
docker exec postgresql-wier psql -U user -d crawldb -c "DELETE FROM crawldb.page WHERE url='https://example.test/health'; DELETE FROM crawldb.site WHERE domain='example.test';"
```

Expected result:
- Insert succeeds without constraint errors.
- Read query returns one `FRONTIER` row with `relevance_score=0.5`.
- Cleanup removes the temporary test records.
