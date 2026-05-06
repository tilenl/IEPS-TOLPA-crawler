#!/usr/bin/env python3
"""
Build canonical cleaned-content mapping for downstream segmentation/embedding.

This script materializes two tables introduced by
``pa2/db/migrations/002_cleaned_content_canonicalization.sql``:

- ``crawldb.cleaned_content_canonical``: one row per unique cleaned_content.
- ``crawldb.page_cleaned_content_map``: mapping from each page to canonical row.

Segmentation pipelines can read from the canonical table to avoid duplicate
embeddings while still resolving every source URL through the mapping table.
"""

from __future__ import annotations

import argparse
import logging
import os
from typing import Any


def _resolve_conn_kwargs() -> dict[str, Any]:
    """Resolve PostgreSQL connection kwargs from env with PA defaults."""
    return {
        "host": os.environ.get("PGHOST", "localhost"),
        "port": int(os.environ.get("PGPORT", "5432")),
        "dbname": os.environ.get("PGDATABASE", "crawldb"),
        "user": os.environ.get("PGUSER", "user"),
        "password": os.environ.get("PGPASSWORD", "SecretPassword"),
    }


def rebuild_canonical_mapping(*, dry_run: bool = False) -> tuple[int, int]:
    """
    Rebuild canonical mapping from ``crawldb.page.cleaned_content``.

    Returns:
        Tuple ``(canonical_rows, mapped_pages)``.
    """
    import psycopg

    conn_kwargs = _resolve_conn_kwargs()
    with psycopg.connect(**conn_kwargs, autocommit=False) as conn:
        conn.execute("SET search_path TO crawldb, public")

        candidate_count = conn.execute(
            """
SELECT COUNT(*)
FROM crawldb.page
WHERE cleaned_content IS NOT NULL
  AND LENGTH(TRIM(cleaned_content)) > 0
"""
        ).fetchone()[0]

        if dry_run:
            canonical_count = conn.execute(
                """
WITH grouped AS (
  SELECT md5(cleaned_content) AS fp
  FROM crawldb.page
  WHERE cleaned_content IS NOT NULL
    AND LENGTH(TRIM(cleaned_content)) > 0
  GROUP BY md5(cleaned_content)
)
SELECT COUNT(*) FROM grouped
"""
            ).fetchone()[0]
            conn.rollback()
            return canonical_count, candidate_count

        # Rebuild in one transaction so canonical table and mapping stay aligned.
        conn.execute(
            "TRUNCATE crawldb.page_cleaned_content_map, crawldb.cleaned_content_canonical"
        )

        conn.execute(
            """
WITH ranked AS (
  SELECT
    id AS page_id,
    cleaned_content,
    md5(cleaned_content) AS content_fingerprint,
    ROW_NUMBER() OVER (PARTITION BY md5(cleaned_content) ORDER BY id) AS rn,
    COUNT(*) OVER (PARTITION BY md5(cleaned_content)) AS dup_count
  FROM crawldb.page
  WHERE cleaned_content IS NOT NULL
    AND LENGTH(TRIM(cleaned_content)) > 0
)
INSERT INTO crawldb.cleaned_content_canonical (
  content_fingerprint,
  canonical_page_id,
  cleaned_content,
  content_length,
  duplicate_count,
  updated_at
)
SELECT
  content_fingerprint,
  page_id,
  cleaned_content,
  LENGTH(cleaned_content),
  dup_count,
  NOW()
FROM ranked
WHERE rn = 1
"""
        )

        conn.execute(
            """
WITH canonical AS (
  SELECT content_fingerprint, canonical_page_id
  FROM crawldb.cleaned_content_canonical
),
mapped AS (
  SELECT
    p.id AS page_id,
    md5(p.cleaned_content) AS content_fingerprint,
    c.canonical_page_id
  FROM crawldb.page p
  JOIN canonical c
    ON c.content_fingerprint = md5(p.cleaned_content)
  WHERE p.cleaned_content IS NOT NULL
    AND LENGTH(TRIM(p.cleaned_content)) > 0
)
INSERT INTO crawldb.page_cleaned_content_map (
  page_id,
  content_fingerprint,
  is_canonical,
  mapped_at
)
SELECT
  page_id,
  content_fingerprint,
  page_id = canonical_page_id AS is_canonical,
  NOW()
FROM mapped
"""
        )

        canonical_rows = conn.execute(
            "SELECT COUNT(*) FROM crawldb.cleaned_content_canonical"
        ).fetchone()[0]
        mapped_rows = conn.execute(
            "SELECT COUNT(*) FROM crawldb.page_cleaned_content_map"
        ).fetchone()[0]
        conn.commit()
        return canonical_rows, mapped_rows


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build canonical cleaned-content mapping for segmentation ingestion."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Compute counts only; do not modify canonicalization tables.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable DEBUG logging.",
    )
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    canonical_rows, mapped_pages = rebuild_canonical_mapping(dry_run=args.dry_run)
    logging.info(
        "canonical mapping done: canonical_rows=%s mapped_pages=%s dry_run=%s",
        canonical_rows,
        mapped_pages,
        args.dry_run,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
