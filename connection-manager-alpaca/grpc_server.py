"""
gRPC server implementation for the Connection Manager Service.

This module exposes the gRPC interfaces defined in the connection_manager.proto schema.
It manages active client connections for live market data streaming and routes order
actions to Alpaca REST endpoints via a ThreadPoolExecutor.
"""

import logging
import asyncio
from concurrent.futures import ThreadPoolExecutor
import grpc

import connection_manager_pb2
import connection_manager_pb2_grpc
from alpaca_client import AlpacaRestClient
import config

logger = logging.getLogger("GrpcServer")


class gRPCStreamBroadcaster:
    """
    Broadcaster for distributing live bar events to connected gRPC clients.

    Maintains active asyncio.Queue instances for all streaming client connections
    and filters/routes updates based on symbol subscription.
    """

    def __init__(self):
        """Initialize the active queues dictionary."""
        self.active_queues = {}

    def register_client(self, symbols: list) -> asyncio.Queue:
        """
        Register a client queue for streaming updates.

        Args:
            symbols (list): The list of symbols to track. If empty, tracks all.

        Returns:
            asyncio.Queue: The newly registered queue.
        """
        queue = asyncio.Queue()
        self.active_queues[queue] = set(symbols)
        logger.info("Registered new gRPC client queue for symbols: %s", symbols)
        return queue

    def unregister_client(self, queue: asyncio.Queue):
        """
        Unregister and discard a client queue on connection teardown.

        Args:
            queue (asyncio.Queue): The queue to remove.
        """
        if queue in self.active_queues:
            del self.active_queues[queue]
            logger.info("Unregistered gRPC client queue.")

    async def broadcast_bar(self, bar):
        """
        Broadcast a new bar update to all matching client subscriptions.

        Args:
            bar (Bar): Raw bar object from StockDataStream.
        """
        symbol = bar.symbol
        timestamp_str = (
            bar.timestamp.isoformat()
            if hasattr(bar.timestamp, "isoformat")
            else str(bar.timestamp)
        )

        response = connection_manager_pb2.MarketDataResponse(
            symbol=symbol,
            open=float(bar.open),
            high=float(bar.high),
            low=float(bar.low),
            close=float(bar.close),
            volume=int(bar.volume),
            timestamp=timestamp_str,
            provider="alpaca",
        )

        # Enqueue update for matched subscribers
        for queue, symbols in list(self.active_queues.items()):
            if not symbols or symbol in symbols:
                try:
                    await queue.put(response)
                except Exception as e:
                    logger.error("Failed to enqueue bar to client: %s", e)


class MarketDataServicer(
    connection_manager_pb2_grpc.MarketDataServiceServicer
):
    """
    gRPC Servicer implementation for MarketDataService.
    """

    def __init__(self, broadcaster: gRPCStreamBroadcaster, rest_client: AlpacaRestClient):
        """
        Initialize the servicer with broadcaster and rest_client.
        """
        self.broadcaster = broadcaster
        self.rest_client = rest_client
        self.thread_pool = ThreadPoolExecutor(max_workers=5)
        self.loop = asyncio.get_event_loop()

    async def StreamMarketData(self, request, context):
        """
        gRPC Server Streaming method for real-time market ticks.
        """
        symbols = request.symbols
        queue = self.broadcaster.register_client(symbols)
        try:
            while True:
                if context.done():
                    break
                response = await queue.get()
                logger.info(
                    "Pushing tick to gRPC stream: symbol=%s close=%s timestamp=%s",
                    response.symbol,
                    response.close,
                    response.timestamp,
                )
                yield response
        except asyncio.CancelledError:
            logger.info("Market data stream connection cancelled by client.")
        finally:
            self.broadcaster.unregister_client(queue)

    async def GetHistoricalBars(self, request, context):
        """
        gRPC Unary method to fetch historical bars for strategy warm-up.
        """
        logger.info("GetHistoricalBars request: symbol=%s, limit=%d", request.symbol, request.limit)
        try:
            # Offload blocking REST query to thread pool executor
            bars = await self.loop.run_in_executor(
                self.thread_pool,
                self.rest_client.get_historical_bars,
                request.symbol,
                request.limit,
            )

            bar_responses = []
            for bar in bars:
                timestamp_str = (
                    bar.timestamp.isoformat()
                    if hasattr(bar.timestamp, "isoformat")
                    else str(bar.timestamp)
                )
                bar_responses.append(
                    connection_manager_pb2.MarketDataResponse(
                        symbol=request.symbol,
                        open=float(bar.open),
                        high=float(bar.high),
                        low=float(bar.low),
                        close=float(bar.close),
                        volume=int(bar.volume),
                        timestamp=timestamp_str,
                        provider="alpaca",
                    )
                )
            return connection_manager_pb2.HistoricalBarsResponse(bars=bar_responses)
        except Exception as e:
            logger.error("Error retrieving historical bars: %s", e)
            context.set_details(str(e))
            context.set_code(grpc.StatusCode.INTERNAL)
            return connection_manager_pb2.HistoricalBarsResponse()


