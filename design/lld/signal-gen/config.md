# Configuration Specification: signal-gen

This document specifies the configurations, environment variables, and startup loading schemas for the `signal-gen` service.

---

## 1. Environment Configurations (`.env`)

| Variable Name | Type | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Strategy Injection** | | | |
| `STRATEGY_FILE_PATH` | String | *Required* | Absolute path to the mounted strategy Python file (e.g. `/app/strategies/sma_crossover.py`). |
| `STRATEGY_CLASS_NAME` | String | *Required* | Name of the Python class inside the strategy file (e.g. `SmaCrossoverStrategy`). |
| `STRATEGY_PARAMS_JSON`| String | `{}` | JSON string defining parameters (e.g. `{"fast_period": 10, "slow_period": 30}`). Passed to `strategy.initialize()`. |
| **Market Data Connection** | | | |
| `TICKER` | String | *Required* | Ticker symbol this strategy instance tracks (e.g., `AAPL` or `MSFT`). Injected in `x-ticker` gRPC headers. |
| `TICK_LB_HOST` | String | `tick-lb` | Hostname of the gRPC L7 load balancer. |
| `TICK_LB_PORT` | Integer | `50051` | Port of the gRPC L7 load balancer. |
| **Kafka Event Broker** | | | |
| `KAFKA_BOOTSTRAP_SERVERS`| String| `localhost:9092` | Comma-separated list of Kafka broker addresses. |
| `KAFKA_TOPIC_SIGNALS` | String | `trading-signals` | Kafka topic where strategy-triggered trade signals are published. |
| **Logging & Diagnostics** | | | |
| `LOG_LEVEL` | String | `INFO` | Logging output level (`DEBUG`, `INFO`, `WARNING`, `ERROR`). |

---

## 2. Configuration Parser Schema (Python)

Below is the python validation script (`config.py`) executing verification checks during boot-up:

```python
import os
import json
import logging
from dotenv import load_dotenv

load_dotenv()
logger = logging.getLogger("Config")

# Strategy settings
STRATEGY_FILE_PATH = os.getenv("STRATEGY_FILE_PATH")
STRATEGY_CLASS_NAME = os.getenv("STRATEGY_CLASS_NAME")
STRATEGY_PARAMS_RAW = os.getenv("STRATEGY_PARAMS_JSON", "{}")

if not STRATEGY_FILE_PATH or not STRATEGY_CLASS_NAME:
    raise ValueError("CRITICAL: STRATEGY_FILE_PATH and STRATEGY_CLASS_NAME must be specified in the environment.")

try:
    STRATEGY_PARAMS = json.loads(STRATEGY_PARAMS_RAW)
except json.JSONDecodeError as e:
    raise ValueError(f"CRITICAL: STRATEGY_PARAMS_JSON is not a valid JSON string: {e}")

# Target Ticker
TICKER = os.getenv("TICKER")
if not TICKER:
    raise ValueError("CRITICAL: TICKER environment variable is required.")

# Load Balancer connection
TICK_LB_HOST = os.getenv("TICK_LB_HOST", "tick-lb")
TICK_LB_PORT = int(os.getenv("TICK_LB_PORT", "50051"))

# Kafka broker
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
KAFKA_TOPIC_SIGNALS = os.getenv("KAFKA_TOPIC_SIGNALS", "trading-signals")

# Logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(level=LOG_LEVEL, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
```

---

## 3. Deployment Configuration in Kubernetes (Helm)

The deployment configuration varies depending on the strategy execution phase.

### Phase 1: Pre-packaged Strategy Execution (Current Phase)
No volume mounts or external ConfigMaps are required. The deployment simply specifies the environment paths referencing the strategies pre-packaged in the image:

```yaml
spec:
  containers:
  - name: signal-gen-aapl
    image: trading-poc/signal-generator:latest
    env:
      # Reference pre-packaged strategy file inside the image
      - name: STRATEGY_FILE_PATH
        value: "/app/strategies/sma_crossover.py"
      - name: STRATEGY_CLASS_NAME
        value: "SmaCrossoverStrategy"
      - name: STRATEGY_PARAMS_JSON
        value: '{"fast": 5, "slow": 20}'
      - name: TICKER
        value: "AAPL"
```

---

### Phase 2: External Mounting & Dynamic Reloading (Future Phase)
To inject custom strategies at runtime without rebuilding the image, we mount a ConfigMap containing the strategy script file:

#### A. ConfigMap defining Strategy files:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: trading-strategies
data:
  sma_crossover.py: |
    from base_strategy import BaseStrategy
    import logging

    logger = logging.getLogger("SmaCrossover")

    class SmaCrossoverStrategy(BaseStrategy):
        def initialize(self, parameters):
            self.fast_window = parameters.get("fast", 10)
            self.slow_window = parameters.get("slow", 30)
            self.prices = []

        def on_bar(self, bar):
            self.prices.append(bar["close"])
            if len(self.prices) > self.slow_window:
                self.prices.pop(0)
                return {"symbol": bar["symbol"], "action": "BUY", "qty": 100}
            return None
```

#### B. Pod Volume Mount Spec:
Mount the ConfigMap to override the `/app/strategies/` directory:
```yaml
spec:
  containers:
  - name: signal-gen-aapl
    image: trading-poc/signal-generator:latest
    env:
      - name: STRATEGY_FILE_PATH
        value: "/app/strategies/sma_crossover.py"
      - name: STRATEGY_CLASS_NAME
        value: "SmaCrossoverStrategy"
      - name: STRATEGY_PARAMS_JSON
        value: '{"fast": 5, "slow": 20}'
      - name: TICKER
        value: "AAPL"
    volumeMounts:
    - name: strategies-volume
      mountPath: /app/strategies
  volumes:
  - name: strategies-volume
    configMap:
      name: trading-strategies
```
This guarantees that strategy scripts can be dynamically loaded or hot-swapped at runtime.
