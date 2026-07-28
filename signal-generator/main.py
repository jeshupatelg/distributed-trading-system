import asyncio
import logging
import os
import importlib.util
import inspect
from fastapi import FastAPI
import uvicorn
import grpc
import config
from base_strategy import BaseStrategy
from kafka_publisher import KafkaSignalPublisher

# Import compiled proto modules
import connection_manager_pb2
import connection_manager_pb2_grpc

logger = logging.getLogger("SignalGeneratorMain")

# Load Strategy Dynamically
def load_strategy() -> BaseStrategy:
    file_path = config.STRATEGY_FILE_PATH
    class_name = config.STRATEGY_CLASS_NAME
    params = config.STRATEGY_PARAMS
    
    logger.info(f"Loading dynamic strategy class '{class_name}' from '{file_path}'...")
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"Strategy file not found at: {file_path}")
        
    module_name = os.path.splitext(os.path.basename(file_path))[0]
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    if spec is None or spec.loader is None:
        raise ImportError(f"Could not load module spec for {file_path}")
        
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    
    if not hasattr(module, class_name):
        raise AttributeError(f"Module '{module_name}' has no attribute '{class_name}'")
        
    strategy_class = getattr(module, class_name)
    if not inspect.isclass(strategy_class):
        raise TypeError(f"'{class_name}' is not a valid class.")
        
    if not issubclass(strategy_class, BaseStrategy):
        raise TypeError(f"Class '{class_name}' must inherit from BaseStrategy")
        
    strategy_instance = strategy_class()
    strategy_instance.initialize(params)
    logger.info("Strategy loaded and initialized successfully.")
    return strategy_instance

# FastAPI Setup for Probes
app = FastAPI()

@app.get("/health")
async def health_check():
    return {"status": "healthy", "ticker": config.TICKER}

async def consume_market_data(strategy: BaseStrategy, publisher: KafkaSignalPublisher):
    """
    gRPC Client task that connects to the broker/load-balancer endpoint,
    consumes the market data stream, and feeds it to the strategy runner.
    """
    logger.info(f"Starting market data consumer task for ticker {config.TICKER}...")
    retry_delay = 5.0
    
    while True:
        try:
            logger.info(f"Connecting to gRPC endpoint at {config.CONNECTION_MANAGER_ENDPOINT}...")
            async with grpc.aio.insecure_channel(config.CONNECTION_MANAGER_ENDPOINT) as channel:
                # Use MarketDataServiceStub instead of ConnectionManagerServiceStub
                stub = connection_manager_pb2_grpc.MarketDataServiceStub(channel)
                req = connection_manager_pb2.MarketDataRequest(symbols=[config.TICKER])
                
                # Initiate gRPC server streaming subscription
                # We also attach the x-ticker header so the load balancer routes correctly
                metadata = (("x-ticker", config.TICKER),)
                stream = stub.StreamMarketData(req, metadata=metadata)
                
                logger.info(f"Subscribed successfully to {config.TICKER} feed. Consuming stream...")
                async for bar_proto in stream:
                    bar_dict = {
                        "symbol": bar_proto.symbol,
                        "open": bar_proto.open,
                        "high": bar_proto.high,
                        "low": bar_proto.low,
                        "close": bar_proto.close,
                        "volume": bar_proto.volume,
                        "timestamp": bar_proto.timestamp,
                        "provider": bar_proto.provider
                    }
                    
                    try:
                        # Process tick with pluggable strategy
                        signal = strategy.on_bar(bar_dict)
                        if signal:
                            await publisher.publish_signal(signal)
                    except Exception as ex:
                        logger.error(f"Error running strategy on_bar: {ex}", exc_info=True)
                        
        except grpc.RpcError as rpc_ex:
            logger.error(f"gRPC stream connection error: {rpc_ex.details() if hasattr(rpc_ex, 'details') else rpc_ex}. Retrying in {retry_delay}s...")
        except Exception as ex:
            logger.error(f"Unexpected consumer error: {ex}. Retrying in {retry_delay}s...", exc_info=True)
            
        await asyncio.sleep(retry_delay)

async def main():
    try:
        strategy = load_strategy()
    except Exception as e:
        logger.error(f"Failed to load dynamic strategy: {e}", exc_info=True)
        raise
        
    publisher = KafkaSignalPublisher()
    
    # Run the client consumer task in the background
    consumer_task = asyncio.create_task(consume_market_data(strategy, publisher))
    
    # Run FastAPI Server for K8s Probes
    fastapi_config = uvicorn.Config(app, host=config.HOST, port=config.PORT_REST, log_level="warning")
    fastapi_server = uvicorn.Server(fastapi_config)
    
    try:
        await fastapi_server.serve()
    finally:
        logger.info("Shutting down consumer and publisher...")
        consumer_task.cancel()
        try:
            await consumer_task
        except asyncio.CancelledError:
            pass
        publisher.close()
        logger.info("Shutdown completed.")

if __name__ == "__main__":
    asyncio.run(main())
