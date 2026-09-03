import os
import redis
import logging

logger = logging.getLogger("NotificationConfig")

# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
KAFKA_GROUP_ID = os.getenv("KAFKA_GROUP_ID", "notification-service-group")
TOPIC_ORDER_REJECT = os.getenv("TOPIC_ORDER_REJECT", "order-reject-events")
TOPIC_ORDER_CREATE = os.getenv("TOPIC_ORDER_CREATE", "order-create-events")
TOPIC_ORDER_COMPLETE = os.getenv("TOPIC_ORDER_COMPLETE", "order-complete-events")

# Redis for dynamic configuration overrides
REDIS_HOST = os.getenv("REDIS_HOST", "host.docker.internal")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")

# Channel Defaults from Env
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", "")
TELEGRAM_ENABLED = os.getenv("TELEGRAM_ENABLED", "false").lower() in ("true", "1", "yes")

NTFY_URL = os.getenv("NTFY_URL", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "trading-system-alerts")
NTFY_TOKEN = os.getenv("NTFY_TOKEN", "")
NTFY_ENABLED = os.getenv("NTFY_ENABLED", "false").lower() in ("true", "1", "yes")

EVOLUTION_API_URL = os.getenv("EVOLUTION_API_URL", "http://192.168.29.96:3015").rstrip("/")
EVOLUTION_API_KEY = os.getenv("EVOLUTION_API_KEY", "")
EVOLUTION_INSTANCE = os.getenv("EVOLUTION_INSTANCE", "")
EVOLUTION_RECIPIENT = os.getenv("EVOLUTION_RECIPIENT", "")
EVOLUTION_ENABLED = os.getenv("EVOLUTION_ENABLED", "false").lower() in ("true", "1", "yes")

# Notification Filters (defaults)
NOTIFY_ON_REJECT = os.getenv("NOTIFY_ON_REJECT", "true").lower() in ("true", "1", "yes")
NOTIFY_ON_ORDER_CREATE = os.getenv("NOTIFY_ON_ORDER_CREATE", "false").lower() in ("true", "1", "yes")
NOTIFY_ON_ORDER_FILL = os.getenv("NOTIFY_ON_ORDER_FILL", "true").lower() in ("true", "1", "yes")
NOTIFY_ON_KILL_SWITCH = os.getenv("NOTIFY_ON_KILL_SWITCH", "true").lower() in ("true", "1", "yes")


def get_redis_client():
    try:
        r = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            password=REDIS_PASSWORD,
            socket_timeout=2.0,
            decode_responses=True
        )
        r.ping()
        return r
    except Exception as e:
        logger.debug("Redis connection unavailable: %s", e)
        return None


def get_active_config(r=None):
    """
    Retrieves the active configuration, merging Redis dynamic overrides over env vars.
    """
    if r is None:
        r = get_redis_client()

    cfg = {
        # Telegram
        "telegram_token": TELEGRAM_BOT_TOKEN,
        "telegram_chat_id": TELEGRAM_CHAT_ID,
        "telegram_enabled": TELEGRAM_ENABLED,
        # ntfy
        "ntfy_url": NTFY_URL,
        "ntfy_topic": NTFY_TOPIC,
        "ntfy_token": NTFY_TOKEN,
        "ntfy_enabled": NTFY_ENABLED,
        # Evolution API
        "evolution_url": EVOLUTION_API_URL,
        "evolution_apikey": EVOLUTION_API_KEY,
        "evolution_instance": EVOLUTION_INSTANCE,
        "evolution_recipient": EVOLUTION_RECIPIENT,
        "evolution_enabled": EVOLUTION_ENABLED,
        # Event Filters
        "notify_on_reject": NOTIFY_ON_REJECT,
        "notify_on_order_create": NOTIFY_ON_ORDER_CREATE,
        "notify_on_order_fill": NOTIFY_ON_ORDER_FILL,
        "notify_on_kill_switch": NOTIFY_ON_KILL_SWITCH,
    }

    if r:
        try:
            # Telegram overrides
            if r.exists("notify:config:telegram:token"):
                val = r.get("notify:config:telegram:token")
                if val: cfg["telegram_token"] = val
            if r.exists("notify:config:telegram:chat_id"):
                val = r.get("notify:config:telegram:chat_id")
                if val: cfg["telegram_chat_id"] = val
            if r.exists("notify:config:telegram:enabled"):
                val = r.get("notify:config:telegram:enabled")
                cfg["telegram_enabled"] = val.lower() in ("true", "1", "yes")

            # ntfy overrides
            if r.exists("notify:config:ntfy:url"):
                val = r.get("notify:config:ntfy:url")
                if val: cfg["ntfy_url"] = val.rstrip("/")
            if r.exists("notify:config:ntfy:topic"):
                val = r.get("notify:config:ntfy:topic")
                if val: cfg["ntfy_topic"] = val
            if r.exists("notify:config:ntfy:token"):
                val = r.get("notify:config:ntfy:token")
                if val: cfg["ntfy_token"] = val
            if r.exists("notify:config:ntfy:enabled"):
                val = r.get("notify:config:ntfy:enabled")
                cfg["ntfy_enabled"] = val.lower() in ("true", "1", "yes")

            # Evolution API overrides
            if r.exists("notify:config:evolution:url"):
                val = r.get("notify:config:evolution:url")
                if val: cfg["evolution_url"] = val.rstrip("/")
            if r.exists("notify:config:evolution:apikey"):
                val = r.get("notify:config:evolution:apikey")
                if val: cfg["evolution_apikey"] = val
            if r.exists("notify:config:evolution:instance"):
                val = r.get("notify:config:evolution:instance")
                if val: cfg["evolution_instance"] = val
            if r.exists("notify:config:evolution:recipient"):
                val = r.get("notify:config:evolution:recipient")
                if val: cfg["evolution_recipient"] = val
            if r.exists("notify:config:evolution:enabled"):
                val = r.get("notify:config:evolution:enabled")
                cfg["evolution_enabled"] = val.lower() in ("true", "1", "yes")

            # Filter overrides
            if r.exists("notify:config:filter:reject"):
                val = r.get("notify:config:filter:reject")
                cfg["notify_on_reject"] = val.lower() in ("true", "1", "yes")
            if r.exists("notify:config:filter:order_create"):
                val = r.get("notify:config:filter:order_create")
                cfg["notify_on_order_create"] = val.lower() in ("true", "1", "yes")
            if r.exists("notify:config:filter:order_fill"):
                val = r.get("notify:config:filter:order_fill")
                cfg["notify_on_order_fill"] = val.lower() in ("true", "1", "yes")
            if r.exists("notify:config:filter:kill_switch"):
                val = r.get("notify:config:filter:kill_switch")
                cfg["notify_on_kill_switch"] = val.lower() in ("true", "1", "yes")
        except Exception as e:
            logger.error("Error reading dynamic config overrides from Redis: %s", e)

    return cfg
