# Order Processing Service (OPS) Configurations

## Environment Variables
The following properties define the OPS configuration:

### Spring Profiles
- `spring.profiles.active`: Active environment profile (e.g., `dev`, `prod`).

### Kafka Configuration
- `spring.kafka.bootstrap-servers`: Kafka cluster addresses.
- `app.kafka.topics.signal-event`: Topic to consume trading signals.
- `app.kafka.topics.order-create-event`: Topic to publish order creation intents.

### Redis Configuration
- `spring.data.redis.host`: Redis host for low-latency state.
- `spring.data.redis.port`: Redis port.
- `spring.data.redis.password`: Redis authentication.

### gRPC Endpoints
- `app.grpc.broker-alpaca.address`: gRPC endpoint for Alpaca Connection Manager.
- `app.grpc.broker-x.address`: gRPC endpoint for Broker X Connection Manager.

### Risk Management Settings
- `app.risk.max-order-value`: Maximum allowed value for a single order.
- `app.risk.max-daily-drawdown`: Threshold for daily loss stoppage.

**Note**: There are strictly **no** SQL or JPA configurations as this service is purely in-memory/cache driven.
