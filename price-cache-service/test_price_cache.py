import asyncio
import sys
import unittest
from unittest.mock import MagicMock, AsyncMock, patch

# Mock third-party packages if running in minimal environment without installed dependencies
for mod in ["redis", "fastapi", "uvicorn", "prometheus_client"]:
    if mod not in sys.modules:
        mock_mod = MagicMock()
        if mod == "prometheus_client":
            mock_mod.Counter.return_value = MagicMock()
            mock_mod.Gauge.return_value = MagicMock()
            mock_mod.make_asgi_app.return_value = MagicMock()
        elif mod == "fastapi":
            mock_mod.FastAPI.return_value = MagicMock()
        sys.modules[mod] = mock_mod

from config import (
    KeyPatternResolver,
    PROVIDER_FLUSH_INTERVAL_SEC,
    PROVIDER_MAX_BATCH_SIZE,
    SYMBOL_FLUSH_INTERVAL_SEC,
    SYMBOL_MAX_BATCH_SIZE,
    PRICE_TTL_SEC
)
from main import PriceBucket, PriceCacheEngine, consume_provider_stream


class TestKeyPatternResolver(unittest.TestCase):
    def test_default_patterns(self):
        resolver = KeyPatternResolver()
        
        # 1. Both replacement
        prov_key = resolver.get_provider_key("alpaca", "aapl")
        self.assertEqual(prov_key, "market:last_price:alpaca:AAPL")

        # 2. Symbol replacement
        sym_key = resolver.get_symbol_key("msft")
        self.assertEqual(sym_key, "market:last_price:MSFT")

    def test_pattern_replacement(self):
        resolver = KeyPatternResolver(
            base_pattern="quotes:feed",
            provider_pattern="{pattern}:{provider}:{symbol}",
            symbol_pattern="{pattern}:{symbol}"
        )
        self.assertEqual(resolver.get_provider_key("alpaca", "NVDA"), "quotes:feed:alpaca:NVDA")
        self.assertEqual(resolver.get_symbol_key("NVDA"), "quotes:feed:NVDA")

    def test_printf_style_replacement(self):
        resolver = KeyPatternResolver(
            base_pattern="market:last_price",
            provider_pattern="market:last_price:%s:%s",
            symbol_pattern="market:last_price:%s"
        )
        self.assertEqual(resolver.get_provider_key("alpaca", "GOOG"), "market:last_price:alpaca:GOOG")
        self.assertEqual(resolver.get_symbol_key("GOOG"), "market:last_price:GOOG")

    def test_cache_hits(self):
        resolver = KeyPatternResolver()
        k1 = resolver.get_provider_key("alpaca", "AAPL")
        self.assertIn(("alpaca", "AAPL"), resolver._provider_cache)
        k2 = resolver.get_provider_key("alpaca", "AAPL")
        self.assertIs(k1, k2)

        s1 = resolver.get_symbol_key("AAPL")
        self.assertIn("AAPL", resolver._symbol_cache)
        s2 = resolver.get_symbol_key("AAPL")
        self.assertIs(s1, s2)


class TestPriceBucket(unittest.IsolatedAsyncioTestCase):
    async def test_update_and_drain_under_max_batch(self):
        bucket = PriceBucket(name="test", flush_interval_sec=0.1, max_batch_size=5, ttl_sec=60)
        await bucket.update("key1", "100.5")
        await bucket.update("key2", "200.5")

        snapshot = await bucket.drain_snapshot()
        self.assertEqual(snapshot, {"key1": "100.5", "key2": "200.5"})
        self.assertEqual(len(bucket.buffer), 0)

        # Subsequent drain when empty returns None
        empty_snapshot = await bucket.drain_snapshot()
        self.assertIsNone(empty_snapshot)

    async def test_drain_over_max_batch(self):
        bucket = PriceBucket(name="test", flush_interval_sec=0.1, max_batch_size=3, ttl_sec=60)
        for i in range(5):
            await bucket.update(f"key{i}", str(i * 10))

        snapshot = await bucket.drain_snapshot()
        self.assertEqual(len(snapshot), 3)
        self.assertEqual(len(bucket.buffer), 2)

        # Drain remainder
        snapshot2 = await bucket.drain_snapshot()
        self.assertEqual(len(snapshot2), 2)
        self.assertEqual(len(bucket.buffer), 0)


