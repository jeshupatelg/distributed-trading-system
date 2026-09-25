#!/usr/bin/env bash
# ==============================================================================
# Script: clean_pg.sh
# Purpose: Purge PostgreSQL database schemas or tables with vacuum/truncate,
#          drop/recreate schema, and optional pg_dump backup.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEFAULT_DUMP_DIR="${SCRIPT_DIR}/../dumps"

# Default configuration
SCOPE="specific-schema"   # "specific-schema" or "all-schema"
SCHEMA="public"
ACTION="truncate"         # "truncate" (vacuum + truncate) or "drop" (drop schema/recreate)
BACKUP=false
DRY_RUN=false
PG_CONTAINER="homeserver-pg"
DB_HOST="localhost"
DB_PORT=5432
DB_USER="admin"
DB_NAME="trading_agent"
export PGPASSWORD="${PGPASSWORD:-admin}"
DUMP_DIR="${DEFAULT_DUMP_DIR}"

# Protected system schemas
SYSTEM_SCHEMAS=("pg_catalog" "information_schema" "pg_toast")

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --scope <specific-schema|all-schema>  Scope of purge:
                                          'specific-schema': Target schema specified by --schema (default)
                                          'all-schema': Target all non-system schemas
  --schema <name>                       Target schema name (default: public)
  --action <truncate|drop>              Cleanup action:
                                          'truncate': Truncate tables and run VACUUM FULL (default)
                                          'drop': DROP SCHEMA CASCADE and recreate empty schema
  --backup                              Export schema & data backup via pg_dump before purging
  --host <host>                         PostgreSQL host (default: localhost)
  --port <port>                         PostgreSQL port (default: 5432)
  --user <user>                         Database username (default: admin)
  --db <dbname>                         Database name (default: trading_agent)
  --container <name>                    Docker container name for PostgreSQL (default: homeserver-pg)
  --dump-dir <path>                     Custom directory for backup dumps
  --dry-run                             Display actions without executing SQL
  -h, --help                            Show this help message
EOF
    exit 0
}

# Parse CLI arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --scope)
            SCOPE="$2"
            shift 2
            ;;
        --schema)
            SCHEMA="$2"
            shift 2
            ;;
        --action)
            ACTION="$2"
            shift 2
            ;;
        --backup)
            BACKUP=true
            shift
            ;;
        --host)
            DB_HOST="$2"
            shift 2
            ;;
        --port)
            DB_PORT="$2"
            shift 2
            ;;
        --user)
            DB_USER="$2"
            shift 2
            ;;
        --db)
            DB_NAME="$2"
            shift 2
            ;;
        --container)
            PG_CONTAINER="$2"
            shift 2
            ;;
        --dump-dir)
            DUMP_DIR="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Error: Unknown argument: $1" >&2
            usage
            ;;
    esac
done

if [[ "$SCOPE" != "specific-schema" && "$SCOPE" != "all-schema" ]]; then
    echo "Error: Invalid scope '$SCOPE'. Allowed: 'specific-schema', 'all-schema'" >&2
    exit 1
fi

if [[ "$ACTION" != "truncate" && "$ACTION" != "drop" ]]; then
    echo "Error: Invalid action '$ACTION'. Allowed: 'truncate', 'drop'" >&2
    exit 1
fi

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
mkdir -p "${DUMP_DIR}"

# Detect execution transport: docker exec vs local binary
USE_DOCKER=false
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -Eq "^${PG_CONTAINER}$"; then
    USE_DOCKER=true
fi

exec_psql() {
    local sql="$1"
    if [[ "$USE_DOCKER" == true ]]; then
        docker exec -i -e PGPASSWORD="${PGPASSWORD}" "${PG_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q -c "$sql"
    else
        psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q -c "$sql"
    fi
}

exec_pg_dump() {
    local outfile="$1"
    if [[ "$USE_DOCKER" == true ]]; then
        docker exec -i -e PGPASSWORD="${PGPASSWORD}" "${PG_CONTAINER}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" > "$outfile"
    else
        pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" > "$outfile"
    fi
}

