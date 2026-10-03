import os
import logging
from typing import Dict, Tuple

logger = logging.getLogger("PriceCacheConfig")

# Throttling & Batching Configurations
# 1. Provider-scoped price write (primary, smaller bucket, more frequent writes)
PROVIDER_FLUSH_INTERVAL_SEC = float(os.getenv("PROVIDER_FLUSH_INTERVAL_SEC", "0.1"))
PROVIDER_MAX_BATCH_SIZE = int(os.getenv("PROVIDER_MAX_BATCH_SIZE", "20"))

# 2. Symbol-scoped backup price write (backup, larger bucket, less frequent writes)
SYMBOL_FLUSH_INTERVAL_SEC = float(os.getenv("SYMBOL_FLUSH_INTERVAL_SEC", os.getenv("FLUSH_INTERVAL_SEC", "0.5")))
SYMBOL_MAX_BATCH_SIZE = int(os.getenv("SYMBOL_MAX_BATCH_SIZE", os.getenv("MAX_BATCH_SIZE", "100")))

# Backward compatibility aliases
FLUSH_INTERVAL_SEC = SYMBOL_FLUSH_INTERVAL_SEC
MAX_BATCH_SIZE = SYMBOL_MAX_BATCH_SIZE

# Price TTL Configuration (Default: 1.0s, configurable via env; docker-compose sets 60s for paper feeds)
PRICE_TTL_SEC = float(os.getenv("PRICE_TTL_SEC", "1.0"))
PROVIDER_PRICE_TTL_SEC = float(os.getenv("PROVIDER_PRICE_TTL_SEC", str(PRICE_TTL_SEC)))
SYMBOL_PRICE_TTL_SEC = float(os.getenv("SYMBOL_PRICE_TTL_SEC", str(PRICE_TTL_SEC)))

# Pre-computed Key Patterns
# Supports pattern replacement ({pattern}), symbol replacement ({symbol}), and both replacement ({provider}, {symbol})
BASE_PRICE_KEY_PATTERN = os.getenv("BASE_PRICE_KEY_PATTERN", "market:last_price")
PROVIDER_PRICE_KEY_PATTERN = os.getenv("PROVIDER_PRICE_KEY_PATTERN", "{pattern}:{provider}:{symbol}")
SYMBOL_PRICE_KEY_PATTERN = os.getenv("SYMBOL_PRICE_KEY_PATTERN", "{pattern}:{symbol}")

# Redis Storage Configuration
REDIS_HOST = os.getenv("REDIS_HOST", "homeserver-redis")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")

# Telemetry & Health Probe Ports
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "8080"))


class KeyPatternResolver:
    """
    Resolves canonical Redis market price keys using pre-computed templates.
    Supports:
    - Pattern replacement: replaces '{pattern}' or '{prefix}' with the base pattern (e.g. 'market:last_price')
    - Symbol replacement: replaces '{symbol}' with the uppercase ticker
    - Both replacement: replaces '{provider}' with lowercase provider and '{symbol}' with uppercase ticker
    Maintains pre-computed in-memory caches to guarantee sub-nanosecond lookups on high-frequency ticks.
    """
    def __init__(
        self,
        base_pattern: str = BASE_PRICE_KEY_PATTERN,
        provider_pattern: str = PROVIDER_PRICE_KEY_PATTERN,
        symbol_pattern: str = SYMBOL_PRICE_KEY_PATTERN
    ):
        clean_base = base_pattern.rstrip(":")
        
        # 1. Pattern replacement
        self.base_pattern = clean_base
        self.provider_pattern = (
            provider_pattern
            .replace("{pattern}", clean_base)
            .replace("{prefix}", clean_base)
        )
        self.symbol_pattern = (
            symbol_pattern
            .replace("{pattern}", clean_base)
            .replace("{prefix}", clean_base)
        )
        
        # Pre-computed key caches: (provider, symbol) -> key, (symbol) -> key
        self._provider_cache: Dict[Tuple[str, str], str] = {}
        self._symbol_cache: Dict[str, str] = {}

    def get_provider_key(self, provider: str, symbol: str) -> str:
        """
        'Both' replacement: resolves provider-scoped key using both provider and symbol.
        """
        norm_prov = (provider or "default").lower().strip()
        norm_sym = (symbol or "").upper().strip()
        cache_key = (norm_prov, norm_sym)

        cached = self._provider_cache.get(cache_key)
        if cached is not None:
            return cached

        # Perform both replacement
        pat = self.provider_pattern
        if "{provider}" in pat or "{symbol}" in pat:
            key = pat.format(provider=norm_prov, symbol=norm_sym)
        elif "%s" in pat:
            key = pat % (norm_prov, norm_sym)
        else:
            key = f"{pat}:{norm_prov}:{norm_sym}"

        self._provider_cache[cache_key] = key
        return key

    def get_symbol_key(self, symbol: str) -> str:
        """
        'Symbol' replacement: resolves symbol-scoped backup key using symbol.
        """
        norm_sym = (symbol or "").upper().strip()
        cached = self._symbol_cache.get(norm_sym)
        if cached is not None:
            return cached

        # Perform symbol replacement
        pat = self.symbol_pattern
        if "{symbol}" in pat:
            key = pat.format(symbol=norm_sym)
        elif "%s" in pat:
            key = pat % (norm_sym,)
        else:
            key = f"{pat}:{norm_sym}"

        self._symbol_cache[norm_sym] = key
        return key


def discover_provider_endpoints() -> dict[str, str]:
    """
    Discovers all configured broker Connection Manager endpoints using the same
    PROVIDER_<NAME>_ENDPOINT convention as OPS and OMS.
    """
    providers = {}
    for key, val in os.environ.items():
        if key.startswith("PROVIDER_") and key.endswith("_ENDPOINT") and key != "PROVIDER_DEFAULT_ENDPOINT":
            provider_name = key[len("PROVIDER_"): -len("_ENDPOINT")].lower()
            providers[provider_name] = val

    # Fallback to default endpoint if no specific provider endpoints were explicitly defined
    if not providers:
        default_ep = os.getenv("PROVIDER_DEFAULT_ENDPOINT", os.getenv("PROVIDER_ALPACA_ENDPOINT", "connection-manager-alpaca:50051"))
        providers["alpaca"] = default_ep

    logger.info("Discovered broker provider endpoints: %s", providers)
    return providers
