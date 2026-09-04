import os
import logging

logger = logging.getLogger("PriceCacheConfig")

# Throttling & Batching Configurations
FLUSH_INTERVAL_SEC = float(os.getenv("FLUSH_INTERVAL_SEC", "0.5"))
MAX_BATCH_SIZE = int(os.getenv("MAX_BATCH_SIZE", "100"))
PRICE_KEY_PREFIX = os.getenv("PRICE_KEY_PREFIX", "market:last_price:")

# Redis Storage Configuration
REDIS_HOST = os.getenv("REDIS_HOST", "homeserver-redis")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")

# Telemetry & Health Probe Ports
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "8080"))

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
