import asyncio
import logging
import os
import signal
import sys
import time
from contextlib import asynccontextmanager

import grpc
import redis
from fastapi import FastAPI
import uvicorn
from prometheus_client import Counter, Gauge, make_asgi_app

import config
import connection_manager_pb2
import connection_manager_pb2_grpc

# Logging Setup
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("PriceCacheService")

# Telemetry Metrics
TICKS_RECEIVED = Counter("price_cache_ticks_received_total", "Total ticks received from connection managers", ["provider"])
FLUSH_COUNT = Counter("price_cache_flush_total", "Total Redis micro-batch flush operations", ["bucket"])
FLUSH_KEYS_UPDATED = Counter("price_cache_keys_updated_total", "Total price keys updated in Redis", ["bucket"])
ACTIVE_PROVIDERS = Gauge("price_cache_active_providers", "Number of active gRPC stream connections")


class PriceBucket:
    """
    In-memory buffer and drain controller for a specific price key scope.
    """
    def __init__(self, name: str, flush_interval_sec: float, max_batch_size: int, ttl_sec: float):
        self.name = name
        self.flush_interval_sec = flush_interval_sec
        self.max_batch_size = max_batch_size
        self.ttl_sec = float(ttl_sec)
        self.buffer: dict[str, str] = {}
        self.lock = asyncio.Lock()

    async def update(self, key: str, price: str):
        """Updates in-memory buffer with sub-nanosecond RAM write."""
        async with self.lock:
            self.buffer[key] = price

    async def drain_snapshot(self) -> dict[str, str] | None:
        """Atomically drains up to max_batch_size entries from the buffer."""
        async with self.lock:
            if not self.buffer:
                return None
            if len(self.buffer) <= self.max_batch_size:
                snapshot = self.buffer.copy()
                self.buffer.clear()
            else:
                keys_to_drain = list(self.buffer.keys())[:self.max_batch_size]
                snapshot = {k: self.buffer[k] for k in keys_to_drain}
                for k in keys_to_drain:
                    del self.buffer[k]
            return snapshot


class PriceCacheEngine:
    """
    Core engine managing dual-bucket in-memory buffering (provider-scoped primary and symbol-scoped backup),
    micro-batching, and pipelined Redis flushes with configurable TTL.
    """
    def __init__(self):
        self.redis_client = None
        self.running = True

        self.key_resolver = config.KeyPatternResolver(
            base_pattern=config.BASE_PRICE_KEY_PATTERN,
            provider_pattern=config.PROVIDER_PRICE_KEY_PATTERN,
            symbol_pattern=config.SYMBOL_PRICE_KEY_PATTERN
        )

        # 1. Provider-scoped primary bucket (smaller bucket, more frequent writes)
        self.provider_bucket = PriceBucket(
            name="provider",
            flush_interval_sec=config.PROVIDER_FLUSH_INTERVAL_SEC,
            max_batch_size=config.PROVIDER_MAX_BATCH_SIZE,
            ttl_sec=config.PROVIDER_PRICE_TTL_SEC
        )

        # 2. Symbol-scoped backup bucket (larger bucket, less frequent writes)
        self.symbol_bucket = PriceBucket(
            name="symbol",
            flush_interval_sec=config.SYMBOL_FLUSH_INTERVAL_SEC,
            max_batch_size=config.SYMBOL_MAX_BATCH_SIZE,
            ttl_sec=config.SYMBOL_PRICE_TTL_SEC
        )

    def init_redis(self):
        logger.info("Initializing Redis connection to %s:%s...", config.REDIS_HOST, config.REDIS_PORT)
        try:
            self.redis_client = redis.Redis(
                host=config.REDIS_HOST,
                port=config.REDIS_PORT,
                password=config.REDIS_PASSWORD if config.REDIS_PASSWORD else None,
                decode_responses=True,
                socket_timeout=5
            )
            self.redis_client.ping()
            logger.info("Successfully connected to Redis cache instance.")
        except Exception as e:
            logger.warning("Initial Redis ping failed (%s). Will retry lazily during flushes.", e)

    async def update_price(self, symbol: str, price: float, provider: str):
        """
        Updates the in-memory buffer with the latest price for a symbol across both
        provider-scoped primary and symbol-scoped backup buckets.
        """
        norm_prov = (provider or "unknown").lower().strip()
        TICKS_RECEIVED.labels(provider=norm_prov).inc()
        price_str = str(price)

        # 1. Provider-scoped primary key (both replacement)
        provider_key = self.key_resolver.get_provider_key(norm_prov, symbol)
        await self.provider_bucket.update(provider_key, price_str)

        # 2. Symbol-scoped backup key (symbol replacement)
        symbol_key = self.key_resolver.get_symbol_key(symbol)
        await self.symbol_bucket.update(symbol_key, price_str)

    async def flush_loop(self, bucket: PriceBucket):
        """
        Background periodic task that flushes buffered price snapshots for a bucket to Redis.
        """
        logger.info(
            "Starting Redis micro-batch flush loop for bucket '%s' (interval=%.2fs, max_batch=%d, ttl=%.2fs)...",
            bucket.name, bucket.flush_interval_sec, bucket.max_batch_size, bucket.ttl_sec
        )
        while self.running:
            await asyncio.sleep(bucket.flush_interval_sec)
            snapshot = await bucket.drain_snapshot()
            if snapshot:
                self._execute_flush(bucket.name, snapshot, bucket.ttl_sec)

    def _execute_flush(self, bucket_name: str, snapshot: dict[str, str], ttl_sec: float):
        """Executes pipelined write with millisecond-granularity (px) or second-granularity (ex) TTL to Redis."""
        if not self.redis_client:
            self.init_redis()
            if not self.redis_client:
                logger.error("Skipping Redis flush for bucket '%s': Redis client uninitialized.", bucket_name)
                return

        try:
            # Pipelined batch write with TTL (PX for fractional seconds or EX for integer seconds)
            pipe = self.redis_client.pipeline(transaction=False)
            ttl_ms = int(ttl_sec * 1000) if ttl_sec and ttl_sec > 0 else 0

            for k, v in snapshot.items():
                if ttl_ms > 0:
                    if ttl_ms % 1000 == 0:
                        pipe.set(k, v, ex=int(ttl_sec))
                    else:
                        pipe.set(k, v, px=ttl_ms)
                else:
                    pipe.set(k, v)
            pipe.execute()

            FLUSH_COUNT.labels(bucket=bucket_name).inc()
            FLUSH_KEYS_UPDATED.labels(bucket=bucket_name).inc(len(snapshot))
            logger.debug("Flushed %d price keys to Redis (bucket=%s, ttl=%.2fs).", len(snapshot), bucket_name, ttl_sec)
        except Exception as e:
            logger.error("Redis flush error for bucket '%s': %s", bucket_name, e)
            self.redis_client = None  # Force reconnection attempt on next flush


