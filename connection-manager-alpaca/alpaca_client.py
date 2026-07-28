"""
Alpaca REST client and WebSocket stream wrapper.

This module provides thin abstractions over Alpaca SDK's REST client (TradingClient)
and live WebSocket streams (StockDataStream, TradingStream).
"""

import logging
import asyncio
from alpaca.data.live.stock import StockDataStream
from alpaca.trading.stream import TradingStream
from alpaca.trading.client import TradingClient
import config

logger = logging.getLogger("AlpacaClient")


class AlpacaRestClient:
    """
    Client wrapper for executing Alpaca REST operations.

    Exposes thread-safe interfaces for placing orders and querying order statuses.
    """

    def __init__(self):
        """Initialize the underlying Alpaca TradingClient using application config."""
        is_paper = "paper" in config.ALPACA_BASE_URL.lower()
        self.client = TradingClient(
            api_key=config.ALPACA_API_KEY,
            secret_key=config.ALPACA_SECRET_KEY,
            paper=is_paper,
            url_override=config.ALPACA_BASE_URL,
        )
        logger.info("AlpacaRestClient initialized (paper=%s).", is_paper)

    def submit_order(
        self,
        symbol: str,
        qty: int,
        side: str,
        order_type: str,
        limit_price: float = None,
    ):
        """
        Submit a new order to the Alpaca Broker.

        Args:
            symbol (str): Ticker symbol.
            qty (int): Quantity to buy/sell.
            side (str): "BUY" or "SELL".
            order_type (str): "market" or "limit".
            limit_price (float, optional): Limit price required for limit orders.

        Returns:
            Order: The order object returned from Alpaca SDK.
        """
        from alpaca.trading.requests import MarketOrderRequest, LimitOrderRequest
        from alpaca.trading.enums import OrderSide, TimeInForce

        side_enum = OrderSide.BUY if side.upper() == "BUY" else OrderSide.SELL
        if order_type.lower() == "market":
            req = MarketOrderRequest(
                symbol=symbol,
                qty=qty,
                side=side_enum,
                time_in_force=TimeInForce.DAY,
            )
        elif order_type.lower() == "limit":
            req = LimitOrderRequest(
                symbol=symbol,
                qty=qty,
                side=side_enum,
                limit_price=limit_price,
                time_in_force=TimeInForce.DAY,
            )
        else:
            raise ValueError(f"Unsupported order type: {order_type}")

        logger.info(
            "Submitting %s %s order for %d shares of %s",
            side,
            order_type,
            qty,
            symbol,
        )
        return self.client.submit_order(order_data=req)

    def get_order_by_id(self, order_id: str):
        """
        Retrieve order status by its Alpaca Order ID.

        Args:
            order_id (str): The unique order ID.

        Returns:
            Order: The order object retrieved from Alpaca SDK.
        """
        logger.info("Querying order status for ID: %s", order_id)
        return self.client.get_order_by_id(order_id)


class AlpacaStreamClient:
    """
    Stream wrapper for subscribing to Alpaca WebSockets.

    Coordinates StockDataStream (for market bars) and TradingStream (for order updates).
    """

    def __init__(self, kafka_publisher, grpc_broadcaster=None):
        """
        Initialize StockDataStream and TradingStream clients.

        Args:
            kafka_publisher (KafkaEventPublisher): Instance of KafkaEventPublisher.
            grpc_broadcaster (gRPCStreamBroadcaster, optional): Instance of broadcaster to forward market ticks.
        """
        self.kafka_publisher = kafka_publisher
        self.grpc_broadcaster = grpc_broadcaster

        from alpaca.data.enums import DataFeed

        feed_enum = (
            DataFeed.SIP if config.ALPACA_DATA_FEED == "sip" else DataFeed.IEX
        )

        self.data_stream = StockDataStream(
            api_key=config.ALPACA_API_KEY,
            secret_key=config.ALPACA_SECRET_KEY,
            feed=feed_enum,
        )

        is_paper = "paper" in config.ALPACA_BASE_URL.lower()
        self.trading_stream = TradingStream(
            api_key=config.ALPACA_API_KEY,
            secret_key=config.ALPACA_SECRET_KEY,
            paper=is_paper,
        )

        self._tasks = []
        self._running = False
        logger.info(
            "AlpacaStreamClient initialized with data feed: %s", feed_enum
        )

    async def _bar_handler(self, bar):
        """
        Asynchronous handler callback for live market bars.

        Args:
            bar (Bar): Raw bar object from StockDataStream.
        """
        logger.debug("Live bar received: %s", bar)
        if self.grpc_broadcaster:
            await self.grpc_broadcaster.broadcast_bar(bar)

    async def _trade_update_handler(self, trade_update):
        """
        Asynchronous handler callback for Alpaca account order updates.

        Args:
            trade_update (TradeUpdate): Raw trade update object from TradingStream.
        """
        logger.info(
            "Live trade update received: event=%s, order_id=%s",
            trade_update.event,
            trade_update.order.id,
        )
        if hasattr(trade_update, "model_dump"):
            data = trade_update.model_dump()
        else:
            data = trade_update.dict()

        # Publish serialized dict to Kafka
        await self.kafka_publisher.publish_order_update(data)

    def start(self):
        """Subscribe to targets and start event tasks in the current loop."""
        self._running = True

        # Subscribe to bars
        if config.TICKERS_TO_TRACK:
            self.data_stream.subscribe_bars(
                self._bar_handler, *config.TICKERS_TO_TRACK
            )
            logger.info("Subscribed to bars for tickers: %s", config.TICKERS_TO_TRACK)

        # Subscribe to trade updates
        self.trading_stream.subscribe_trade_updates(self._trade_update_handler)
        logger.info("Subscribed to trading stream updates.")

        loop = asyncio.get_running_loop()
        self._tasks.append(loop.create_task(self.data_stream._run_forever()))
        self._tasks.append(loop.create_task(self.trading_stream._run_forever()))
        logger.info("Started Alpaca streaming tasks in active event loop.")

    async def stop(self):
        """Shut down and cancel stream tasks."""
        self._running = False
        logger.info("Stopping Alpaca streaming connections...")

        try:
            await self.data_stream.stop()
        except Exception as e:
            logger.error("Error stopping data stream: %s", e)

        try:
            await self.trading_stream.stop()
        except Exception as e:
            logger.error("Error stopping trading stream: %s", e)

        for task in self._tasks:
            if not task.done():
                task.cancel()

        self._tasks.clear()
        logger.info("AlpacaStreamClient tasks stopped.")
