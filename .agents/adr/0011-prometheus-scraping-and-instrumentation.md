# ADR 0011: Prometheus Dynamic Scraping and Microservices Instrumentation

## Status
Accepted

## Context
Initially, the Prometheus setup relied on static IP address mapping which made scaling and container redeployments brittle. Furthermore, all microservices (both Java Spring Boot and Python FastAPI/dynamic executors) and the Envoy load balancer were uninstrumented, lacking CPU, memory, thread pool, network, and application-specific metrics. Permissions conflicts also prevented Prometheus from querying `/var/run/docker.sock` to dynamically discover running containers.

Additionally, infrastructure-level services (PostgreSQL, Redis, Kafka) lacked any metrics exposure, as they do not natively export Prometheus-compatible endpoints. Grafana dashboards for these services relied on direct client-protocol queries (raw SQL, Redis commands, Kafka API) which are inflexible, non-time-series, and cannot be correlated with application-level metrics on the same Prometheus datasource.

## Decision
1. **Dynamic Container Discovery**: Configured Prometheus to dynamically discover targets on the Docker network by mounting the host daemon socket (`/var/run/docker.sock`) and using `docker_sd_configs` filtered by compose labels (`application=distributed-trading`).
2. **Relabeling System**: Standardized scraping targets via Docker Compose labels:
   * `prometheus.scrape: "true"`
   * `prometheus.port: "<port>"`
   * `prometheus.path: "<path>"`
3. **Privilege Access Fix**: Configured `user: root` for Prometheus container in `docker-compose.yml` to allow reading the mounted read-only Docker socket.
4. **Java Instrumentation**: Integrated Spring Boot Actuator and Micrometer Prometheus registries in `order-processing-service` (port `8081`) and `order-management-service` (port `8082`), exposing JVM, HikariCP, JPA, Kafka Listener, and Tomcat threads.
5. **Python Instrumentation**: Integrated `prometheus-client` manually into `connection-manager-alpaca` (port `8000`) and the dynamically loaded `signal-generator` container (ports `8001`/`8002` mapping to `8000` internal). Created an abstract `telemetry.py` module to decouple custom metrics from strategy implementations.
6. **Envoy Balancer**: Verified and scraped metrics natively from the Envoy admin panel (port `9901` at `/stats/prometheus`).
7. **Sidecar Exporters for Infrastructure Services**: Deployed three Prometheus exporter sidecar containers:
   * `postgres-exporter` (`prometheuscommunity/postgres-exporter`, port `9187`) — bridges PostgreSQL `pg_stat_*` tables into Prometheus metrics.
   * `redis-exporter` (`oliver006/redis_exporter`, port `9121`) — bridges Redis `INFO` stats into Prometheus metrics.
   * `kafka-exporter` (`danielqsj/kafka-exporter`, port `9308`) — bridges Kafka broker, topic, and consumer group data into Prometheus metrics.
8. **Grafana Dashboard Migration**: Migrated Redis and Kafka Grafana dashboards from direct client-protocol queries to Prometheus datasource time-series queries. Appended server-health panels to the PostgreSQL dashboard.

## Consequences
* **Automated Scaling**: Any newly deployed container matching the compose labels will be dynamically discovered and scraped without manual Prometheus config modifications.
* **Telemetry Consistency**: Standardized metrics (CPU, virtual/resident memory, garbage collection, file descriptors, network traffic) are collected uniformly across all microservice runtimes (Java and Python).
* **Decoupled Strategy Code**: Dynamic indicator calculations (moving averages, z-scores) write metrics via a framework-level `telemetry.py` import rather than raw client declarations.
* **Unified Grafana Datasource**: All infrastructure metrics (PostgreSQL, Redis, Kafka) are now served via the Prometheus datasource, enabling cross-service correlation and time-range filtering in Grafana.
* **Exporter Overhead**: Three additional containers are introduced. Their resource footprint is minimal (each under 50MB RAM), but they must be considered in capacity planning.