class OrderExecutionServicer(
    connection_manager_pb2_grpc.OrderExecutionServiceServicer
):
    """
    gRPC Servicer implementation for OrderExecutionService.
    """

    def __init__(self, rest_client: AlpacaRestClient):
        """
        Initialize the servicer with client interfaces and thread pool.
        """
        self.rest_client = rest_client
        self.thread_pool = ThreadPoolExecutor(max_workers=10)
        self.loop = asyncio.get_event_loop()

    async def PlaceOrder(self, request, context):
        """
        gRPC Unary method to submit orders to Alpaca.
        """
        logger.info(
            "PlaceOrder request: symbol=%s, qty=%d, side=%s, order_type=%s",
            request.symbol,
            request.qty,
            request.side,
            request.order_type,
        )
        stop_loss_price = getattr(request, 'stop_loss_price', 0.0)
        take_profit_price = getattr(request, 'take_profit_price', 0.0)

        try:
            order = await self.loop.run_in_executor(
                self.thread_pool,
                self.rest_client.submit_order,
                request.symbol,
                request.qty,
                request.side,
                request.order_type,
                request.limit_price if request.order_type.lower() == "limit" else None,
                stop_loss_price if stop_loss_price > 0 else None,
                take_profit_price if take_profit_price > 0 else None,
            )

            order_id = str(order.id)
            symbol = str(order.symbol)
            qty = int(order.qty)
            side = str(
                order.side.value if hasattr(order.side, "value") else order.side
            )
            status = str(
                order.status.value
                if hasattr(order.status, "value")
                else order.status
            )
            client_order_id = str(order.client_order_id)
            order_type = str(
                order.order_type.value
                if hasattr(order.order_type, "value")
                else order.order_type
            )

            return connection_manager_pb2.OrderResponse(
                order_id=order_id,
                symbol=symbol,
                qty=qty,
                side=side,
                status=status,
                client_order_id=client_order_id,
                order_type=order_type,
                provider="alpaca",
            )
        except Exception as e:
            logger.error("Error submitting order: %s", e)
            context.set_details(str(e))
            context.set_code(grpc.StatusCode.INTERNAL)
            return connection_manager_pb2.OrderResponse()

    async def GetOrderStatus(self, request, context):
        """
        gRPC Unary method to fetch order status.
        """
        logger.info("GetOrderStatus request: order_id=%s", request.order_id)
        try:
            order = await self.loop.run_in_executor(
                self.thread_pool,
                self.rest_client.get_order_by_id,
                request.order_id,
            )

            order_id = str(order.id)
            symbol = str(order.symbol)
            qty = int(order.qty)
            side = str(
                order.side.value if hasattr(order.side, "value") else order.side
            )
            status = str(
                order.status.value
                if hasattr(order.status, "value")
                else order.status
            )
            filled_qty = int(float(order.filled_qty)) if order.filled_qty else 0
            filled_avg_price = (
                float(order.filled_avg_price) if order.filled_avg_price else 0.0
            )
            client_order_id = str(order.client_order_id)

            return connection_manager_pb2.OrderStatusResponse(
                order_id=order_id,
                symbol=symbol,
                qty=qty,
                side=side,
                status=status,
                filled_qty=filled_qty,
                filled_avg_price=filled_avg_price,
                client_order_id=client_order_id,
                provider="alpaca",
            )
        except Exception as e:
            logger.error("Error retrieving order status: %s", e)
            context.set_details(str(e))
            context.set_code(grpc.StatusCode.NOT_FOUND)
            return connection_manager_pb2.OrderStatusResponse()

    async def CancelAllOrders(self, request, context):
        """
        Emergency gRPC Unary method to cancel all open orders.
        """
        logger.warning("CancelAllOrders emergency request received for provider: %s", getattr(request, 'provider', 'default'))
        try:
            cancelled_count = await self.loop.run_in_executor(
                self.thread_pool,
                self.rest_client.cancel_all_orders,
            )
            return connection_manager_pb2.CancelAllResponse(
                success=True,
                cancelled_count=cancelled_count,
                message=f"Successfully requested cancellation of {cancelled_count} orders.",
            )
        except Exception as e:
            logger.error("Error in CancelAllOrders: %s", e)
            return connection_manager_pb2.CancelAllResponse(
                success=False,
                cancelled_count=0,
                message=str(e),
            )

    async def CloseAllPositions(self, request, context):
        """
        Emergency gRPC Unary method to close all open positions.
        """
        cancel_orders = getattr(request, 'cancel_orders', True)
        logger.warning("CloseAllPositions emergency request received for provider: %s (cancel_orders=%s)", getattr(request, 'provider', 'default'), cancel_orders)
        try:
            closed_count = await self.loop.run_in_executor(
                self.thread_pool,
                self.rest_client.close_all_positions,
                cancel_orders,
            )
            return connection_manager_pb2.ClosePositionsResponse(
                success=True,
                closed_count=closed_count,
                message=f"Successfully initiated closure of {closed_count} positions.",
            )
        except Exception as e:
            logger.error("Error in CloseAllPositions: %s", e)
            return connection_manager_pb2.ClosePositionsResponse(
                success=False,
                closed_count=0,
                message=str(e),
            )



async def start_grpc_server(
    broadcaster: gRPCStreamBroadcaster, rest_client: AlpacaRestClient
) -> grpc.aio.Server:
    """
    Start the gRPC asyncio server.
    """
    import grpc.aio

    server = grpc.aio.server()
    market_servicer = MarketDataServicer(broadcaster, rest_client)
    order_servicer = OrderExecutionServicer(rest_client)
    
    connection_manager_pb2_grpc.add_MarketDataServiceServicer_to_server(
        market_servicer, server
    )
    connection_manager_pb2_grpc.add_OrderExecutionServiceServicer_to_server(
        order_servicer, server
    )

    listen_addr = f"{config.HOST}:{config.PORT_GRPC}"
    server.add_insecure_port(listen_addr)
    logger.info("Starting ConnectionManager gRPC server on %s", listen_addr)
    await server.start()
    return server

