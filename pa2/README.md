# Programming Assignment 2 - Extraction and vector retrieval

This README is focused on two tasks:

1. setting up and running the PA2 extraction/retrieval pipeline locally,
2. restoring an exported PostgreSQL database dump in pgAdmin.

## Folder structure

```text
pa2/
|- README.md
|- FINDINGS-TO-REPORT.md
|- db/migrations/
|  |- 001_page_cleaned_content.sql
|  |- 002_cleaned_content_canonicalization.sql
|  |- 003_page_segment.sql
|  |- 004_page_segment_embedding.sql
|  |- 005_page_segment_merge_group_parent.sql
|  |- 006_page_segment_embedding_text.sql
|  \- 007_page_segment_embedding_labse.sql
|- implementation-extraction/
|  |- requirements.txt
|  |- extract_readme.py
|  |- build_canonical_content_map.py
|  |- segment_cleaned_content.py
|  |- embed_page_segments.py
|  \- demo.py
\- extraction-db/
   \- db.txt
```

## 1) Setup and run the code (query-ready path)

### Prerequisites

- Python 3.10+ (`python3 --version`)
- Docker running (`docker ps`)
- A restored PA2 export dump from `pa2/extraction-db/db.txt` (the dump already contains extracted text, segments, and embeddings)

### Database defaults used by the scripts

- host: `localhost`
- port: `5432`
- database: `crawldb`
- user: `user`
- password: `SecretPassword`
- schema: `crawldb`

You can override these with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`.

### Step A - create virtual environment

```bash
cd pa2/implementation-extraction
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Step B - ensure PostgreSQL container is running

If you already have `postgresql-wier`, start it:

```bash
docker start postgresql-wier
```

If you need full DB setup/reset, follow `DATABASE_SETUP_CLEANUP.md`.

### Step C - restore the exported database dump

Follow Section **2) Restore exported database in pgAdmin (detailed)** below.

The exported DB already includes:

- `crawldb.page.cleaned_content`
- `crawldb.page_segment`
- `crawldb.page_segment.embedding`
- `crawldb.page_segment.embedding_labse`

No extraction, segmentation, or embedding generation step is required for normal usage.

### Step D - run retrieval demo immediately (in the pa2/implementation-extraction/ directory)

```bash
# Single query example
python demo.py --query "What are some of the repositories for food image classification?" --top-k 5 --candidate-k 20 --rerank --metric cosine
python demo.py --embedding labse --strategy heading_structure_v4 --query "How do I run Segment Anything (SAM) inference or download SAM checkpoints from this repository?" --top-k 5

# Built-in qualitative demo: runs 3 expected-good and 3 intentionally weak queries
python demo.py --run-eval --top-k 5
python demo.py --run-eval --top-k 5 --rerank --candidate-k 20
```

### Quick smoke checks

Run from repository root:

```bash
docker exec -i postgresql-wier psql -U user -d crawldb -c "SELECT COUNT(*) FROM crawldb.page WHERE cleaned_content IS NOT NULL;"
docker exec -i postgresql-wier psql -U user -d crawldb -c "SELECT strategy, COUNT(*) FROM crawldb.page_segment GROUP BY strategy ORDER BY strategy;"
docker exec -i postgresql-wier psql -U user -d crawldb -c "SELECT COUNT(*) FILTER (WHERE embedding IS NOT NULL) AS minilm, COUNT(*) FILTER (WHERE embedding_labse IS NOT NULL) AS labse FROM crawldb.page_segment;"
```

If these counts are non-zero, you can query immediately with `demo.py`.

## 2) Restore exported database in pgAdmin (detailed)

At the moment, `pa2/extraction-db/db.txt` contains a download link for the exported DB. Download the dump file locally first.

### 2.1 Download and identify dump format

After downloading, check the file extension:

- `.backup`, `.dump`, `.tar` -> use pgAdmin **Restore**
- `.sql` -> use pgAdmin **Query Tool** (execute script)