class TestPriceCacheEngine(unittest.IsolatedAsyncioTestCase):
    def test_engine_configurations(self):
        engine = PriceCacheEngine()
        self.assertEqual(engine.provider_bucket.flush_interval_sec, PROVIDER_FLUSH_INTERVAL_SEC)
        self.assertEqual(engine.provider_bucket.max_batch_size, PROVIDER_MAX_BATCH_SIZE)
        self.assertEqual(engine.symbol_bucket.flush_interval_sec, SYMBOL_FLUSH_INTERVAL_SEC)
        self.assertEqual(engine.symbol_bucket.max_batch_size, SYMBOL_MAX_BATCH_SIZE)
        self.assertLess(engine.provider_bucket.max_batch_size, engine.symbol_bucket.max_batch_size)
        self.assertLess(engine.provider_bucket.flush_interval_sec, engine.symbol_bucket.flush_interval_sec)

    async def test_dual_bucket_update(self):
        engine = PriceCacheEngine()
        await engine.update_price("AAPL", 150.25, "alpaca")

        # Provider bucket should contain provider key
        self.assertIn("market:last_price:alpaca:AAPL", engine.provider_bucket.buffer)
        self.assertEqual(engine.provider_bucket.buffer["market:last_price:alpaca:AAPL"], "150.25")

        # Symbol bucket should contain symbol key
        self.assertIn("market:last_price:AAPL", engine.symbol_bucket.buffer)
        self.assertEqual(engine.symbol_bucket.buffer["market:last_price:AAPL"], "150.25")

    def test_pipelined_flush_with_ttl(self):
        engine = PriceCacheEngine()
        mock_redis = MagicMock()
        mock_pipeline = MagicMock()
        mock_redis.pipeline.return_value = mock_pipeline
        engine.redis_client = mock_redis

        snapshot = {
            "market:last_price:alpaca:AAPL": "150.25",
            "market:last_price:alpaca:MSFT": "400.50"
        }

        # Integer second TTL uses EX
        engine._execute_flush("provider", snapshot, ttl_sec=1.0)

        mock_redis.pipeline.assert_called_once_with(transaction=False)
        self.assertEqual(mock_pipeline.set.call_count, 2)
        mock_pipeline.set.assert_any_call("market:last_price:alpaca:AAPL", "150.25", ex=1)
        mock_pipeline.set.assert_any_call("market:last_price:alpaca:MSFT", "400.50", ex=1)
        mock_pipeline.execute.assert_called_once()

    def test_pipelined_flush_with_subsecond_px_ttl(self):
        engine = PriceCacheEngine()
        mock_redis = MagicMock()
        mock_pipeline = MagicMock()
        mock_redis.pipeline.return_value = mock_pipeline
        engine.redis_client = mock_redis

        snapshot = {
            "market:last_price:alpaca:AAPL": "150.25"
        }

        # Sub-second fractional TTL uses PX
        engine._execute_flush("provider", snapshot, ttl_sec=0.5)

        mock_redis.pipeline.assert_called_once_with(transaction=False)
        mock_pipeline.set.assert_called_once_with("market:last_price:alpaca:AAPL", "150.25", px=500)
        mock_pipeline.execute.assert_called_once()

    async def test_tick_extraction_with_provider_in_tick(self):
        engine = PriceCacheEngine()
        
        class FakeBar:
            symbol = "TSLA"
            close = 220.5
            provider = "zerodha"

        bar = FakeBar()
        raw_symbol = getattr(bar, "symbol", "")
        raw_provider = getattr(bar, "provider", "") or "alpaca"
        close_price = getattr(bar, "close", 0.0)

        await engine.update_price(raw_symbol, close_price, raw_provider)

        self.assertIn("market:last_price:zerodha:TSLA", engine.provider_bucket.buffer)
        self.assertEqual(engine.provider_bucket.buffer["market:last_price:zerodha:TSLA"], "220.5")
        self.assertIn("market:last_price:TSLA", engine.symbol_bucket.buffer)

    async def test_tick_extraction_fallback_to_stream_provider(self):
        engine = PriceCacheEngine()

        class FakeBar:
            symbol = "META"
            close = 500.0
            provider = ""

        bar = FakeBar()
        raw_symbol = getattr(bar, "symbol", "")
        raw_provider = getattr(bar, "provider", "") or "alpaca"
        close_price = getattr(bar, "close", 0.0)

        await engine.update_price(raw_symbol, close_price, raw_provider)

        self.assertIn("market:last_price:alpaca:META", engine.provider_bucket.buffer)
        self.assertEqual(engine.provider_bucket.buffer["market:last_price:alpaca:META"], "500.0")
        self.assertIn("market:last_price:META", engine.symbol_bucket.buffer)


if __name__ == "__main__":
    unittest.main()
