#!/usr/bin/env bash
#
# Live terminal dashboard for crawldb.page counts (FRONTIER / PROCESSING / terminal types).
#
# Usage:
#   ./db-crawl-status.sh [--docker] [--interval SECONDS]
#   ./db-crawl-status.sh --help
#
# Environment (optional overrides):
#   PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSWORD — local psql (defaults match crawler application.properties)
#   CRAWLER_BUDGET_MAX_TOTAL_PAGES — bar denominator (else read from pa1/crawler/.../application.properties)
#   CRAWLER_STATUS_REFRESH_SEC — seconds between refreshes (default 5; --interval wins)
#   CRAWLER_STATUS_ETA_SEC_PER_QUEUE_URL — seconds per (frontier+processing) for ETA (default 5)
#   CRAWLER_DB_DOCKER_CONTAINER — container name for --docker (default postgresql-wier)
#
# Stop with Ctrl+C (restores default terminal attributes).
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo root: .../pa1/scripts -> ../..
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly APP_PROPS="${REPO_ROOT}/pa1/crawler/src/main/resources/application.properties"

BAR_WIDTH="${CRAWLER_STATUS_BAR_WIDTH:-40}"
USE_DOCKER=0
DOCKER_CONTAINER="${CRAWLER_DB_DOCKER_CONTAINER:-postgresql-wier}"
REFRESH_SEC="${CRAWLER_STATUS_REFRESH_SEC:-5}"
ETA_SEC_PER_URL="${CRAWLER_STATUS_ETA_SEC_PER_QUEUE_URL:-5}"

# Default DB settings aligned with pa1/crawler application.properties (override via PG* env).
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:=user}"
: "${PGDATABASE:=crawldb}"
: "${PGPASSWORD:=SecretPassword}"

# --- ANSI (stdout is a TTY when interactive; we still emit colors if terminal looks capable) ---
if [[ -t 1 ]]; then
  readonly R=$'\033[0m' B=$'\033[1m'
  readonly C_CYAN=$'\033[36m' C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m'
  readonly C_BLUE=$'\033[34m' C_MAGENTA=$'\033[35m' C_RED=$'\033[31m' C_DIM=$'\033[2m'
else
  readonly R='' B=''
  readonly C_CYAN='' C_GREEN='' C_YELLOW='' C_BLUE='' C_MAGENTA='' C_RED='' C_DIM=''
fi

usage() {
  # Only comment lines in the header block (stop before `set -euo` on line 18).
  sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
}

resolve_max_total_pages() {
  if [[ -n "${CRAWLER_BUDGET_MAX_TOTAL_PAGES:-}" ]]; then
    echo "${CRAWLER_BUDGET_MAX_TOTAL_PAGES}"
    return
  fi
  if [[ -f "$APP_PROPS" ]]; then
    local v
    v="$(grep -E '^[[:space:]]*crawler\.budget\.maxTotalPages=' "$APP_PROPS" | tail -n1 | cut -d= -f2- | tr -d '[:space:]')"
    if [[ -n "$v" && "$v" =~ ^[0-9]+$ ]]; then
      echo "$v"
      return
    fi
  fi
  echo "0"
}

clear_screen_safe() {
  if command -v clear >/dev/null 2>&1; then
    clear
  else
    printf '\033[2J\033[H'
  fi
}

# Filled width: min(bar_width, n * bar_width / denom). If denom is 0, scale by fallback_total when > 0.
compute_fill() {
  local n=$1 denom=$2 width=$3 fallback=${4:-0}
  local f=0
  if (( denom > 0 )); then
    f=$((n * width / denom))
  elif (( fallback > 0 )); then
    f=$((n * width / fallback))
  fi
  if (( f > width )); then f=$width; fi
  if (( f < 0 )); then f=0; fi
  echo "$f"
}

# Budget bar uses a simple green / yellow / red fill by utilization of max.
budget_fill_color() {
  local loaded=$1 max=$2
  if (( max <= 0 )); then
    echo "$C_DIM"
    return
  fi
  local pct=$((loaded * 100 / max))
  if (( pct > 100 )); then pct=100; fi
  if (( pct < 80 )); then
    echo "$C_GREEN"
  elif (( pct < 100 )); then
    echo "$C_YELLOW"
  else
    echo "$C_RED"
  fi
}