echo "=================================================="
echo " Starting PostgreSQL Purge"
echo " Scope:            ${SCOPE}"
echo " Schema:           $(if [[ "$SCOPE" == "specific-schema" ]]; then echo "$SCHEMA"; else echo "ALL non-system"; fi)"
echo " Action:           ${ACTION}"
echo " Backup Enabled:   ${BACKUP}"
echo " Transport:        $(if [[ "$USE_DOCKER" == true ]]; then echo "docker exec (${PG_CONTAINER})"; else echo "direct (${DB_HOST}:${DB_PORT}/${DB_NAME})"; fi)"
echo " Dry Run:          ${DRY_RUN}"
echo "=================================================="

# Test connection
if [[ "$DRY_RUN" == false ]]; then
    CONN_CHECK=$(exec_psql "SELECT 1;" 2>&1 || echo "FAIL")
    if [[ "$CONN_CHECK" == *"FAIL"* ]]; then
        echo "Error: Unable to connect to PostgreSQL (${CONN_CHECK}). Check credentials or container status." >&2
        exit 1
    fi
fi

# Execute backup if requested
if [[ "$BACKUP" == true ]]; then
    DUMP_FILE="${DUMP_DIR}/pg_dump_${DB_NAME}_${TIMESTAMP}.sql"
    echo "--- Creating PostgreSQL Full Database Backup ---"
    echo "Destination: ${DUMP_FILE}"
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would execute pg_dump to ${DUMP_FILE}"
    else
        exec_pg_dump "${DUMP_FILE}"
        echo "Backup saved successfully: ${DUMP_FILE} ($(du -h "${DUMP_FILE}" | cut -f1))"
    fi
fi

# Determine target schemas
TARGET_SCHEMAS=()
if [[ "$SCOPE" == "specific-schema" ]]; then
    TARGET_SCHEMAS=("$SCHEMA")
else
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would query all non-system schemas"
        TARGET_SCHEMAS=("$SCHEMA")
    else
        ALL_SCHEMAS=$(exec_psql "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast');" | tr -d ' ' | grep -v '^$' | grep -v 'schema_name' | grep -v -- '---' || true)
        while IFS= read -r s; do
            [[ -n "$s" ]] && TARGET_SCHEMAS+=("$s")
        done <<< "$ALL_SCHEMAS"
    fi
fi

echo "Target schema(s) to process: ${TARGET_SCHEMAS[*]}"

for current_schema in "${TARGET_SCHEMAS[@]}"; do
    echo "--- Processing Schema: '${current_schema}' ---"
    
    if [[ "$ACTION" == "truncate" ]]; then
        echo "Action: Truncate all tables and VACUUM FULL in schema '${current_schema}'"
        TRUNCATE_SQL="
        DO \$\$
        DECLARE
            r RECORD;
        BEGIN
            FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = '${current_schema}') LOOP
                EXECUTE 'TRUNCATE TABLE \"' || '${current_schema}' || '\".\"' || r.tablename || '\" RESTART IDENTITY CASCADE;';
            END LOOP;
        END \$\$;
        "
        VACUUM_SQL="VACUUM FULL ANALYZE;"
        
        if [[ "$DRY_RUN" == true ]]; then
            echo "[DRY-RUN] Would execute truncate block on all tables in schema '${current_schema}'"
            echo "[DRY-RUN] Would execute: ${VACUUM_SQL}"
        else
            exec_psql "$TRUNCATE_SQL"
            echo "  All tables in schema '${current_schema}' truncated and identities reset."
            exec_psql "$VACUUM_SQL"
            echo "  VACUUM FULL completed for schema '${current_schema}'."
        fi
        
    elif [[ "$ACTION" == "drop" ]]; then
        echo "Action: DROP SCHEMA '${current_schema}' CASCADE and recreate"
        DROP_SQL="DROP SCHEMA IF EXISTS \"${current_schema}\" CASCADE; CREATE SCHEMA \"${current_schema}\";"
        
        if [[ "$DRY_RUN" == true ]]; then
            echo "[DRY-RUN] Would execute: ${DROP_SQL}"
        else
            exec_psql "$DROP_SQL"
            echo "  Schema '${current_schema}' dropped and recreated empty."
        fi
    fi
done

echo "PostgreSQL cleanup completed successfully."
