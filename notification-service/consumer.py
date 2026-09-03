import json
import logging
import asyncio
from concurrent.futures import ThreadPoolExecutor
from confluent_kafka import Consumer, KafkaError

import config
import formatter
from channels.telegram import send_telegram
from channels.ntfy import send_ntfy
from channels.evolution_api import send_evolution_whatsapp

logger = logging.getLogger("NotificationConsumer")


class NotificationConsumer:
    def __init__(self):
        self.running = False
        self.consumer = None
        self.executor = ThreadPoolExecutor(max_workers=2)
        self.redis_client = config.get_redis_client()

    def _init_consumer(self):
        conf = {
            "bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS,
            "group.id": config.KAFKA_GROUP_ID,
            "auto.offset.reset": "latest",
            "enable.auto.commit": True,
        }
        self.consumer = Consumer(conf)
        topics = [
            config.TOPIC_ORDER_REJECT,
            config.TOPIC_ORDER_CREATE,
            config.TOPIC_ORDER_COMPLETE,
        ]
        self.consumer.subscribe(topics)
        logger.info("NotificationConsumer subscribed to Kafka topics: %s", topics)

    async def start(self):
        self.running = True
        self._init_consumer()
        loop = asyncio.get_running_loop()
        logger.info("NotificationConsumer started background poll loop.")

        while self.running:
            try:
                msg = await loop.run_in_executor(self.executor, self._poll_message)
                if msg is not None:
                    await self._handle_message(msg)
            except Exception as e:
                logger.error("Error in consumer poll loop: %s", e)
                await asyncio.sleep(1.0)
            await asyncio.sleep(0.05)

    def _poll_message(self):
        if not self.consumer:
            return None
        return self.consumer.poll(timeout=0.5)

    async def _handle_message(self, msg):
        if msg.error():
            if msg.error().code() != KafkaError._PARTITION_EOF:
                logger.error("Kafka consumer error: %s", msg.error())
            return

        topic = msg.topic()
        try:
            payload = json.loads(msg.value().decode("utf-8"))
        except Exception as e:
            logger.error("Failed to decode Kafka message from topic %s: %s", topic, e)
            return

        # Fetch latest dynamic configuration (merging Redis overrides)
        cfg = config.get_active_config(self.redis_client)

        tasks = []

        # Case 1: Risk Rejection / Failed Order
        if topic == config.TOPIC_ORDER_REJECT:
            if not cfg.get("notify_on_reject", True):
                logger.debug("Risk rejection notifications disabled in config. Skipping.")
                return

            logger.info("Processing Risk Rejection notification for symbol: %s, gate: %s",
                        payload.get("symbol"), payload.get("riskGateLevel"))

            tg_text = formatter.format_reject_telegram(payload)
            ntfy_title, ntfy_body = formatter.format_reject_ntfy(payload)
            wa_text = formatter.format_reject_whatsapp(payload)

            if cfg.get("telegram_enabled") and cfg.get("telegram_token") and cfg.get("telegram_chat_id"):
                tasks.append(send_telegram(cfg["telegram_token"], cfg["telegram_chat_id"], tg_text))

            if cfg.get("ntfy_enabled") and cfg.get("ntfy_url") and cfg.get("ntfy_topic"):
                tasks.append(send_ntfy(cfg["ntfy_url"], cfg["ntfy_topic"], ntfy_title, ntfy_body, priority="urgent", tags=["warning", "rotating_light"]))

            if cfg.get("evolution_enabled") and cfg.get("evolution_url") and cfg.get("evolution_instance") and cfg.get("evolution_recipient"):
                tasks.append(send_evolution_whatsapp(cfg["evolution_url"], cfg["evolution_apikey"], cfg["evolution_instance"], cfg["evolution_recipient"], wa_text))

        # Case 2: Order Created (Dispatched)
        elif topic == config.TOPIC_ORDER_CREATE:
            if not cfg.get("notify_on_order_create", False):
                return

            logger.info("Processing Order Created notification for order: %s", payload.get("orderId"))
            tg_text = formatter.format_create_telegram(payload)
            ntfy_title, ntfy_body = formatter.format_create_ntfy(payload)
            wa_text = formatter.format_create_whatsapp(payload)

            if cfg.get("telegram_enabled") and cfg.get("telegram_token") and cfg.get("telegram_chat_id"):
                tasks.append(send_telegram(cfg["telegram_token"], cfg["telegram_chat_id"], tg_text))

            if cfg.get("ntfy_enabled") and cfg.get("ntfy_url") and cfg.get("ntfy_topic"):
                tasks.append(send_ntfy(cfg["ntfy_url"], cfg["ntfy_topic"], ntfy_title, ntfy_body, priority="default", tags=["rocket"]))

            if cfg.get("evolution_enabled") and cfg.get("evolution_url") and cfg.get("evolution_instance") and cfg.get("evolution_recipient"):
                tasks.append(send_evolution_whatsapp(cfg["evolution_url"], cfg["evolution_apikey"], cfg["evolution_instance"], cfg["evolution_recipient"], wa_text))

        # Case 3: Order Completed (Filled)
        elif topic == config.TOPIC_ORDER_COMPLETE:
            if not cfg.get("notify_on_order_fill", True):
                return

            logger.info("Processing Order Completed notification for order: %s", payload.get("orderId"))
            tg_text = formatter.format_complete_telegram(payload)
            ntfy_title, ntfy_body = formatter.format_complete_ntfy(payload)
            wa_text = formatter.format_complete_whatsapp(payload)

            status = payload.get("status", "")
            icon_tag = "white_check_mark" if status == "COMPLETED" else "warning"

            if cfg.get("telegram_enabled") and cfg.get("telegram_token") and cfg.get("telegram_chat_id"):
                tasks.append(send_telegram(cfg["telegram_token"], cfg["telegram_chat_id"], tg_text))

            if cfg.get("ntfy_enabled") and cfg.get("ntfy_url") and cfg.get("ntfy_topic"):
                tasks.append(send_ntfy(cfg["ntfy_url"], cfg["ntfy_topic"], ntfy_title, ntfy_body, priority="high", tags=[icon_tag]))

            if cfg.get("evolution_enabled") and cfg.get("evolution_url") and cfg.get("evolution_instance") and cfg.get("evolution_recipient"):
                tasks.append(send_evolution_whatsapp(cfg["evolution_url"], cfg["evolution_apikey"], cfg["evolution_instance"], cfg["evolution_recipient"], wa_text))

        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    def stop(self):
        self.running = False
        if self.consumer:
            try:
                self.consumer.close()
            except Exception as e:
                logger.error("Error closing Kafka consumer: %s", e)
        self.executor.shutdown(wait=False)
        logger.info("NotificationConsumer stopped.")
