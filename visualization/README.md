## Visualization (Link Graph)

This folder contains a small visualization tool that reads crawler data from PostgreSQL (`crawldb` schema) and renders a graph in the browser.

### Components

- **Postgres (Docker)**: same schema as the crawler (`pa1/db/crawldb.sql`)
- **Seed script (Node)**: generates mock `site/page/link` data (including `DUPLICATE`, `FRONTIER`, `ERROR`, `BINARY`)
- **API (Node/Express)**: reads from Postgres and returns graph JSON
- **Web UI (served by API)**: `vis-network` graph with filters

## Install

From repository root:

```bash
cd visualization
npm install
```

Optional: create your local env file from the template:

```bash
cd visualization
cp .env.example .env
```

## Run With Mock Data

Use this mode while developing/debugging the visualization.

1) Start a local Postgres container (same crawler schema):

```bash
docker compose -f visualization/docker-compose.yml up -d
```

2) Seed mock data:

```bash
cd visualization
set -a; source .env 2>/dev/null; set +a
npm run seed
```

3) Start the API + UI server:

```bash
cd visualization
set -a; source .env 2>/dev/null; set +a
npm run dev
```

4) Open:

- [http://localhost:3001](http://localhost:3001)

## Run With Real Crawler Data

Use this mode after/while the crawler is writing real records to PostgreSQL.

1) Make sure crawler tables are populated in the target database (`crawldb.site`, `crawldb.page`, `crawldb.link`).
2) Start the visualization server and provide both profiles (mock + real) via env vars:

```bash
cd visualization
MOCK_VIZ_DB_HOST=127.0.0.1 \
MOCK_VIZ_DB_PORT=5433 \
MOCK_VIZ_DB_USER=user \
MOCK_VIZ_DB_PASSWORD=SecretPassword \
MOCK_VIZ_DB_NAME=crawldb \
REAL_VIZ_DB_HOST=127.0.0.1 \
REAL_VIZ_DB_PORT=5432 \
REAL_VIZ_DB_USER=user \
REAL_VIZ_DB_PASSWORD=SecretPassword \
REAL_VIZ_DB_NAME=crawldb \
npm run dev
```

3) Open:

- [http://localhost:3001](http://localhost:3001)

Important: **Do not run `npm run seed`** when you want to keep real crawler data unchanged.

## How To Switch Between Mock And Real

- **Mock mode**:
  - run `npm run seed` (overwrites page/site/link-related data used by the visualizer)
  - use default DB config (or point to a dedicated mock DB)
- **Real mode**:
  - skip `npm run seed`
  - in UI select profile `real` (API reads `REAL_VIZ_DB_*`)

Recommended setup: keep two separate databases (or ports/containers), one for mock and one for real data, and switch profile in UI (`mock` / `real`).

## Environment Variables

Used by API and seed script:

- `VIZ_DB_HOST` (default: `127.0.0.1`)
- `VIZ_DB_PORT` (default: `5432`)
- `VIZ_DB_USER` (default: `user`)
- `VIZ_DB_PASSWORD` (default: `SecretPassword`)
- `VIZ_DB_NAME` (default: `crawldb`)

Profile-specific (used by API only, for UI switching):

- `MOCK_VIZ_DB_HOST`, `MOCK_VIZ_DB_PORT`, `MOCK_VIZ_DB_USER`, `MOCK_VIZ_DB_PASSWORD`, `MOCK_VIZ_DB_NAME`
- `REAL_VIZ_DB_HOST`, `REAL_VIZ_DB_PORT`, `REAL_VIZ_DB_USER`, `REAL_VIZ_DB_PASSWORD`, `REAL_VIZ_DB_NAME`

Optional seed controls:

- `VIZ_DOMAINS` (default: `example.com,foo.si,bar.gov.si`)
- `VIZ_HTML_PER_DOMAIN` (default: `80`)
- `VIZ_DUP_PER_DOMAIN` (default: `15`)
- `VIZ_FRONTIER_PER_DOMAIN` (default: `10`)
- `VIZ_ERROR_PER_DOMAIN` (default: `5`)
- `VIZ_BINARY_PER_DOMAIN` (default: `5`)
- `VIZ_CROSS_DOMAIN_LINKS` (default: `60`)
- `VIZ_INTRA_LINKS_PER_PAGE` (default: `3`)

## API

- `GET /` - serves the visualization UI
- `GET /health` - DB health check
- `GET /graph` - graph payload (`nodes`, `edges`)
  - `profile` (`mock|real`, default `mock`)
  - `maxNodes` (default `500`, max `5000`)
  - `domain` (repeatable), e.g. `?domain=example.com&domain=gov.si`
  - `onlyTypes` (CSV), e.g. `HTML,DUPLICATE,ERROR`
  - `groupByDomain` (`true|false`) for high-level domain graph

