#!/usr/bin/env bash
# ==============================================================================
# Script: clean_kafka.sh
# Purpose: Purge Kafka topics and messages with optional backup and recreation.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEFAULT_DUMP_DIR="${SCRIPT_DIR}/../dumps"

# Default configuration
MODE="hardcoded"          # "hardcoded" or "all"
BACKUP=false
RECREATE=true
DRY_RUN=false
PARTITIONS=3
REPLICATION_FACTOR=1
KAFKA_CONTAINER="kafka"
BOOTSTRAP_SERVER="localhost:9092"
DUMP_DIR="${DEFAULT_DUMP_DIR}"

# Canonical Trading System Topics
HARDCODED_TOPICS=(
    "trading-signals"
    "order-create-events"
    "order-reject-events"
    "order-complete-events"
    "raw-order-updates"
)

# Protected internal topics that should never be deleted
PROTECTED_TOPICS=(
    "__consumer_offsets"
    "_schemas"
    "connect-configs"
    "connect-offsets"
    "connect-status"
)

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --mode <hardcoded|all>      Topic selection mode:
                                'hardcoded': Clean only known trading topics (default)
                                'all': Clean all topics except Kafka internals
  --backup                    Backup topic messages before purging (dumps to .jsonl)
  --recreate                  Recreate deleted topics with fresh offsets (default: true)
  --no-recreate               Delete topics without recreating them
  --partitions <n>            Number of partitions for recreated topics (default: 3)
  --replication-factor <n>    Replication factor for recreated topics (default: 1)
  --bootstrap-server <host>   Kafka bootstrap server (default: localhost:9092)
  --container <name>          Docker container name for Kafka (default: kafka)
  --dump-dir <path>           Custom directory for backup dumps
  --dry-run                   Display actions without modifying data
  -h, --help                  Show this help message
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
        --recreate)
            RECREATE=true
            shift
            ;;
        --no-recreate)
            RECREATE=false
            shift
            ;;
        --partitions)
            PARTITIONS="$2"
            shift 2
            ;;
        --replication-factor)
            REPLICATION_FACTOR="$2"
            shift 2
            ;;
        --bootstrap-server)
            BOOTSTRAP_SERVER="$2"
            shift 2
            ;;
        --container)
            KAFKA_CONTAINER="$2"
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

# Validate mode
if [[ "$MODE" != "hardcoded" && "$MODE" != "all" ]]; then
    echo "Error: Invalid mode '$MODE'. Allowed: 'hardcoded', 'all'" >&2
    exit 1
fi

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
mkdir -p "${DUMP_DIR}"

# Detect execution transport: docker exec vs local binary
USE_DOCKER=false
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -Eq "^${KAFKA_CONTAINER}$"; then
    USE_DOCKER=true
    # When inside docker container, internal bootstrap is kafka:9092 or localhost:9092
    KAFKA_EXEC_BOOTSTRAP="localhost:9092"
else
    KAFKA_EXEC_BOOTSTRAP="${BOOTSTRAP_SERVER}"
fi

exec_kafka() {
    if [[ "$USE_DOCKER" == true ]]; then
        docker exec -i "${KAFKA_CONTAINER}" "$@"
    else
        "$@"
    fi
}

echo "=================================================="
echo " Starting Kafka Purge"
echo " Mode:             ${MODE}"
echo " Backup Enabled:   ${BACKUP}"
echo " Recreate Topics:  ${RECREATE}"
echo " Transport:        $(if [[ "$USE_DOCKER" == true ]]; then echo "docker exec (${KAFKA_CONTAINER})"; else echo "direct (${BOOTSTRAP_SERVER})"; fi)"
echo " Dry Run:          ${DRY_RUN}"
echo "=================================================="

# Determine target topics
TARGET_TOPICS=()

if [[ "$MODE" == "hardcoded" ]]; then
    TARGET_TOPICS=("${HARDCODED_TOPICS[@]}")
