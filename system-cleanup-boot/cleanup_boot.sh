#!/usr/bin/env bash
# ==============================================================================
# Script: cleanup_boot.sh
# Purpose: Master orchestrator for system cleanup, data purge, and fresh bootstrap.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SCRIPTS_DIR="${SCRIPT_DIR}/scripts"
DUMP_DIR="${SCRIPT_DIR}/dumps"

# Execution selection flags
DO_KAFKA=false
DO_REDIS=false
DO_PG=false
DO_DOWN=false
DO_RESTART=false
BACKUP=false
DRY_RUN=false

# Nested script parameters
KAFKA_MODE="hardcoded"
REDIS_MODE="hardcoded"
PG_SCOPE="specific-schema"
PG_ACTION="truncate"
PG_SCHEMA="public"

# Application services that write to data stores and should be paused during purge
APP_SERVICES=(
    "order-processing-service"
    "order-management-service"
    "signal-gen-aapl"
    "signal-gen-msft"
    "connection-manager-alpaca"
    "tick-lb"
    "price-cache-service"
    "quant-dashboard"
    "notification-service"
)

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Master orchestrator for purging data stores (Kafka, Redis, PostgreSQL) and
restarting the Distributed Trading System for a clean, green deployment.

Cleanup Selection:
  --all-cleanup               Execute full purge: Kafka, Redis, and PostgreSQL
  --kafka-cleanup             Execute Kafka topics & messages cleanup
  --redis-cleanup             Execute Redis keyspace cleanup
  --pg-cleanup                Execute PostgreSQL schema / table cleanup

Stack Lifecycle Options:
  --down                      Execute 'docker compose down' after cleanup
  --restart                   Rebuild and restart stack: 'docker compose down && docker compose up -d --build'
  --backup                    Take pre-purge backups for all selected cleaners (saved to dumps/)
  --dry-run                   Preview all planned actions without modifying data or containers

Component Configuration Pass-Through:
  --kafka-mode <hardcoded|all>          Kafka mode: 'hardcoded' (trading topics) or 'all' (default: hardcoded)
  --redis-mode <hardcoded|all>          Redis mode: 'hardcoded' (trading patterns) or 'all' (FLUSHDB) (default: hardcoded)
  --pg-scope <specific-schema|all-schema> PG scope: 'specific-schema' or 'all-schema' (default: specific-schema)
  --pg-action <truncate|drop>           PG action: 'truncate' (vacuum+truncate) or 'drop' (recreate schema) (default: truncate)
  --pg-schema <name>                    Target PG schema (default: public)
  --dump-dir <path>                     Directory for pre-purge backups (default: system-cleanup-boot/dumps)

Help:
  -h, --help                  Show this help message

Examples:
  # 1. Preview full purge with dry-run
  ./cleanup_boot.sh --all-cleanup --dry-run

  # 2. Full purge with pre-purge backups and stack restart
  ./cleanup_boot.sh --all-cleanup --backup --restart

  # 3. Purge only Redis and Kafka without touching PostgreSQL
  ./cleanup_boot.sh --redis-cleanup --kafka-cleanup

  # 4. Deep reset: drop PG schema, flush all Redis keys, recreate all Kafka topics, and rebuild
  ./cleanup_boot.sh --all-cleanup --redis-mode all --kafka-mode all --pg-action drop --restart
EOF
    exit 0
}

# Parse CLI arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all-cleanup)
            DO_KAFKA=true
            DO_REDIS=true
            DO_PG=true
            shift
            ;;
        --kafka-cleanup)
            DO_KAFKA=true
            shift
            ;;
        --redis-cleanup)
            DO_REDIS=true
            shift
            ;;
        --pg-cleanup)
            DO_PG=true
            shift
            ;;
        --down)
            DO_DOWN=true
            shift
            ;;
        --restart)
            DO_RESTART=true
            shift
            ;;
        --backup)
            BACKUP=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --kafka-mode)
            KAFKA_MODE="$2"
            shift 2
            ;;
        --redis-mode)
            REDIS_MODE="$2"
            shift 2
            ;;
        --pg-scope)
            PG_SCOPE="$2"
            shift 2
            ;;
        --pg-action)
            PG_ACTION="$2"
            shift 2
            ;;
        --pg-schema)
            PG_SCHEMA="$2"
            shift 2
            ;;
        --dump-dir)
            DUMP_DIR="$2"
            shift 2
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

# If no cleanup or lifecycle option specified, show usage
if [[ "$DO_KAFKA" == false && "$DO_REDIS" == false && "$DO_PG" == false && "$DO_DOWN" == false && "$DO_RESTART" == false ]]; then
    echo "Error: No action requested. Please specify at least one cleanup or lifecycle option." >&2
    echo "Run '$(basename "$0") --help' for available options." >&2
    exit 1
fi

