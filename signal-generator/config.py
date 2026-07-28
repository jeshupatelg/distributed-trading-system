import os
import json
import logging
from dotenv import load_dotenv

load_dotenv()

# Basic setup
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("Config")

# Networking
HOST = os.getenv("HOST", "0.0.0.0")
PORT_GRPC = int(os.getenv("PORT_GRPC", "50051"))
PORT_REST = int(os.getenv("PORT_REST", "8000"))

# Dynamic strategy configuration
STRATEGY_FILE_PATH = os.getenv("STRATEGY_FILE_PATH")
STRATEGY_CLASS_NAME = os.getenv("STRATEGY_CLASS_NAME")
STRATEGY_PARAMS_JSON = os.getenv("STRATEGY_PARAMS_JSON", "{}")

if not STRATEGY_FILE_PATH or not STRATEGY_CLASS_NAME:
    logger.error("Missing STRATEGY_FILE_PATH or STRATEGY_CLASS_NAME in environment.")
    raise ValueError("STRATEGY_FILE_PATH and STRATEGY_CLASS_NAME are required configurations.")

try:
    STRATEGY_PARAMS = json.loads(STRATEGY_PARAMS_JSON)
except json.JSONDecodeError as e:
    logger.error(f"Failed to parse STRATEGY_PARAMS_JSON: {e}")
    raise ValueError(f"STRATEGY_PARAMS_JSON is not a valid JSON string: {e}")

# Trading details
TICKER = os.getenv("TICKER")
if not TICKER:
    logger.error("Missing TICKER environment variable.")
    raise ValueError("TICKER environment variable is required.")

# Connection Manager Endpoint
CONNECTION_MANAGER_ENDPOINT = os.getenv("CONNECTION_MANAGER_ENDPOINT", "connection-manager-alpaca:50051")

# Kafka configurations
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
KAFKA_TOPIC_SIGNALS = os.getenv("KAFKA_TOPIC_SIGNALS", "trading-signals")

logger.info(
    f"Loaded Config: TICKER={TICKER}, strategy_class={STRATEGY_CLASS_NAME}, "
    f"file={STRATEGY_FILE_PATH}, broker_endpoint={CONNECTION_MANAGER_ENDPOINT}, "
    f"kafka_brokers={KAFKA_BOOTSTRAP_SERVERS}"
)
