# Connection Manager (Alpaca) Low-Level Design (LLD)

This document specifies the internal class architecture, thread orchestration, and interface definitions for the `connection-manager-alpaca` component.

---

## 1. System Responsibilities
1.  **Session & Credential Isolation**: The sole component containing the Alpaca developer credentials and active connection key.
2.  **Stateless Proxying**: Translates incoming gRPC Unary execution commands (Place Order, Query Order) into Alpaca REST operations.
3.  **Live Price Tick Broadcasting**: Connects to the Alpaca WebSocket stream and routes raw bar events to subscribing consumers via gRPC Server Streaming.
4.  **Live Order State Forwarding**: Listens to the Alpaca WebSocket stream for order fills, and immediately forwards raw updates to a Kafka topic for processing by the Combined Order Service (COS).

---

## 2. Interface Definitions (gRPC Protobuf Schema)

The communication contract is defined in the shared configuration file [connection_manager.proto](file:///c:/Users/jeshu/Projects/distributed-trading-system/design/lld/connection-manager-alpaca/connection_manager.proto). This file is mounted externally across target services (Python and Java JVM containers) using Docker volumes or Kubernetes ConfigMaps at compile-time/runtime.

---

## 3. Class & Module Architecture

```
+---------------------------------------------------------------------------------+
|                              ConnectionManagerServer                            |
|                  (Orchestrates Asyncio Event Loops & Ports)                     |
+---------------------------------------+-----------------------------------------+
                                        |
       +--------------------------------+--------------------------------+
       |                                                                 |
       v                                                                 v
+-----------------------------+                           +-----------------------------+
|     AlpacaStreamClient      |                           |      AlpacaRestClient       |
|  - StockDataStream Client   |                           |  - TradingClient Wrapper    |
|  - handles WS lifecycle     |                           |  - executes REST HTTP       |
+--------------+--------------+                           +--------------+--------------+
               |                                                         ^
               | (WebSocket Callback)                                    |
               v                                                         |
+-----------------------------+                           +--------------+--------------+
|        StreamHandler        |                           |       OrderController       |
|  - processes JSON ticks     |                           |  - handles gRPC/REST APIs   |
|  - triggers Kafka/gRPC      |                           |  - forwards payloads        |
+-------+--------------+------+                           +-----------------------------+
        |              |
        |              | (Forward tick)
        |              v
        |       +-----------------------------+
        |       |    gRPCStreamBroadcaster    |
        |       |  - manages active gRPC stream|
        |       |    clients & sends packages |
        |       +-----------------------------+
        |
        v (Order fills)
+-----------------------------+
|    KafkaEventPublisher      |
|  - pushes raw events to     |
|    topic: raw-order-updates |
+-----------------------------+
```

### Module Descriptions:

#### 1. `ConnectionManagerServer`
*   **Role**: Entry point process runner.
*   **Responsibilities**:
    *   Starts the FastAPI application (Port 8000) for basic `/health` probes.
    *   Starts the gRPC service server (Port 50051).
    *   Initializes the `AlpacaStreamClient`.
    *   Schedules tasks inside Python's `asyncio` event loop.

#### 2. `AlpacaStreamClient`
*   **Role**: Wrapper for `StockDataStream` (Alpaca WebSocket client).
*   **Responsibilities**:
    *   Maintains the active socket connection loop.
    *   Subscribes to raw tick feeds (`subscribe_bars`).
    *   Routes raw price messages to `StreamHandler`.
    *   Listens to trade/order completion feeds and routes events to `KafkaEventPublisher`.

#### 3. `AlpacaRestClient`
*   **Role**: Thin wrapper around Alpaca `TradingClient`.
*   **Responsibilities**:
    *   Instantiates the Alpaca REST Client using base credentials.
    *   Exposes thread-safe interfaces for `submit_order`, `get_orders`, and `get_open_position`.

#### 4. `KafkaEventPublisher`
*   **Role**: Wrapper for `KafkaProducer`.
*   **Responsibilities**:
    *   Maintains connectivity to Kafka brokers.
    *   Pushes raw WebSocket order statuses immediately to the `raw-order-updates` topic.

---

## 4. Multi-Threading & Concurrency Design

Because Python is subject to the **Global Interpreter Lock (GIL)**, CPU-bound multi-threading is limited. Therefore, `connection-manager-alpaca` relies on an **asynchronous, event-driven I/O model**:

1.  **Single-Threaded Event Loop (`asyncio`)**:
    *   The primary execution thread runs a single `asyncio` loop that coordinates the `FastAPI` server, the `gRPC` server, and the `AlpacaStreamClient` WebSocket reader.
    *   Network socket reads/writes yield execution control back to the loop (`await`), allowing thousands of concurrent ticks to flow without blocking.
2.  **REST ThreadPool Offloading**:
    *   Alpaca REST client requests (`TradingClient.submit_order`) are blocking synchronous I/O operations.
    *   To prevent a REST call from locking the entire event loop (which would freeze live market data streams), the `OrderController` runs these blocking REST methods inside a `concurrent.futures.ThreadPoolExecutor` using:
        ```python
        loop = asyncio.get_running_loop()
        order_response = await loop.run_in_executor(
            thread_pool_executor, 
            trading_client.submit_order, 
            order_payload
        )
        ```
