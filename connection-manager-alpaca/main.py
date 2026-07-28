"""
Entry point application module for the connection-manager-alpaca service.

Starts a FastAPI HTTP server for health monitoring and registers lifespan hooks
to initialize and shut down gRPC and Alpaca WebSocket tasks concurrently.
"""

import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
import uvicorn

import config
from kafka_publisher import KafkaEventPublisher
from alpaca_client import AlpacaRestClient, AlpacaStreamClient
from grpc_server import gRPCStreamBroadcaster, start_grpc_server

logger = logging.getLogger("Main")

# Global service references
kafka_publisher = None
rest_client = None
stream_client = None
broadcaster = None
grpc_server_instance = None


async def start_services():
    """Initialize all broker clients, Kafka publishers, and gRPC servers."""
    global kafka_publisher, rest_client, stream_client, broadcaster, grpc_server_instance
    logger.info("Initializing connection manager services...")

    # Initialize Kafka publisher
    kafka_publisher = KafkaEventPublisher()
    kafka_publisher.start()

    # Initialize Alpaca REST client
    rest_client = AlpacaRestClient()

    # Initialize broadcaster & gRPC server
    broadcaster = gRPCStreamBroadcaster()
    grpc_server_instance = await start_grpc_server(broadcaster, rest_client)

    # Initialize and start Alpaca Stream Client
    stream_client = AlpacaStreamClient(kafka_publisher, broadcaster)
    stream_client.start()

    logger.info("All connection manager services started successfully.")


async def stop_services():
    """Gracefully shut down all background connections and servers."""
    global kafka_publisher, stream_client, grpc_server_instance
    logger.info("Stopping connection manager services...")

    if stream_client:
        try:
            await stream_client.stop()
        except Exception as e:
            logger.error("Error stopping stream client: %s", e)

    if grpc_server_instance:
        try:
            await grpc_server_instance.stop(grace=2.0)
            logger.info("gRPC server stopped.")
        except Exception as e:
            logger.error("Error stopping gRPC server: %s", e)

    if kafka_publisher:
        try:
            kafka_publisher.close()
        except Exception as e:
            logger.error("Error closing Kafka publisher: %s", e)

    logger.info("All services shut down successfully.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    FastAPI lifespan context manager for startup and shutdown execution.

    Args:
        app (FastAPI): The FastAPI application instance.
    """
    await start_services()
    yield
    await stop_services()


# Initialize FastAPI app with lifespan manager
app = FastAPI(
    title="connection-manager-alpaca",
    description="Stateless broker gateway service for Alpaca Integration",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health_check():
    """
    Liveness and Readiness health check endpoint.

    Returns:
        dict: Status message indicating health.
    """
    return {
        "status": "HEALTHY",
        "service": "connection-manager-alpaca",
        "provider": "alpaca",
    }


if __name__ == "__main__":
    logger.info("Starting FastAPI/Uvicorn on %s:%d", config.HOST, config.PORT_REST)
    # Run Uvicorn HTTP server
    uvicorn.run("main:app", host=config.HOST, port=config.PORT_REST, log_config=None)
