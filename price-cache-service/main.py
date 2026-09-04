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
FLUSH_COUNT = Counter("price_cache_flush_total", "Total Redis micro-batch flush operations")
FLUSH_KEYS_UPDATED = Counter("price_cache_keys_updated_total", "Total price keys updated in Redis")
ACTIVE_PROVIDERS = Gauge("price_cache_active_providers", "Number of active gRPC stream connections")

class PriceCacheEngine:
    """
    Core engine managing in-memory tick buffering, micro-batching, and pipelined Redis flushes.
    """
    def __init__(self):
        self.redis_client = None
        self.buffer = {}
        self.lock = asyncio.Lock()
        self.running = True

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
        Updates the in-memory buffer with the latest price for a symbol. Sub-nanosecond RAM write.
        """
        TICKS_RECEIVED.labels(provider=provider).inc()
        async with self.lock:
            key = f"{config.PRICE_KEY_PREFIX}{symbol.upper()}"
            self.buffer[key] = str(price)

    async def flush_loop(self):
        """
        Background periodic task that flushes buffered price snapshots to Redis using MSET.
        """
        logger.info(
            "Starting Redis micro-batch flush loop (interval=%.2fs, max_batch=%d)...",
            config.FLUSH_INTERVAL_SEC, config.MAX_BATCH_SIZE
        )
        while self.running:
            await asyncio.sleep(config.FLUSH_INTERVAL_SEC)
            snapshot = None
            async with self.lock:
                if self.buffer:
                    snapshot = self.buffer.copy()
                    self.local_clear_or_drain(config.MAX_BATCH_SIZE)

            if snapshot:
                self._execute_flush(snapshot)

    def local_clear_or_drain(self, max_batch: int):
        """Clears or drains the processed items from buffer."""
        if len(self.buffer) <= max_batch:
            self.buffer.clear()
        else:
            keys_to_remove = list(self.buffer.keys())[:max_batch]
            for k in keys_to_remove:
                del self.buffer[k]

    def _execute_flush(self, snapshot: dict):
        """Executes single pipelined/MSET call to Redis."""
        if not self.redis_client:
            self.init_redis()
            if not self.redis_client:
                logger.error("Skipping Redis flush: Redis client uninitialized.")
                return

        try:
            # MSET writes all key-value pairs in a single atomic payload
            self.redis_client.mset(snapshot)
            FLUSH_COUNT.inc()
            FLUSH_KEYS_UPDATED.inc(len(snapshot))
            logger.debug("Flushed %d price keys to Redis via MSET.", len(snapshot))
        except Exception as e:
            logger.error("Redis MSET flush error: %s", e)
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
                    symbol = bar_proto.symbol
                    close_price = bar_proto.close
                    if symbol and close_price > 0:
                        await engine.update_price(symbol, close_price, provider_name)
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
    
    # 1. Start background Redis flush loop
    flush_task = asyncio.create_task(engine.flush_loop())
    
    # 2. Discover provider endpoints & start gRPC consumers
    providers = config.discover_provider_endpoints()
    provider_tasks = []
    for p_name, p_ep in providers.items():
        t = asyncio.create_task(consume_provider_stream(p_name, p_ep))
        provider_tasks.append(t)

    yield  # Application is running

    # Shutdown logic
    engine.running = False
    flush_task.cancel()
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
        "flush_interval_sec": config.FLUSH_INTERVAL_SEC,
        "max_batch_size": config.MAX_BATCH_SIZE
    }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=config.SERVICE_PORT)
