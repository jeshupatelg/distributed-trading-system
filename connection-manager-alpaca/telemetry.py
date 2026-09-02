from prometheus_client import Counter, Histogram, Gauge
import config

BROKER_CONNECTED = Gauge(
    "connection_manager_broker_connected",
    "Boolean status indicating whether connection manager is connected to broker stream (1 = connected, 0 = disconnected)",
    ["stream_type"]
)

GRPC_ACTIVE_STREAMS = Gauge(
    "connection_manager_grpc_active_streams",
    "Current number of active downstream gRPC streaming clients connected to MarketDataService"
)

TICKS_RECEIVED = Counter(
    "connection_manager_ticks_received_total",
    "Total number of market data price ticks received from broker stream",
    ["ticker"]
)

TICKS_BROADCASTED = Counter(
    "connection_manager_ticks_broadcasted_total",
    "Total number of market data price ticks broadcasted to consumers",
    ["ticker"]
)

TICKS_DROPPED = Counter(
    "connection_manager_ticks_dropped_total",
    "Total number of market data price ticks dropped due to queue overflow or dispatch error",
    ["ticker", "reason"]
)

TICK_PROCESSING_DURATION = Histogram(
    "connection_manager_tick_processing_duration_seconds",
    "Duration in seconds taken to process and dispatch a market data tick to gRPC queues",
    ["ticker"],
    buckets=(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0)
)

TRADE_UPDATES_PUBLISHED = Counter(
    "connection_manager_trade_updates_total",
    "Total number of trade updates received and published to Kafka",
    ["event"]
)

# Pre-initialize metric sample lines so Prometheus exports them immediately with value 0.0 at boot
BROKER_CONNECTED.labels(stream_type="data").set(0)
BROKER_CONNECTED.labels(stream_type="trading").set(0)
GRPC_ACTIVE_STREAMS.set(0)

if hasattr(config, "TICKERS_TO_TRACK") and config.TICKERS_TO_TRACK:
    for ticker_symbol in config.TICKERS_TO_TRACK:
        TICKS_RECEIVED.labels(ticker=ticker_symbol).inc(0)
        TICKS_BROADCASTED.labels(ticker=ticker_symbol).inc(0)
        TICKS_DROPPED.labels(ticker=ticker_symbol, reason="queue_full").inc(0)
        TICKS_DROPPED.labels(ticker=ticker_symbol, reason="dispatch_error").inc(0)
        TICK_PROCESSING_DURATION.labels(ticker=ticker_symbol).observe(0.0005)