engine = PriceCacheEngine()


async def consume_provider_stream(provider_name: str, endpoint: str):
    """
    Long-lived task subscribing to StreamMarketData on a specific Connection Manager gateway.
    Handles dynamic endpoint connection and automatic reconnect backoffs.
    """
    logger.info("Starting gRPC stream consumer for provider '%s' at endpoint '%s'...", provider_name, endpoint)
    retry_delay = 3.0

    while engine.running:
        try:
            logger.info("Connecting to gRPC endpoint at %s for provider '%s'...", endpoint, provider_name)
            async with grpc.aio.insecure_channel(endpoint) as channel:
                stub = connection_manager_pb2_grpc.MarketDataServiceStub(channel)
                req = connection_manager_pb2.MarketDataRequest(symbols=[])

                stream = stub.StreamMarketData(req)
                ACTIVE_PROVIDERS.inc()
                logger.info("Subscribed to market data stream for provider '%s'. Processing ticks...", provider_name)
                retry_delay = 3.0  # Reset delay on successful connection

                async for bar_proto in stream:
                    if not engine.running:
                        break

                    # Extract symbol and provider from tick
                    raw_symbol = getattr(bar_proto, "symbol", "")
                    raw_provider = getattr(bar_proto, "provider", "") or provider_name
                    close_price = getattr(bar_proto, "close", 0.0)

                    if raw_symbol and close_price > 0:
                        await engine.update_price(
                            symbol=raw_symbol,
                            price=close_price,
                            provider=raw_provider
                        )
        except asyncio.CancelledError:
            logger.info("gRPC stream task for provider '%s' cancelled.", provider_name)
            break
        except Exception as ex:
            ACTIVE_PROVIDERS.dec()
            logger.warning(
                "gRPC stream connection to provider '%s' (%s) lost: %s. Retrying in %.1fs...",
                provider_name, endpoint, ex, retry_delay
            )
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 1.5, 30.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifecycle manager for FastAPI starting background tasks."""
    engine.init_redis()

    # 1. Start background Redis flush loops for both buckets
    provider_flush_task = asyncio.create_task(engine.flush_loop(engine.provider_bucket))
    symbol_flush_task = asyncio.create_task(engine.flush_loop(engine.symbol_bucket))

    # 2. Discover provider endpoints & start gRPC consumers
    providers = config.discover_provider_endpoints()
    provider_tasks = []
    for p_name, p_ep in providers.items():
        t = asyncio.create_task(consume_provider_stream(p_name, p_ep))
        provider_tasks.append(t)

    yield  # Application is running

    # Shutdown logic
    engine.running = False
    provider_flush_task.cancel()
    symbol_flush_task.cancel()
    for t in provider_tasks:
        t.cancel()
    logger.info("Price Cache Service shutdown complete.")


# FastAPI Setup
app = FastAPI(title="Price Cache Service", lifespan=lifespan)
metrics_app = make_asgi_app()
app.mount("/metrics", metrics_app)


@app.get("/health")
async def health_check():
    redis_status = "ok" if engine.redis_client is not None else "disconnected"
    return {
        "status": "healthy",
        "service": "price-cache-service",
        "redis_status": redis_status,
        "ttl_sec": config.PRICE_TTL_SEC,
        "provider_bucket": {
            "flush_interval_sec": config.PROVIDER_FLUSH_INTERVAL_SEC,
            "max_batch_size": config.PROVIDER_MAX_BATCH_SIZE,
            "ttl_sec": config.PROVIDER_PRICE_TTL_SEC
        },
        "symbol_bucket": {
            "flush_interval_sec": config.SYMBOL_FLUSH_INTERVAL_SEC,
            "max_batch_size": config.SYMBOL_MAX_BATCH_SIZE,
            "ttl_sec": config.SYMBOL_PRICE_TTL_SEC
        },
        # Backward compatibility
        "flush_interval_sec": config.FLUSH_INTERVAL_SEC,
        "max_batch_size": config.MAX_BATCH_SIZE
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=config.SERVICE_PORT)
