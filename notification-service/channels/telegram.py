import logging
import httpx

logger = logging.getLogger("TelegramChannel")

async def send_telegram(token: str, chat_id: str, html_text: str) -> bool:
    if not token or not chat_id:
        logger.warning("Telegram token or chat_id not configured. Skipping.")
        return False

    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = {
        "chat_id": chat_id,
        "text": html_text,
        "parse_mode": "HTML",
        "disable_web_page_preview": True,
    }

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload)
            if resp.status_code == 200:
                logger.info("Telegram message dispatched successfully to chat_id=%s", chat_id)
                return True
            else:
                logger.error("Failed to send Telegram message: status=%s, body=%s", resp.status_code, resp.text)
                return False
    except Exception as e:
        logger.error("Exception sending Telegram message: %s", e)
        return False
