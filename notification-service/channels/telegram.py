import logging
import httpx

logger = logging.getLogger("TelegramChannel")

async def send_telegram(token: str, chat_id: str, html_text: str, message_thread_id: int | str | None = None) -> bool:
    if not token or not chat_id:
        logger.warning("Telegram token or chat_id not configured. Skipping.")
        return False

    chat_id_clean = str(chat_id).strip()
    if ":" in chat_id_clean:
        parts = chat_id_clean.split(":", 1)
        chat_id_clean = parts[0].strip()
        if message_thread_id is None or not str(message_thread_id).strip():
            message_thread_id = parts[1].strip()

    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = {
        "chat_id": chat_id_clean,
        "text": html_text,
        "parse_mode": "HTML",
        "disable_web_page_preview": True,
    }

    if message_thread_id is not None and str(message_thread_id).strip():
        try:
            payload["message_thread_id"] = int(str(message_thread_id).strip())
        except (ValueError, TypeError):
            logger.warning("Invalid message_thread_id '%s', omitting from payload.", message_thread_id)

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload)
            if resp.status_code == 200:
                logger.info("Telegram message dispatched successfully to chat_id=%s (thread_id=%s)", chat_id_clean, payload.get("message_thread_id"))
                return True
            else:
                logger.error("Failed to send Telegram message: status=%s, body=%s", resp.status_code, resp.text)
                return False
    except Exception as e:
        logger.error("Exception sending Telegram message: %s", e)
        return False
