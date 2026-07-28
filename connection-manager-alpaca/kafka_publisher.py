"""
Kafka publisher module for connection-manager-alpaca.

This module handles publishing raw trade and order status WebSocket events
received from Alpaca stream to the Kafka topic 'raw-order-updates'.
"""

import json
import logging
from concurrent.futures import ThreadPoolExecutor
import asyncio
from confluent_kafka import Producer
import config

logger = logging.getLogger("KafkaPublisher")


class KafkaEventPublisher:
    """
    Publisher class for sending raw order events to Apache Kafka.

    Encapsulates a confluent-kafka Producer, running delivery polls
    and produce operations on a thread pool to avoid blocking the asyncio event loop.
    """

    def __init__(self):
        """Initialize the Kafka Producer configuration and thread pool executor."""
        conf = {
            "bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS,
            "client.id": "connection-manager-alpaca",
        }
        self.producer = Producer(conf)
        self.topic = config.KAFKA_TOPIC_RAW_ORDER_UPDATES
        self.loop = None
        self.executor = ThreadPoolExecutor(max_workers=2)
        self._running = True
        self._poll_task = None
        
        # Programmatically ensure topic exists
        try:
            self._ensure_topic_exists()
        except Exception as e:
            logger.error("Failed to verify/create Kafka topic programmatically: %s", e)

    def _ensure_topic_exists(self):
        """Query broker and create the configured topic if it does not exist."""
        from confluent_kafka.admin import AdminClient, NewTopic
        
        logger.info("Verifying existence of Kafka topic '%s'...", self.topic)
        admin = AdminClient({"bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS})
        metadata = admin.list_topics(timeout=5.0)
        
        if self.topic not in metadata.topics:
            if not config.KAFKA_AUTO_CREATE_TOPICS:
                logger.error("Topic '%s' does not exist and KAFKA_AUTO_CREATE_TOPICS is disabled.", self.topic)
                raise ValueError(f"CRITICAL: Kafka topic '{self.topic}' does not exist and KAFKA_AUTO_CREATE_TOPICS is disabled.")
                
            logger.info("Topic '%s' not found. Creating programmatically...", self.topic)
            # Default to 3 partitions for consumer scalability and 1 replica for dev/local setups
            new_topic = NewTopic(self.topic, num_partitions=3, replication_factor=1)
            futures = admin.create_topics([new_topic])
            for topic, future in futures.items():
                future.result(timeout=5.0)
            logger.info("Topic '%s' created successfully.", self.topic)
        else:
            logger.info("Topic '%s' already exists.", self.topic)


    def start(self):
        """Start the background poll loop task in the current event loop."""
        self.loop = asyncio.get_running_loop()
        self._poll_task = self.loop.create_task(self._poll_loop())
        logger.info("KafkaEventPublisher background poll loop started.")

    async def _poll_loop(self):
        """Periodically trigger producer poll to handle message callbacks."""
        while self._running:
            try:
                # Run poll in thread pool to avoid blocking asyncio
                await self.loop.run_in_executor(self.executor, self.producer.poll, 0.1)
            except Exception as e:
                logger.error("Error in Kafka producer poll loop: %s", e)
            await asyncio.sleep(0.1)

    def _delivery_report(self, err, msg):
        """
        Callback handler for confluent-kafka message delivery reports.

        Args:
            err (KafkaError): The error object, or None if successful.
            msg (Message): The message object that was sent.
        """
        if err is not None:
            logger.error("Failed to deliver Kafka message: %s", err)
        else:
            logger.debug(
                "Successfully delivered message to %s [%d] at offset %d",
                msg.topic(),
                msg.partition(),
                msg.offset(),
            )

    async def publish_order_update(self, order_update_dict: dict):
        """
        Publish raw order update dictionary as a JSON payload to Kafka.

        Args:
            order_update_dict (dict): Raw dictionary object from Alpaca TradingStream.
        """
        if not self.loop:
            self.loop = asyncio.get_running_loop()

        try:
            payload = json.dumps(order_update_dict).encode("utf-8")
            # Queue production asynchronously
            await self.loop.run_in_executor(
                self.executor,
                lambda: self.producer.produce(
                    self.topic,
                    value=payload,
                    callback=self._delivery_report,
                ),
            )
            logger.info("Enqueued order update event to Kafka topic: %s", self.topic)
        except Exception as e:
            logger.error("Exception during Kafka publishing: %s", e)

    def close(self):
        """Stop background tasks, flush remaining messages, and release pool resources."""
        self._running = False
        if self._poll_task:
            self._poll_task.cancel()
        try:
            # Flush in thread pool to avoid blocking main thread
            self.producer.flush(timeout=5.0)
        except Exception as e:
            logger.error("Error flushing Kafka producer: %s", e)
        self.executor.shutdown(wait=True)
        logger.info("KafkaEventPublisher closed successfully.")