# Print one labeled bar: label, count, denominator for scale, fallback denom, fill ANSI color for █ runs.
print_bar_row() {
  local label=$1 count=$2 denom=$3 fallback=$4 fill_color=$5
  local fill empty i
  fill="$(compute_fill "$count" "$denom" "$BAR_WIDTH" "$fallback")"
  empty=$((BAR_WIDTH - fill))
  printf '  %-22s ' "$label"
  printf '%b[' "$R"
  printf '%b' "$fill_color"
  for ((i = 0; i < fill; i++)); do printf '█'; done
  printf '%b' "$R"
  printf '%b' "$C_DIM"
  for ((i = 0; i < empty; i++)); do printf '·'; done
  printf '%b]%b  %s\n' "$R" "$R" "$count"
}

format_duration() {
  local sec=$1
  if ((sec < 0)); then sec=0; fi
  if ((sec < 60)); then
    printf '%ds' "$sec"
    return
  fi
  if ((sec < 3600)); then
    printf '%dm %ds' $((sec / 60)) $((sec % 60))
    return
  fi
  printf '%dh %dm' $((sec / 3600)) $(((sec % 3600) / 60))
}

run_sql() {
  local sql=$1
  if ((USE_DOCKER)); then
    docker exec -i "$DOCKER_CONTAINER" psql -U "$PGUSER" -d "$PGDATABASE" -t -A -F'|' -v ON_ERROR_STOP=1 -c "$sql"
  else
    PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -F'|' -v ON_ERROR_STOP=1 -c "$sql"
  fi
}

QUERY=$(
  cat <<'EOSQL'
SELECT
  COUNT(*)::bigint,
  COUNT(*) FILTER (WHERE page_type_code IN ('HTML','BINARY','DUPLICATE','ERROR'))::bigint,
  COUNT(*) FILTER (WHERE page_type_code = 'FRONTIER')::bigint,
  COUNT(*) FILTER (WHERE page_type_code = 'PROCESSING')::bigint
FROM crawldb.page;
EOSQL
)

cleanup() {
  printf '%b\n' "$R"
  exit 0
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --docker) USE_DOCKER=1 ;;
      --interval)
        [[ $# -ge 2 ]] || { echo "${C_RED}--interval requires a number${R}" >&2; exit 2; }
        REFRESH_SEC="$2"
        shift
        ;;
      --help|-h) usage; exit 0 ;;
      *)
        echo "${C_RED}Unknown option: $1${R}" >&2
        usage >&2
        exit 2
        ;;
    esac
    shift
  done
  if ! [[ "$REFRESH_SEC" =~ ^[0-9]+$ ]] || ((REFRESH_SEC < 1)); then
    echo "${C_RED}Refresh interval must be a positive integer (got: ${REFRESH_SEC})${R}" >&2
    exit 2
  fi
}

validate_counts() {
  local t=$1 f=$2 fr=$3 pr=$4
  [[ "$t" =~ ^[0-9]+$ && "$f" =~ ^[0-9]+$ && "$fr" =~ ^[0-9]+$ && "$pr" =~ ^[0-9]+$ ]]
}