else
    echo "Querying active topics from Kafka broker..."
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would fetch all topics via kafka-topics.sh --list"
        TARGET_TOPICS=("${HARDCODED_TOPICS[@]}")
    else
        ACTIVE_TOPICS=$(exec_kafka kafka-topics.sh --bootstrap-server "${KAFKA_EXEC_BOOTSTRAP}" --list 2>/dev/null || true)
        while IFS= read -r topic; do
            [[ -z "$topic" ]] && continue
            
            # Check against protected topics
            IS_PROTECTED=false
            for prot in "${PROTECTED_TOPICS[@]}"; do
                if [[ "$topic" == "$prot" ]]; then
                    IS_PROTECTED=true
                    break
                fi
            done
            
            if [[ "$IS_PROTECTED" == false ]]; then
                TARGET_TOPICS+=("$topic")
            else
                echo "Skipping protected internal topic: ${topic}"
            fi
        done <<< "$ACTIVE_TOPICS"
    fi
fi

if [[ ${#TARGET_TOPICS[@]} -eq 0 ]]; then
    echo "No target topics identified for cleanup."
    exit 0
fi

echo "Target topics to purge (${#TARGET_TOPICS[@]}): ${TARGET_TOPICS[*]}"

# Execute backup if requested
if [[ "$BACKUP" == true ]]; then
    echo "--- Executing Topic Message Backups ---"
    for topic in "${TARGET_TOPICS[@]}"; do
        DUMP_FILE="${DUMP_DIR}/kafka_${topic}_${TIMESTAMP}.jsonl"
        echo "Backing up messages for topic: '${topic}' -> ${DUMP_FILE}"
        if [[ "$DRY_RUN" == true ]]; then
            echo "[DRY-RUN] Would execute: kafka-console-consumer.sh --from-beginning --timeout-ms 3000 to ${DUMP_FILE}"
        else
            exec_kafka kafka-console-consumer.sh \
                --bootstrap-server "${KAFKA_EXEC_BOOTSTRAP}" \
                --topic "${topic}" \
                --from-beginning \
                --timeout-ms 4000 > "${DUMP_FILE}" 2>/dev/null || true
            MSG_COUNT=$(wc -l < "${DUMP_FILE}" 2>/dev/null || echo 0)
            echo "  Exported ${MSG_COUNT} messages from '${topic}'"
        fi
    done
fi

# Execute topic deletion
echo "--- Deleting Target Topics ---"
for topic in "${TARGET_TOPICS[@]}"; do
    echo "Deleting topic: '${topic}'"
    if [[ "$DRY_RUN" == true ]]; then
        echo "[DRY-RUN] Would execute: kafka-topics.sh --delete --topic ${topic}"
    else
        exec_kafka kafka-topics.sh \
            --bootstrap-server "${KAFKA_EXEC_BOOTSTRAP}" \
            --delete \
            --topic "${topic}" 2>/dev/null || echo "  Notice: Topic '${topic}' may not have existed."
    fi
done

# Wait for deletion propagation
if [[ "$DRY_RUN" == false && "$RECREATE" == true ]]; then
    echo "Waiting 3 seconds for deletion metadata propagation..."
    sleep 3
fi

# Execute recreation if requested
if [[ "$RECREATE" == true ]]; then
    echo "--- Recreating Topics with Clean Offsets ---"
    for topic in "${TARGET_TOPICS[@]}"; do
        echo "Creating topic: '${topic}' (partitions: ${PARTITIONS}, replication-factor: ${REPLICATION_FACTOR})"
        if [[ "$DRY_RUN" == true ]]; then
            echo "[DRY-RUN] Would execute: kafka-topics.sh --create --topic ${topic} --partitions ${PARTITIONS} --replication-factor ${REPLICATION_FACTOR}"
        else
            exec_kafka kafka-topics.sh \
                --bootstrap-server "${KAFKA_EXEC_BOOTSTRAP}" \
                --create \
                --topic "${topic}" \
                --partitions "${PARTITIONS}" \
                --replication-factor "${REPLICATION_FACTOR}" \
                --if-not-exists 2>/dev/null || echo "  Warning: Topic '${topic}' creation returned non-zero status."
        fi
    done
fi

echo "Kafka cleanup completed successfully."
