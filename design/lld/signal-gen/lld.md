# Signal Generator (signal-gen) Low-Level Design (LLD)

This document specifies the internal class architecture, dynamic strategy loading mechanism, and data execution flows for the pluggable `signal-gen` microservice.

---

## 1. Architectural Overview & Pluggable Strategy Strategy

The core requirement is to maintain a **single base docker image** for the signal generator while allowing different ticker-specific strategies (e.g., AAPL SMA Crossover, MSFT Mean Reversion) to be resolved dynamically. This will be implemented in two distinct phases:

### Phase 1: Pre-packaged Strategy Resolution (Current Phase)
*   **Self-Contained Bundling**: All production strategy script files are packaged directly inside the Docker image under the `/app/strategies/` directory during the container build process.
*   **Runtime Selection**: On startup, the container reads environment configurations (`STRATEGY_FILE_PATH` pointing to the packaged file, and `STRATEGY_CLASS_NAME`) to dynamically load and register the designated strategy.
*   **Benefits**: Ensures absolute reproducibility, simplifies container staging, and avoids runtime volume mounting risks.

### Phase 2: External Mounting & Dynamic Hot-Reloading (Future Phase)
*   **External Mounting**: Custom strategy files can be mounted from Kubernetes ConfigMaps or external storage volumes to override bundled strategies.
*   **Runtime Reload Trigger**: The container will expose an administrative endpoint (REST/gRPC) or file watcher that re-triggers the dynamic importer. It will re-import the module using `importlib.reload()` and re-instantiate the strategy at runtime without restarting the container process or dropping gRPC price streams.


---

## 2. Module & Class Architecture

```
+---------------------------------------------------------------------------------+
|                             SignalGeneratorServer                               |
|                  (Orchestrates Event Loops & Container Lifespan)                |
+---------------------------------------+-----------------------------------------+
                                        |
       +--------------------------------+--------------------------------+
       |                                                                 |
       v                                                                 v
+-----------------------------+                           +-----------------------------+
|       StrategyLoader        |                           |    gRPCMarketDataClient     |
|  - Dynamically loads .py    |                           |  - Implements gRPC Client   |
|  - Instantiates class       |                           |  - Executes warmup queries  |
|  - Validates interface      |                           |  - Consumes tick stream     |
+--------------+--------------+                           +--------------+--------------+
               |                                                         |
               | (Instantiates)                                          | (Forwards Bar)
               v                                                         v
+-----------------------------+                           +-----------------------------+
|        BaseStrategy         |                           |       StrategyRunner        |
|  - initialize(parameters)   | <-------------------------+  - Coordinates execution    |
|  - on_bar(bar_data)         |     (Calls on_bar)        |  - Invokes custom strategy  |
+--------------+--------------+                           |  - Publishes signals        |
               ^                                          +--------------+--------------+
               | (Inherits)                                              |
      +--------+--------+                                                | (Pushes generated signal)
      |                 |                                                v
+-----+-------+   +-----+-------+                         +-----------------------------+
| SmaCrossover|   |MeanReversion|                         |    KafkaSignalPublisher     |
| (Custom.py) |   | (Custom.py) |                         |  - Publishes signal events  |
+-------------+   +-------------+                         |    to topic: 'signals'      |
                                                          +-----------------------------+
```

### Module Descriptions:

#### 1. `BaseStrategy` (Abstract Base Class)
- **Role**: Interface defining the contract for all trading strategies.
- **Methods**:
  - `initialize(self, parameters: dict) -> None`: Pre-instantiates thresholds, windows, or moving averages.
  - `on_bar(self, bar: dict) -> dict`: Evaluates the new bar data. Returns a signal dictionary if a trade is triggered (e.g. side, qty, type) or `None` if no action is taken.

#### 2. `StrategyLoader`
- **Role**: Dynamic importer.
- **Responsibilities**:
  - Locates the strategy script using the `STRATEGY_FILE_PATH` variable.
  - Loads the module dynamically using Python's `importlib.util` library.
  - Instantiates the class matching `STRATEGY_CLASS_NAME` and asserts it inherits from `BaseStrategy`.

#### 3. `StrategyRunner`
- **Role**: Execution coordinator.
- **Responsibilities**:
  - Holds reference to the instantiated strategy.
  - Feeds incoming gRPC market bars sequentially to `strategy.on_bar()`.
  - Forwards any returned signal dictionary to `KafkaSignalPublisher`.

#### 4. `gRPCMarketDataClient`
- **Role**: gRPC Service Client.
- **Responsibilities**:
  - Establishes a client-side gRPC channel connection to the Envoy Load Balancer (`tick-lb:50051`).
  - **Dynamic Warmup**: Calls `GetHistoricalBars` unary RPC on startup to fetch historical bars, pre-populating the strategy's window without publishing signals.
  - **Live Subscription**: Initiates the `StreamMarketData` server-streaming RPC call to pull live price ticks, feeding them to the strategy runner.

#### 5. `KafkaSignalPublisher`
- **Role**: Event publisher.
- **Responsibilities**:
  - Publishes signals to the Kafka topic `trading-signals`.
  - Stacks the `provider` field on the signal event to preserve execution traceability.

---

## 3. Dynamic Strategy Import Specification

The following code illustrates how `StrategyLoader` resolves and initializes strategies at runtime:

```python
import importlib.util
import os
import inspect
from base_strategy import BaseStrategy

class StrategyLoader:
    @staticmethod
    def load(file_path: str, class_name: str, parameters: dict) -> BaseStrategy:
        """
        Dynamically imports a python file and instantiates the target strategy class.
        """
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Strategy script file not found at: {file_path}")

        # Derive module name and load spec
        module_name = os.path.splitext(os.path.basename(file_path))[0]
        spec = importlib.util.spec_from_file_location(module_name, file_path)
        if spec is None or spec.loader is None:
            raise ImportError(f"Could not load module spec for {file_path}")

        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        # Retrieve and validate target strategy class
        if not hasattr(module, class_name):
            raise AttributeError(f"Module '{module_name}' has no attribute '{class_name}'")

        strategy_class = getattr(module, class_name)
        if not inspect.isclass(strategy_class):
            raise TypeError(f"'{class_name}' is not a valid python class.")

        if not issubclass(strategy_class, BaseStrategy):
            raise TypeError(f"Class '{class_name}' must inherit from BaseStrategy")

        # Instantiate and initialize
        instance = strategy_class()
        instance.initialize(parameters)
        return instance
```
