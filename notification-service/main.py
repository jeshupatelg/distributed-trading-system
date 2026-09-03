import logging
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, Dict, Any

import config
import formatter
from consumer import NotificationConsumer
from channels.telegram import send_telegram
from channels.ntfy import send_ntfy
from channels.evolution_api import send_evolution_whatsapp

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("NotificationService")

consumer_instance = None
consumer_task = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global consumer_instance, consumer_task
    logger.info("Starting Notification Service...")
    consumer_instance = NotificationConsumer()
    consumer_task = asyncio.create_task(consumer_instance.start())
    yield
    logger.info("Shutting down Notification Service...")
    if consumer_instance:
        consumer_instance.stop()
    if consumer_task:
        consumer_task.cancel()


app = FastAPI(
    title="Quant Notification Service",
    version="1.0.0",
    description="Multi-channel notification dispatcher for trade executions, risk rejections, and system alerts.",
    lifespan=lifespan
)


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "service": "notification-service",
        "kafka_connected": consumer_instance.running if consumer_instance else False
    }


def _mask(s: str) -> str:
    if not s:
        return ""
    if len(s) <= 6:
        return "******"
    return s[:3] + "..." + s[-3:]


@app.get("/api/v1/notify/status")
def get_status():
    r = config.get_redis_client()
    cfg = config.get_active_config(r)
    return {
        "channels": {
            "telegram": {
                "enabled": cfg["telegram_enabled"],
                "chat_id": cfg["telegram_chat_id"],
                "token_configured": bool(cfg["telegram_token"]),
                "token_preview": _mask(cfg["telegram_token"])
            },
            "ntfy": {
                "enabled": cfg["ntfy_enabled"],
                "url": cfg["ntfy_url"],
                "topic": cfg["ntfy_topic"]
            },
            "evolution": {
                "enabled": cfg["evolution_enabled"],
                "url": cfg["evolution_url"],
                "instance": cfg["evolution_instance"],
                "recipient": cfg["evolution_recipient"],
                "apikey_configured": bool(cfg["evolution_apikey"]),
                "apikey_preview": _mask(cfg["evolution_apikey"])
            }
        },
        "filters": {
            "notify_on_reject": cfg["notify_on_reject"],
            "notify_on_order_create": cfg["notify_on_order_create"],
            "notify_on_order_fill": cfg["notify_on_order_fill"],
            "notify_on_kill_switch": cfg["notify_on_kill_switch"]
        }
    }


class TestNotificationRequest(BaseModel):
    channel: Optional[str] = "all"  # "all", "telegram", "ntfy", "evolution"
    event_type: Optional[str] = "reject"  # "reject" or "fill"
    symbol: Optional[str] = "AAPL"
    qty: Optional[int] = 100
    price: Optional[float] = 260.00
    gate: Optional[str] = "PRICE_COLLAR"
    reason: Optional[str] = "PRICE_COLLAR_VIOLATION (13.04% > 2.0%)"


@app.post("/api/v1/notify/test")
async def send_test_notification(req: TestNotificationRequest):
    r = config.get_redis_client()
    cfg = config.get_active_config(r)

    mock_data = {
        "orderId": "test_ord_8f102c4b",
        "symbol": req.symbol,
        "qty": req.qty,
        "side": "BUY",
        "price": req.price,
        "estimatedCost": req.price * req.qty,
        "provider": "alpaca",
        "strategy": "SmaCrossover",
        "rejectReason": req.reason,
        "riskGateLevel": req.gate,
        "status": "COMPLETED",
        "filledQty": req.qty,
        "filledAvgPrice": req.price,
        "timestamp": None
    }

    results = {}

    # 1. Telegram
    if req.channel in ("all", "telegram"):
        if not cfg["telegram_token"] or not cfg["telegram_chat_id"]:
            results["telegram"] = {"success": False, "error": "Telegram token or chat_id not configured"}
        else:
            text = (formatter.format_reject_telegram(mock_data) 
                    if req.event_type == "reject" 
                    else formatter.format_complete_telegram(mock_data))
            success = await send_telegram(cfg["telegram_token"], cfg["telegram_chat_id"], text)
            results["telegram"] = {"success": success}

    # 2. ntfy
    if req.channel in ("all", "ntfy"):
        if not cfg["ntfy_url"] or not cfg["ntfy_topic"]:
            results["ntfy"] = {"success": False, "error": "ntfy url or topic not configured"}
        else:
            if req.event_type == "reject":
                title, body = formatter.format_reject_ntfy(mock_data)
                priority = "urgent"
                tags = ["warning", "rotating_light"]
            else:
                title, body = formatter.format_complete_ntfy(mock_data)
                priority = "high"
                tags = ["white_check_mark"]
            success = await send_ntfy(cfg["ntfy_url"], cfg["ntfy_topic"], title, body, priority=priority, tags=tags)
            results["ntfy"] = {"success": success}

    # 3. Evolution API
    if req.channel in ("all", "evolution"):
        if not cfg["evolution_url"] or not cfg["evolution_instance"] or not cfg["evolution_recipient"]:
            results["evolution"] = {"success": False, "error": "Evolution API url, instance or recipient not configured"}
        else:
            text = (formatter.format_reject_whatsapp(mock_data) 
                    if req.event_type == "reject" 
                    else formatter.format_complete_whatsapp(mock_data))
            success = await send_evolution_whatsapp(
                cfg["evolution_url"],
                cfg["evolution_apikey"],
                cfg["evolution_instance"],
                cfg["evolution_recipient"],
                text
            )
            results["evolution"] = {"success": success}

    return {"status": "dispatched", "channel_results": results}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8085)
