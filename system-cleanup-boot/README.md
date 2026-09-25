# System Cleanup & Bootstrap Suite (`system-cleanup-boot`)

A modular, automated, and configurable cleanup and bootstrap suite designed to safely purge accumulated paper-trading data, reset brokers and databases, and perform a green redeployment of the Distributed Trading System.

---

## Directory Layout & Separation of Concern

```text
system-cleanup-boot/
├── cleanup_boot.sh            # Master orchestrator CLI
├── scripts/
│   ├── clean_kafka.sh         # Kafka topic/message purge, offset reset & pre-purge JSONL backup
│   ├── clean_redis.sh         # Redis keyspace purge (pattern-based or FLUSHDB) & RDB snapshot backup
│   └── clean_pg.sh            # PostgreSQL schema/table purge (truncate vs drop) & pg_dump backup
├── dumps/                     # Pre-purge backup destination (gitignored)
│   └── .gitkeep
└── README.md                  # Operator documentation
```

---

## Architecture & Safety Principles

1. **State Isolation & Producer Freeze**:
   - Before executing data purges, `cleanup_boot.sh` automatically stops active trading services (`order-processing-service`, `order-management-service`, `signal-gen-*`, `connection-manager-alpaca`, `tick-lb`, `price-cache-service`, `quant-dashboard`, `notification-service`).
   - This guarantees that no new order requests, market ticks, or events are written while brokers and databases are being cleaned.

2. **Pre-Purge Point-in-Time Backups (`--backup`)**:
   - **Kafka**: Consumes all available messages from offset 0 into `.jsonl` files (`kafka_<topic>_<timestamp>.jsonl`).
   - **Redis**: Generates an atomic RDB snapshot via `BGSAVE` / `SAVE` into `redis_dump_<timestamp>.rdb`.
   - **PostgreSQL**: Performs a full schema and data export via `pg_dump` into `pg_dump_<dbname>_<timestamp>.sql`.
   - All backups are stored in `dumps/`, which is excluded from source control in `.gitignore`.

3. **Protected Internals**:
   - **Kafka**: Protected topics like `__consumer_offsets`, `_schemas`, and `connect-*` are never deleted.
   - **PostgreSQL**: Protected system schemas (`pg_catalog`, `information_schema`, `pg_toast`) are preserved.

4. **Dual Transport Resolution**:
   - Scripts automatically detect whether Docker containers (`kafka`, `homeserver-redis`, `homeserver-pg`) are running locally on the host and use `docker exec`, or fall back to direct network connection endpoints (`localhost:9092`, `localhost:6379`, `localhost:5432`).

---

## Usage & CLI Reference

### 1. Master Orchestrator (`cleanup_boot.sh`)

```bash
./cleanup_boot.sh [OPTIONS]
```

#### Cleanup Selection Flags
| Flag | Description |
| :--- | :--- |
| `--all-cleanup` | Purges Kafka, Redis, and PostgreSQL completely. |
| `--kafka-cleanup` | Purges Kafka topics and messages only. |
| `--redis-cleanup` | Purges Redis keyspace only. |
| `--pg-cleanup` | Purges PostgreSQL database only. |

#### Lifecycle & Safety Flags
| Flag | Description |
| :--- | :--- |
| `--backup` | Takes pre-purge backups for all selected services before deleting data. |
| `--down` | Executes `docker compose down` after data purge. |
| `--restart` | Rebuilds and launches the stack (`docker compose down && docker compose up -d --build`). |
| `--dry-run` | Previews all planned actions without modifying data or containers. |
| `-h`, `--help` | Displays help message and CLI options. |

#### Sub-Script Configuration Overrides
| Flag | Values | Default | Description |
| :--- | :--- | :--- | :--- |
| `--kafka-mode` | `hardcoded` \| `all` | `hardcoded` | `hardcoded`: clean trading topics. `all`: clean all user topics. |
| `--redis-mode` | `hardcoded` \| `all` | `hardcoded` | `hardcoded`: clean trading keyspace patterns. `all`: `FLUSHDB`. |
| `--pg-scope` | `specific-schema` \| `all-schema` | `specific-schema` | Target specified schema or all non-system schemas. |
| `--pg-action` | `truncate` \| `drop` | `truncate` | `truncate`: `TRUNCATE TABLE ... CASCADE`. `drop`: `DROP SCHEMA CASCADE`. |
| `--pg-schema` | `<schema_name>` | `public` | Target schema for PostgreSQL cleanup. |
| `--dump-dir` | `<directory_path>` | `dumps/` | Target directory for backup dumps. |

---

## Common Operational Examples

### Scenario 1: Complete System Purge & Green Restart
Freezes services, purges Kafka trading topics (and recreates with clean offsets), deletes trading Redis keys, truncates PostgreSQL order tracking tables, and rebuilds the stack:
```bash
./cleanup_boot.sh --all-cleanup --restart
```

### Scenario 2: Complete Purge with Safety Backups
Performs a full purge across all three data stores, but exports JSONL Kafka topic dumps, Redis RDB snapshot, and PostgreSQL SQL dump first:
```bash
./cleanup_boot.sh --all-cleanup --backup --restart
```

### Scenario 3: Deep Reset (Nuclear Clean)
Flushes entire Redis database (`FLUSHDB`), deletes all user Kafka topics, drops and recreates PostgreSQL `public` schema, and rebuilds the stack:
```bash
./cleanup_boot.sh --all-cleanup --kafka-mode all --redis-mode all --pg-action drop --restart
```

### Scenario 4: Fast Broker Reset (Kafka & Redis only)
Cleans event streaming and cache layers without modifying PostgreSQL trade history:
```bash
./cleanup_boot.sh --kafka-cleanup --redis-cleanup
```

### Scenario 5: Dry-Run Inspection
Previews planned commands and key/topic targets without mutating anything:
```bash
./cleanup_boot.sh --all-cleanup --dry-run
```

---

## Individual Sub-script Reference

Each cleaner can also be executed independently:

### Kafka Cleaner (`scripts/clean_kafka.sh`)
```bash
# Purge standard trading topics
./scripts/clean_kafka.sh --mode hardcoded

# Purge with backup and custom partition count
./scripts/clean_kafka.sh --mode all --backup --partitions 3
```

### Redis Cleaner (`scripts/clean_redis.sh`)
```bash
# Clean trading keyspace patterns
./scripts/clean_redis.sh --mode hardcoded

# Full database flush with RDB backup
./scripts/clean_redis.sh --mode all --backup
```

### PostgreSQL Cleaner (`scripts/clean_pg.sh`)
```bash
# Truncate public schema tables and run VACUUM FULL
./scripts/clean_pg.sh --scope specific-schema --schema public --action truncate

# Drop and recreate schema with pre-purge pg_dump
./scripts/clean_pg.sh --scope specific-schema --schema public --action drop --backup
```
