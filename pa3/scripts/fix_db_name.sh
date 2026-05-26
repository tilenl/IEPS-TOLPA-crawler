#!/usr/bin/env bash
# Rename crawldb_restore -> crawldb so PA2/PA3 defaults work.
# Safe when crawldb is empty and crawldb_restore has the restored dump.
set -euo pipefail

CONTAINER="${POSTGRES_CONTAINER:-postgresql-wier}"
USER="${PGUSER:-user}"

echo "Checking counts..."
EMPTY=$(docker exec "${CONTAINER}" psql -U "${USER}" -d crawldb -t -A -c \
  "SELECT COUNT(*) FROM crawldb.page_segment WHERE embedding IS NOT NULL;" 2>/dev/null || echo "missing")
FULL=$(docker exec "${CONTAINER}" psql -U "${USER}" -d crawldb_restore -t -A -c \
  "SELECT COUNT(*) FROM crawldb.page_segment WHERE embedding IS NOT NULL;" 2>/dev/null || echo "0")

echo "  crawldb embeddings:          ${EMPTY}"
echo "  crawldb_restore embeddings: ${FULL}"

if [[ "${FULL}" == "0" || "${FULL}" == "missing" ]]; then
  echo "ERROR: crawldb_restore has no embeddings. Restore the PA2 dump first."
  exit 1
fi

if [[ "${EMPTY}" != "0" && "${EMPTY}" != "missing" ]]; then
  echo "ERROR: crawldb already has embeddings (${EMPTY}). Aborting to avoid data loss."
  echo "Use: export PGDATABASE=crawldb_restore"
  exit 1
fi

read -r -p "Drop empty crawldb and rename crawldb_restore -> crawldb? [y/N] " CONFIRM
if [[ "${CONFIRM}" != "y" && "${CONFIRM}" != "Y" ]]; then
  echo "Cancelled."
  exit 0
fi

docker exec "${CONTAINER}" psql -U "${USER}" -d postgres -c "DROP DATABASE IF EXISTS crawldb;"
docker exec "${CONTAINER}" psql -U "${USER}" -d postgres -c "ALTER DATABASE crawldb_restore RENAME TO crawldb;"

echo "Done. Verify:"
docker exec "${CONTAINER}" psql -U "${USER}" -d crawldb -t -c \
  "SELECT COUNT(*) FROM crawldb.page_segment WHERE embedding IS NOT NULL;"
