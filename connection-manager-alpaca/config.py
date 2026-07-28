"""
Configuration module for the Alpaca Connection Manager.

This module loads configurations from environment variables or a local .env file,
performs validation on required Alpaca credentials, cleans URLs, and sets up
basic logging config based on the specified log level.
"""

import os
import logging
from dotenv import load_dotenv

load_dotenv()
logger = logging.getLogger("Config")

# Server binding
HOST = os.getenv("HOST", "0.0.0.0")
PORT_REST = int(os.getenv("PORT_REST", "8000"))
PORT_GRPC = int(os.getenv("PORT_GRPC", "50051"))

# Credentials
ALPACA_API_KEY = os.getenv("ALPACA_API_KEY")
ALPACA_SECRET_KEY = os.getenv("ALPACA_SECRET_KEY")
ALPACA_BASE_URL = os.getenv("ALPACA_BASE_URL", "https://paper-api.alpaca.markets")
ALPACA_DATA_FEED = os.getenv("ALPACA_DATA_FEED", "iex").lower()

if not ALPACA_API_KEY or not ALPACA_SECRET_KEY:
    raise ValueError("CRITICAL: ALPACA_API_KEY and ALPACA_SECRET_KEY must be populated in the environment.")

# Clean base URL (strip trailing v2 for SDK compliance)
if ALPACA_BASE_URL.endswith("/v2"):
    ALPACA_BASE_URL = ALPACA_BASE_URL[:-3]
elif ALPACA_BASE_URL.endswith("/v2/"):
    ALPACA_BASE_URL = ALPACA_BASE_URL[:-4]

# Kafka configuration
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
KAFKA_TOPIC_RAW_ORDER_UPDATES = os.getenv("KAFKA_TOPIC_RAW_ORDER_UPDATES", "raw-order-updates")

# Tickers & Streams
TICKERS_TO_TRACK = [t.strip() for t in os.getenv("TICKERS_TO_TRACK", "AAPL,MSFT").split(",") if t.strip()]
DEFAULT_TIMEFRAME = os.getenv("DEFAULT_TIMEFRAME", "1Min")

# Logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(level=LOG_LEVEL, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