If unknown, right-click file -> properties and check exact name.

### 2.2 Start PostgreSQL and open pgAdmin

1. Ensure container is running:
  ```bash
   docker start postgresql-wier
  ```
2. Open pgAdmin.
3. If server is not registered, create one:
  - **Name**: `postgresql-wier`
  - **Host name/address**: `localhost`
  - **Port**: `5432`
  - **Maintenance DB**: `crawldb` (or `postgres`)
  - **Username**: `user`
  - **Password**: `SecretPassword`
  - Save password if you want.

### 2.3 Prepare target database

For clean restores, use an empty target DB.

Option A (recommended): create a fresh database in pgAdmin named `crawldb_restore`:

1. Right-click **Databases** -> **Create** -> **Database...**
2. Database name: `crawldb_restore`
3. Owner: `user`
4. Save.

Option B: restore into existing `crawldb` only if you are sure you can overwrite it.

### 2.4 Restore `.backup` / `.dump` / `.tar` with pgAdmin

1. Right-click target database (`crawldb_restore`) -> **Restore...**
2. In **General** tab:
  - **Format**: choose the matching format (`Custom` for `.backup/.dump`, `Tar` for `.tar`)
  - **Filename**: select your downloaded dump file
  - **Role name**: `user` (if present)
3. In **Restore options** / **Data options**:
  - enable **Pre-data**, **Data**, **Post-data**
  - enable **Clean before restore** if restoring over non-empty DB
  - keep **Single transaction** off unless you specifically need all-or-nothing behavior
4. Click **Restore**.
5. Wait for completion and open the process output panel.

If you get ownership/privilege errors, retry with:

- target DB owned by `user`,
- restore role set to `user`,
- `Clean before restore` enabled.

### 2.5 Import `.sql` dump with pgAdmin

If the export is plain SQL:

1. Open target database.
2. Open **Query Tool**.
3. Load the `.sql` file.
4. Execute all statements.
5. Confirm there are no SQL errors in the Messages panel.

### 2.6 Verify restore succeeded

Run in pgAdmin Query Tool on the restored database:

```sql
SELECT COUNT(*) AS page_count FROM crawldb.page;
SELECT COUNT(*) AS segment_count FROM crawldb.page_segment;
SELECT COUNT(*) AS minilm_embeddings
FROM crawldb.page_segment
WHERE embedding IS NOT NULL;
SELECT COUNT(*) AS labse_embeddings
FROM crawldb.page_segment
WHERE embedding_labse IS NOT NULL;
```

Also verify the schema/table tree in pgAdmin:

- schema `crawldb` exists,
- tables `page`, `page_segment`, and related PA1 tables are present.

## Useful references

- `DATABASE_SETUP_CLEANUP.md` - full Docker/PostgreSQL setup and reset guide
- `HOW_TO_TEST.md` - test execution instructions
- `PROGRAMMING-ASSIGNMENT-2.md` - assignment notes and rubric
- `REPORT.md` - report checklist and extraction writeup

## Optional: recompute pipeline from scratch

Only use this section if you intentionally want to rebuild PA2 artifacts yourself.

From repository root (migrations once per fresh DB):

```bash
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/001_page_cleaned_content.sql
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/003_page_segment.sql
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/005_page_segment_merge_group_parent.sql
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/006_page_segment_embedding_text.sql
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/004_page_segment_embedding.sql
docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/007_page_segment_embedding_labse.sql
```

Then from `pa2/implementation-extraction` with virtualenv activated:

```bash
python extract_readme.py --batch-size 50
python build_canonical_content_map.py
python segment_cleaned_content.py --strategy heading_structure_v4 --batch-size 200
python embed_page_segments.py --strategy heading_structure_v4 --batch-size 64
python embed_page_segments.py --embedding labse --strategy heading_structure_v4 --batch-size 32
```

