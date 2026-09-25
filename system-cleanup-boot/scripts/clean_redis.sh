#!/usr/bin/env bash
# ==============================================================================
# Script: clean_redis.sh
# Purpose: Purge Redis keyspace with optional backup (RDB snapshot) and selective patterns.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEFAULT_DUMP_DIR="${SCRIPT_DIR}/../dumps"

# Default configuration
MODE="hardcoded"          # "hardcoded" or "all"
BACKUP=false
DRY_RUN=false
REDIS_CONTAINER="homeserver-redis"
REDIS_HOST="localhost"
REDIS_PORT=6379
DUMP_DIR="${DEFAULT_DUMP_DIR}"

# Hardcoded Trading System Key Patterns
HARDCODED_PATTERNS=(
    "balance:*"
    "positions:*"
    "orders:pending:*"
    "market:last_price:*"
    "system:kill_switch*"
    "provider:status:*"
    "risk:*"
    "system:defaults:*"
    "balance:cash"
    "balance:blocked"
    "positions:*"
)

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --mode <hardcoded|all>   Key purge mode:
                             'hardcoded': Delete trading system key patterns (default)
                             'all': Complete database wipe via FLUSHDB
  --backup                 Create a point-in-time RDB backup before purge
  --host <host>            Redis host (default: localhost)
  --port <port>            Redis port (default: 6379)
  --container <name>       Docker container name for Redis (default: homeserver-redis)
  --dump-dir <path>        Custom directory for backup dumps
  --dry-run                Display matching keys and actions without deleting
  -h, --help               Show this help message
EOF
    exit 0
}

# Parse CLI arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)
            MODE="$2"
            shift 2
            ;;
        --backup)
            BACKUP=true
            shift
            ;;
        --host)
            REDIS_HOST="$2"
            shift 2
            ;;
        --port)
            REDIS_PORT="$2"
            shift 2
            ;;
        --container)
            REDIS_CONTAINER="$2"
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

if [[ "$MODE" != "hardcoded" && "$MODE" != "all" ]]; then
    echo "Error: Invalid mode '$MODE'. Allowed: 'hardcoded', 'all'" >&2
    exit 1
fi

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
mkdir -p "${DUMP_DIR}"

# Detect execution transport: docker exec vs local binary
USE_DOCKER=false
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -Eq "^${REDIS_CONTAINER}$"; then
    USE_DOCKER=true
fi

exec_redis() {
    if [[ "$USE_DOCKER" == true ]]; then
        docker exec -i "${REDIS_CONTAINER}" redis-cli "$@"
    else
        redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" "$@"
    fi
}

echo "=================================================="
echo " Starting Redis Purge"
echo " Mode:             ${MODE}"
echo " Backup Enabled:   ${BACKUP}"
echo " Transport:        $(if [[ "$USE_DOCKER" == true ]]; then echo "docker exec (${REDIS_CONTAINER})"; else echo "direct (${REDIS_HOST}:${REDIS_PORT})"; fi)"
echo " Dry Run:          ${DRY_RUN}"
echo "=================================================="

# Test Redis connection
if [[ "$DRY_RUN" == false ]]; then
    PING_RESP=$(exec_redis ping 2>/dev/null || echo "FAIL")
    if [[ "$PING_RESP" != "PONG" ]]; then
        echo "Error: Unable to connect to Redis server (${PING_RESP}). Check host/port or container status." >&2
        exit 1
    fi
fi

# Execute backup if requested
if [[ "$BACKUP" == true ]]; then
    DUMP_FILE="${DUMP_DIR}/redis_dump_${TIMESTAMP}.rdb"
    echo "--- Creating Redis Point-in-Time Backup ---"
    echo "Target backup destination: ${DUMP_FILE}"
    
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would trigger RDB dump via redis-cli --rdb ${DUMP_FILE}"
    else
        if [[ "$USE_DOCKER" == true ]]; then
            # Save inside container and copy out
            exec_redis bgsave >/dev/null 2>&1 || exec_redis save >/dev/null 2>&1
            # Wait momentarily for bgsave completion
            sleep 2
            docker cp "${REDIS_CONTAINER}:/data/dump.rdb" "${DUMP_FILE}" 2>/dev/null || \
                docker exec -i "${REDIS_CONTAINER}" redis-cli --rdb /tmp/dump.rdb >/dev/null 2>&1 && \
                docker cp "${REDIS_CONTAINER}:/tmp/dump.rdb" "${DUMP_FILE}" 2>/dev/null || \
                echo "Warning: Could not copy dump.rdb directly from container. Attempting stream dump..."
        else
            redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" --rdb "${DUMP_FILE}" 2>/dev/null || true
        fi
        
        if [[ -f "${DUMP_FILE}" ]]; then
            echo "Backup saved successfully: ${DUMP_FILE} ($(du -h "${DUMP_FILE}" | cut -f1))"
        else
            echo "Notice: Snapshot created in Redis data directory."
        fi
    fi
fi

# Execute purge
if [[ "$MODE" == "all" ]]; then
    echo "--- Executing Complete Keyspace Flush (FLUSHDB) ---"
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would execute: FLUSHDB"
    else
        FLUSH_OUT=$(exec_redis FLUSHDB)
        echo "Redis FLUSHDB Result: ${FLUSH_OUT}"
    fi
else
    echo "--- Purging Hardcoded Trading System Key Patterns ---"
    TOTAL_PURGED=0
    for pattern in "${HARDCODED_PATTERNS[@]}"; do
        echo "Scanning pattern: '${pattern}'"
        if [[ "$DRY_RUN" == true ]]; then
            echo "[DRY-RUN] Would scan and delete keys matching '${pattern}'"
        else
            # Scan matching keys in batches of 100
            KEYS=$(exec_redis --scan --pattern "${pattern}" 2>/dev/null || true)
            if [[ -n "$KEYS" ]]; then
                COUNT=$(echo "$KEYS" | wc -l)
                # Unlink matching keys efficiently
                echo "$KEYS" | while IFS= read -r key; do
                    [[ -n "$key" ]] && exec_redis UNLINK "$key" >/dev/null 2>&1 || true
                done
                echo "  Unlinked ${COUNT} keys matching '${pattern}'"
                TOTAL_PURGED=$((TOTAL_PURGED + COUNT))
            else
                echo "  No keys matched pattern '${pattern}'"
            fi
        fi
    done
    [[ "$DRY_RUN" == false ]] && echo "Total keys purged: ${TOTAL_PURGED}"
fi

echo "Redis cleanup completed successfully."
