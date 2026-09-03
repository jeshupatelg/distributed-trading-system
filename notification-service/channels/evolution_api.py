import logging
import httpx

logger = logging.getLogger("EvolutionApiChannel")

async def send_evolution_whatsapp(server_url: str, api_key: str, instance: str, recipient_number: str, text: str) -> bool:
    if not server_url or not instance or not recipient_number:
        logger.warning("Evolution API server_url, instance or recipient not configured. Skipping.")
        return False

    url = f"{server_url.rstrip("/")}/message/sendText/{instance}"
    headers = {
        "apikey": api_key,
        "Content-Type": "application/json"
    }
    clean_num = "".join(filter(str.isdigit, recipient_number))
    payload = {
        "number": clean_num,
        "text": text
    }

    try:
        async with httpx.AsyncClient(timeout=7.0) as client:
            resp = await client.post(url, json=payload, headers=headers)
            if resp.status_code in (200, 201):
                logger.info("Evolution WhatsApp message sent successfully to %s on instance %s", clean_num, instance)
                return True
            else:
                logger.error("Failed to send Evolution WhatsApp message: status=%s, body=%s", resp.status_code, resp.text)
                return False
    except Exception as e:
        logger.error("Exception sending Evolution WhatsApp message: %s", e)
        return False
