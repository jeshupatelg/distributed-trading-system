from prometheus_client import Counter
import config

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

TRADE_UPDATES_PUBLISHED = Counter(
    "connection_manager_trade_updates_total",
    "Total number of trade updates received and published to Kafka",
    ["event"]
)

# Pre-initialize metric sample lines so Prometheus exports them immediately with value 0.0 at boot
if hasattr(config, "TICKERS_TO_TRACK") and config.TICKERS_TO_TRACK:
    for ticker_symbol in config.TICKERS_TO_TRACK:
        TICKS_RECEIVED.labels(ticker=ticker_symbol).inc(0)
        TICKS_BROADCASTED.labels(ticker=ticker_symbol).inc(0)
        TICKS_DROPPED.labels(ticker=ticker_symbol, reason="queue_full").inc(0)
        TICKS_DROPPED.labels(ticker=ticker_symbol, reason="dispatch_error").inc(0)
