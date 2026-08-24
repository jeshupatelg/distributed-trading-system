# Order Management Service (OMS) Configurations

## Environment Variables
The following properties define the OMS configuration:

### Spring Profiles
- `spring.profiles.active`: Active environment profile (e.g., `dev`, `prod`).

### Database & JPA Configuration (Hikari)
- `spring.datasource.url`: JDBC connection string to the RDBMS.
- `spring.datasource.username`: DB Username.
- `spring.datasource.password`: DB Password.
- `spring.datasource.hikari.maximum-pool-size`: Max Hikari connection pool size.
- `spring.jpa.hibernate.ddl-auto`: Hibernate schema strategy (e.g., `validate`).

### Kafka Configuration
- `spring.kafka.bootstrap-servers`: Kafka cluster addresses.
- `app.kafka.topics.order-create-event`: Topic to consume order creation intents.
- `app.kafka.topics.raw-order-update`: Topic to consume broker updates.
- `app.kafka.topics.order-complete-event`: Topic to publish finalized state to.

### Redis Configuration
- `spring.data.redis.host`: Redis host for cache synchronization.
- `spring.data.redis.port`: Redis port.
- `spring.data.redis.password`: Redis authentication.

### gRPC Endpoints (Reconciliation)
- `app.grpc.broker-alpaca.address`: gRPC endpoint for querying Alpaca.
- `app.grpc.broker-x.address`: gRPC endpoint for querying Broker X.

### Cron Scheduling
- `app.cron.reconciliation.schedule`: Cron expression for the reconciliation job (e.g., `0 */1 * * * *` for every minute).
