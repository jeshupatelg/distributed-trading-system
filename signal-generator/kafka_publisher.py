import asyncio
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from confluent_kafka import Producer
import config

logger = logging.getLogger("KafkaSignalPublisher")

class KafkaSignalPublisher:
    def __init__(self):
        conf = {
            "bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS,
            "client.id": f"signal-gen-{config.TICKER.lower()}"
        }
        self.producer = Producer(conf)
        self.executor = ThreadPoolExecutor(max_workers=3)
        self.loop = asyncio.get_event_loop()
        logger.info(f"Initialized Kafka publisher to brokers: {config.KAFKA_BOOTSTRAP_SERVERS}")
        
        # Programmatically ensure topic exists
        try:
            self._ensure_topic_exists()
        except Exception as e:
            logger.error(f"Failed to verify/create Kafka topic programmatically: {e}")

    def _ensure_topic_exists(self):
        """Query broker and create the configured topic if it does not exist."""
        from confluent_kafka.admin import AdminClient, NewTopic
        
        topic = config.KAFKA_TOPIC_SIGNALS
        logger.info(f"Verifying existence of Kafka topic '{topic}'...")
        admin = AdminClient({"bootstrap.servers": config.KAFKA_BOOTSTRAP_SERVERS})
        metadata = admin.list_topics(timeout=5.0)
        
        if topic not in metadata.topics:
            if not config.KAFKA_AUTO_CREATE_TOPICS:
                logger.error(f"Topic '{topic}' does not exist and KAFKA_AUTO_CREATE_TOPICS is disabled.")
                raise ValueError(f"CRITICAL: Kafka topic '{topic}' does not exist and KAFKA_AUTO_CREATE_TOPICS is disabled.")
                
            logger.info(f"Topic '{topic}' not found. Creating programmatically...")
            new_topic = NewTopic(topic, num_partitions=3, replication_factor=1)
            futures = admin.create_topics([new_topic])
            for t, future in futures.items():
                future.result(timeout=5.0)
            logger.info(f"Topic '{topic}' created successfully.")
        else:
            logger.info(f"Topic '{topic}' already exists.")


    def _sync_publish(self, topic: str, key: str, value: str):
        try:
            self.producer.produce(
                topic=topic,
                key=key.encode('utf-8') if key else None,
                value=value.encode('utf-8'),
                callback=self._delivery_report
            )
            self.producer.poll(0)
        except Exception as e:
            logger.error(f"Error executing sync produce to Kafka: {e}")

    def _delivery_report(self, err, msg):
        if err is not None:
            logger.error(f"Failed to deliver signal to Kafka: {err}")
        else:
            logger.debug(f"Successfully published signal to {msg.topic()} [{msg.partition()}]")

    async def publish_signal(self, signal: dict):
        key = signal.get("symbol", config.TICKER)
        val_str = json.dumps(signal)
        await self.loop.run_in_executor(
            self.executor,
            self._sync_publish,
            config.KAFKA_TOPIC_SIGNALS,
            key,
            val_str
        )

    def close(self):
        logger.info("Flushing and closing Kafka publisher...")
        self.producer.flush(timeout=5.0)
        self.executor.shutdown(wait=True)
