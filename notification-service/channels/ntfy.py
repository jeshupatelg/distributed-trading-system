import logging
import httpx

logger = logging.getLogger("NtfyChannel")

async def send_ntfy(server_url: str, topic: str, title: str, markdown_body: str, priority: str = "urgent", tags: list = None, token: str = None) -> bool:
    if not server_url or not topic:
        logger.warning("ntfy server_url or topic not configured. Skipping.")
        return False

    url = f"{server_url.rstrip("/")}/{topic}"
    clean_title = title.encode("ascii", "ignore").decode("ascii").strip() or "Trading System Alert"
    headers = {
        "Title": clean_title,
        "Priority": priority,
        "Markdown": "yes",
    }
    if tags:
        headers["Tags"] = ",".join(tags)
    if token:
        headers["Authorization"] = f"Bearer {token.strip()}"

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, content=markdown_body.encode("utf-8"), headers=headers)
            if resp.status_code == 200:
                logger.info("ntfy alert dispatched successfully to %s/%s", server_url, topic)
                return True
            else:
                logger.error("Failed to send ntfy alert: status=%s, body=%s", resp.status_code, resp.text)
                return False
    except Exception as e:
        logger.error("Exception sending ntfy alert: %s", e)
        return False
