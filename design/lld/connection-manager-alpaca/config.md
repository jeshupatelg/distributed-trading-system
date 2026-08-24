# Connection Manager (Alpaca) Configuration Specification

This document details the configuration options, environment variables, and settings required to run the `connection-manager-alpaca` service.

## 1. Environment Configurations (`.env`)

The `connection-manager-alpaca` loads configuration variables from standard environment variables or a local `.env` file at startup.

| Variable Name | Type | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Server Settings** | | | |
| `HOST` | String | `0.0.0.0` | IP interface address to bind the service listeners. |
| `PORT_REST` | Integer | `8000` | Port for fallback REST APIs and liveness/readiness health endpoints. |
| `PORT_GRPC` | Integer | `50051` | Port for the primary gRPC binary stream & unary interface. |
| **Alpaca API Credentials** | | | |
| `ALPACA_API_KEY` | String | *Required* | Alpaca API account credential key. |
| `ALPACA_SECRET_KEY` | String | *Required* | Alpaca API account secret key. |
| `ALPACA_BASE_URL` | String | `https://paper-api.alpaca.markets` | Base endpoint URL for paper/live REST API execution. |
| `ALPACA_DATA_FEED` | String | `iex` | Options: `iex` (free, 2-3% market depth) or `sip` (paid, full market tape). |
| **Kafka Configurations** | | | |
| `KAFKA_BOOTSTRAP_SERVERS` | String | `localhost:9092` | Comma-separated list of Kafka broker host:port addresses. |
| `KAFKA_TOPIC_RAW_ORDER_UPDATES` | String | `raw-order-updates` | Kafka topic where raw WebSocket order status feeds are forwarded. |
| **Trading Settings** | | | |
| `TICKERS_TO_TRACK` | String | `AAPL,MSFT` | Comma-separated list of tickers connection-manager-alpaca streams from Alpaca. |
| `DEFAULT_TIMEFRAME` | String | `1Min` | Bar aggregation timeframe for the WebSocket stream (e.g., `1Min`, `5Min`, `1Day`). |
| **Logging & Diagnostics** | | | |
| `LOG_LEVEL` | String | `INFO` | Level of logging output (`DEBUG`, `INFO`, `WARNING`, `ERROR`). |

---

## 2. Configuration Schema & Loading Validation (Python)

Below is the configuration class validator schema (`config.py`) that executes verification checks at boot-up:

```python
import os
import logging
from dotenv import load_dotenv

load_dotenv()
logger = logging.getLogger("Config")

# Server binding
HOST = os.getenv("HOST", "0.0.0.0")
PORT_REST = int(os.getenv("PORT_REST", "8000"))
PORT_GRPC = int(os.getenv("PORT_GRPC", "50051"))

# Credentials
ALPACA_API_KEY = os.getenv("ALPACA_API_KEY")
ALPACA_SECRET_KEY = os.getenv("ALPACA_SECRET_KEY")
ALPACA_BASE_URL = os.getenv("ALPACA_BASE_URL", "https://paper-api.alpaca.markets")
ALPACA_DATA_FEED = os.getenv("ALPACA_DATA_FEED", "iex").lower()

if not ALPACA_API_KEY or not ALPACA_SECRET_KEY:
    raise ValueError("CRITICAL: ALPACA_API_KEY and ALPACA_SECRET_KEY must be populated in the environment.")

# Clean base URL (strip trailing v2 for SDK compliance)
if ALPACA_BASE_URL.endswith("/v2"):
    ALPACA_BASE_URL = ALPACA_BASE_URL[:-3]
elif ALPACA_BASE_URL.endswith("/v2/"):
    ALPACA_BASE_URL = ALPACA_BASE_URL[:-4]

# Kafka configuration
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
KAFKA_TOPIC_RAW_ORDER_UPDATES = os.getenv("KAFKA_TOPIC_RAW_ORDER_UPDATES", "raw-order-updates")

# Tickers & Streams
TICKERS_TO_TRACK = [t.strip() for t in os.getenv("TICKERS_TO_TRACK", "AAPL,MSFT").split(",") if t.strip()]
DEFAULT_TIMEFRAME = os.getenv("DEFAULT_TIMEFRAME", "1Min")

# Logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(level=LOG_LEVEL, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
```

---

## 3. Shared gRPC Schema Configuration (`connection_manager.proto`)

Rather than maintaining duplicate copies of the gRPC Protobuf schema inside Python and Java services, [connection_manager.proto](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/lld/connection-manager-alpaca/connection_manager.proto) is treated as a **shared external configuration asset**.

During deployment, it is mounted into the target service containers.

### A. Kubernetes ConfigMap Deployment
You can define the schema as a ConfigMap in Kubernetes and mount it to the containers:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grpc-schemas-config
  namespace: trading
data:
  connection_manager.proto: |
    # (Content of connection_manager.proto)
```

And mount it in the Deployment spec of the services (e.g., `connection-manager-alpaca` and `combined-oms`):
```yaml
spec:
  containers:
  - name: combined-oms
    image: trading-poc/combined-oms:latest
    volumeMounts:
    - name: schema-volume
      mountPath: /opt/schemas
  volumes:
  - name: schema-volume
    configMap:
      name: grpc-schemas-config
```

### B. Docker Compose Volume Mount
For local POC testing in Docker Compose, mount the directory containing the `.proto` file as a shared read-only volume:

```yaml
services:
  connection-manager-alpaca:
    volumes:
      - ./schemas:/opt/schemas:ro

  combined-oms:
    volumes:
      - ./schemas:/opt/schemas:ro
```