echo "=================================================================="
echo " Distributed Trading System: System Cleanup & Bootstrap Suite"
echo "=================================================================="
echo " Actions Planned:"
echo "   - Kafka Cleanup:      ${DO_KAFKA} (mode: ${KAFKA_MODE})"
echo "   - Redis Cleanup:      ${DO_REDIS} (mode: ${REDIS_MODE})"
echo "   - PostgreSQL Cleanup: ${DO_PG} (scope: ${PG_SCOPE}, action: ${PG_ACTION}, schema: ${PG_SCHEMA})"
echo "   - Pre-purge Backup:   ${BACKUP}"
echo "   - Docker Down:        ${DO_DOWN}"
echo "   - Docker Restart:     ${DO_RESTART}"
echo "   - Dry Run:            ${DRY_RUN}"
echo "   - Dump Directory:     ${DUMP_DIR}"
echo "=================================================================="

# Step 1: Pause active application producers if cleanup is requested
if [[ "$DO_KAFKA" == true || "$DO_REDIS" == true || "$DO_PG" == true ]]; then
    echo ""
    echo ">>> Step 1: Pausing Trading Application Services..."
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would pause/stop application services: ${APP_SERVICES[*]}"
    else
        if command -v docker >/dev/null 2>&1; then
            for svc in "${APP_SERVICES[@]}"; do
                if docker ps --format '{{.Names}}' 2>/dev/null | grep -Eq "^${svc}$"; then
                    echo "  Stopping container: ${svc}..."
                    docker stop -t 5 "${svc}" >/dev/null 2>&1 || true
                fi
            done
        else
            echo "  Notice: Docker CLI not found or not running locally; skipping container pause."
        fi
    fi
fi

# Step 2: Kafka Cleanup
if [[ "$DO_KAFKA" == true ]]; then
    echo ""
    echo ">>> Step 2: Executing Kafka Purge..."
    KAFKA_ARGS=(
        "--mode" "${KAFKA_MODE}"
        "--dump-dir" "${DUMP_DIR}"
    )
    [[ "$BACKUP" == true ]] && KAFKA_ARGS+=("--backup")
    [[ "$DRY_RUN" == true ]] && KAFKA_ARGS+=("--dry-run")
    
    bash "${SCRIPTS_DIR}/clean_kafka.sh" "${KAFKA_ARGS[@]}"
fi

# Step 3: Redis Cleanup
if [[ "$DO_REDIS" == true ]]; then
    echo ""
    echo ">>> Step 3: Executing Redis Purge..."
    REDIS_ARGS=(
        "--mode" "${REDIS_MODE}"
        "--dump-dir" "${DUMP_DIR}"
    )
    [[ "$BACKUP" == true ]] && REDIS_ARGS+=("--backup")
    [[ "$DRY_RUN" == true ]] && REDIS_ARGS+=("--dry-run")
    
    bash "${SCRIPTS_DIR}/clean_redis.sh" "${REDIS_ARGS[@]}"
fi

# Step 4: PostgreSQL Cleanup
if [[ "$DO_PG" == true ]]; then
    echo ""
    echo ">>> Step 4: Executing PostgreSQL Purge..."
    PG_ARGS=(
        "--scope" "${PG_SCOPE}"
        "--schema" "${PG_SCHEMA}"
        "--action" "${PG_ACTION}"
        "--dump-dir" "${DUMP_DIR}"
    )
    [[ "$BACKUP" == true ]] && PG_ARGS+=("--backup")
    [[ "$DRY_RUN" == true ]] && PG_ARGS+=("--dry-run")
    
    bash "${SCRIPTS_DIR}/clean_pg.sh" "${PG_ARGS[@]}"
fi

# Step 5: Docker Compose Teardown (if requested)
if [[ "$DO_DOWN" == true || "$DO_RESTART" == true ]]; then
    echo ""
    echo ">>> Step 5: Executing Docker Compose Teardown..."
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would execute: docker compose -f ${ROOT_DIR}/docker-compose.yml down"
    else
        if command -v docker >/dev/null 2>&1; then
            docker compose -f "${ROOT_DIR}/docker-compose.yml" down || true
        else
            echo "  Notice: Docker CLI not found; skipping docker compose down."
        fi
    fi
fi

# Step 6: Docker Compose Fresh Bootstrap (if requested)
if [[ "$DO_RESTART" == true ]]; then
    echo ""
    echo ">>> Step 6: Bootstrapping Fresh Application Stack..."
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would execute: docker compose -f ${ROOT_DIR}/docker-compose.yml up -d --build"
    else
        if command -v docker >/dev/null 2>&1; then
            docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d --build
            echo "Stack bootstrapped successfully."
        else
            echo "  Notice: Docker CLI not found; skipping docker compose up."
        fi
    fi
fi

echo ""
echo "=================================================================="
echo " Cleanup and Bootstrap Sequence Completed Successfully!"
echo "=================================================================="