render_frame() {
  local loaded=$1 fetched=$2 frontier=$3 processing=$4 max_pages=$5
  local now pct_str bcolor queue eta_sec eta_label
  now="$(date '+%Y-%m-%d %H:%M:%S')"

  printf '%b┌─ Crawldb crawl status ───────────────────────────────────┐%b\n' "$B$C_CYAN" "$R"
  printf '%b│ %-56s │%b\n' "$now" "$R"
  printf '%b└──────────────────────────────────────────────────────────┘%b\n\n' "$B$C_CYAN" "$R"

  pct_str=" —"
  bcolor="$(budget_fill_color "$loaded" "$max_pages")"
  if ((max_pages > 0)); then
    pct_str="$(printf ' (%3d%%)' $((loaded * 100 / max_pages)))"
    if (( loaded * 100 / max_pages > 100 )); then pct_str=" (100%+)"; fi
  fi

  # Budget row: custom right side shows loaded / max and percent.
  local bfill bempty i
  if ((max_pages > 0)); then
    # With a known cap, fill reflects loaded vs budget (same rule as the plan sketch).
    bfill="$(compute_fill "$loaded" "$max_pages" "$BAR_WIDTH" "$loaded")"
  else
    # No maxTotalPages resolved: avoid a misleading "full" bar (loaded/loaded).
    bfill=0
  fi
  if (( bfill > BAR_WIDTH )); then bfill=$BAR_WIDTH; fi
  bempty=$((BAR_WIDTH - bfill))
  printf '  %-22s %b[' "Budget (loaded / max)" "$R"
  printf '%b' "$bcolor"
  for ((i = 0; i < bfill; i++)); do printf '█'; done
  printf '%b' "$R"
  printf '%b' "$C_DIM"
  for ((i = 0; i < bempty; i++)); do printf '·'; done
  if ((max_pages > 0)); then
    printf '%b]%b  %s / %s%s\n\n' "$R" "$R" "$loaded" "$max_pages" "$pct_str"
  else
    printf '%b]%b  %s / — (max n/a)%b\n\n' "$R" "$R" "$loaded" "$R"
  fi

  # State rows: scale each count against max_pages; if max is 0, fall back to loaded (plan: proportion of total).
  local fb="$loaded"

  print_bar_row "Fetched (terminal)" "$fetched" "$max_pages" "$fb" "$C_GREEN"
  print_bar_row "Frontier" "$frontier" "$max_pages" "$fb" "$C_YELLOW"
  print_bar_row "Processing" "$processing" "$max_pages" "$fb" "$C_BLUE"

  printf '\n'
  printf '  Totals:%b  loaded=%s  fetched=%s  frontier=%s  processing=%s\n' "$R" "$loaded" "$fetched" "$frontier" "$processing"
  printf '  %b(sanity: %s + %s + %s = %s)%b\n\n' "$C_DIM" "$fetched" "$frontier" "$processing" "$((fetched + frontier + processing))" "$R"

  queue=$((frontier + processing))
  if ((queue == 0)); then
    printf '  %bETA (approx, %ds/page on queue):%b  queue empty\n' "$B" "$ETA_SEC_PER_URL" "$R"
  else
    eta_sec=$((queue * ETA_SEC_PER_URL))
    eta_label="$(format_duration "$eta_sec")"
    printf '  %bETA (approx, %ds/page on queue):%b  ~%s  (%d URLs × %ds)\n' "$B" "$ETA_SEC_PER_URL" "$R" "$eta_label" "$queue" "$ETA_SEC_PER_URL"
  fi
  printf '\n  %bNote:%b New discoveries and parallelism are not included.\n' "$C_DIM" "$R"
  printf '\n  %bRefresh every %ds · Ctrl+C to quit%b\n' "$C_DIM" "$REFRESH_SEC" "$R"
}

main() {
  parse_args "$@"
  trap cleanup INT TERM

  local max_pages
  max_pages="$(resolve_max_total_pages)"

  if ((USE_DOCKER)) && ! command -v docker >/dev/null 2>&1; then
    echo "${C_RED}docker not found; run without --docker if psql is local.${R}" >&2
    exit 1
  fi
  if ! ((USE_DOCKER)) && ! command -v psql >/dev/null 2>&1; then
    echo "${C_RED}psql not found. Install PostgreSQL client tools or use --docker.${R}" >&2
    exit 1
  fi

  while true; do
    clear_screen_safe
    local line loaded fetched frontier processing
    if ! line="$(run_sql "$QUERY" 2>/dev/null)"; then
      printf '%bDatabase query failed%b\n\n' "$C_RED" "$R"
      printf '  %bCheck PostgreSQL is running and credentials match application.properties.%b\n' "$C_DIM" "$R"
      if ((USE_DOCKER)); then
        printf '  %bDocker container:%b %s\n' "$C_DIM" "$R" "$DOCKER_CONTAINER"
      else
        printf '  %bConnection:%b %s:%s / %s\n' "$C_DIM" "$R" "$PGHOST" "$PGPORT" "$PGDATABASE"
      fi
      printf '\n  %bRetrying in %ds... (Ctrl+C to quit)%b\n' "$C_DIM" "$REFRESH_SEC" "$R"
      sleep "$REFRESH_SEC"
      continue
    fi

    IFS='|' read -r loaded fetched frontier processing <<<"$line"
    if ! validate_counts "$loaded" "$fetched" "$frontier" "$processing"; then
      printf '%bUnexpected query output:%b %q\n' "$C_RED" "$R" "$line"
      sleep "$REFRESH_SEC"
      continue
    fi

    render_frame "$loaded" "$fetched" "$frontier" "$processing" "$max_pages"
    sleep "$REFRESH_SEC"
  done
}

main "$@"
