#!/usr/bin/env bash
# Show which PostgreSQL database has PA2 data (pages, segments, embeddings).
set -euo pipefail

CONTAINER="${POSTGRES_CONTAINER:-postgresql-wier}"
USER="${PGUSER:-user}"

echo "=== Databases in container ${CONTAINER} ==="
docker exec "${CONTAINER}" psql -U "${USER}" -d postgres -t -c \
  "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY 1;"

for DB in crawldb crawldb_restore; do
  echo ""
  echo "=== ${DB} ==="
  docker exec "${CONTAINER}" psql -U "${USER}" -d "${DB}" -t -c \
    "SELECT 'pages' AS what, COUNT(*)::text FROM crawldb.page
     UNION ALL SELECT 'segments', COUNT(*)::text FROM crawldb.page_segment
     UNION ALL SELECT 'embeddings', COUNT(*)::text FROM crawldb.page_segment WHERE embedding IS NOT NULL;" \
    2>/dev/null || echo "  (database ${DB} does not exist or has no crawldb schema)"
done

echo ""
echo "PA2/PA3 scripts default to database name: crawldb (host localhost:5432)."
echo "If only crawldb_restore has data, either:"
echo "  export PGDATABASE=crawldb_restore"
echo "  or run: pa3/scripts/fix_db_name.sh"
