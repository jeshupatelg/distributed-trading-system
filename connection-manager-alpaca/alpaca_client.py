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
        stop_loss_price: float = None,
        take_profit_price: float = None,
    ):
        """
        Submit a new order to the Alpaca Broker.

        Args:
            symbol (str): Ticker symbol.
            qty (int): Quantity to buy/sell.
            side (str): "BUY" or "SELL".
            order_type (str): "market" or "limit".
            limit_price (float, optional): Limit price required for limit orders.
            stop_loss_price (float, optional): Trigger stop loss price for bracket order.
            take_profit_price (float, optional): Target limit price for bracket order.

        Returns:
            Order: The order object returned from Alpaca SDK.
        """
        from alpaca.trading.requests import (
            MarketOrderRequest,
            LimitOrderRequest,
            StopLossRequest,
            TakeProfitRequest,
        )
        from alpaca.trading.enums import OrderSide, TimeInForce, OrderClass

        side_enum = OrderSide.BUY if side.upper() == "BUY" else OrderSide.SELL

        # Configure bracket / stop attachments
        stop_loss_data = None
        if stop_loss_price and stop_loss_price > 0:
            stop_loss_data = StopLossRequest(stop_price=round(float(stop_loss_price), 2))

        take_profit_data = None
        if take_profit_price and take_profit_price > 0:
            take_profit_data = TakeProfitRequest(limit_price=round(float(take_profit_price), 2))

        order_class = OrderClass.BRACKET if (stop_loss_data or take_profit_data) else None

        if order_type.lower() == "market":
            req = MarketOrderRequest(
                symbol=symbol,
                qty=qty,
                side=side_enum,
                time_in_force=TimeInForce.DAY,
                order_class=order_class,
                stop_loss=stop_loss_data,
                take_profit=take_profit_data,
            )
        elif order_type.lower() == "limit":
            req = LimitOrderRequest(
                symbol=symbol,
                qty=qty,
                side=side_enum,
                limit_price=limit_price,
                time_in_force=TimeInForce.DAY,
                order_class=order_class,
                stop_loss=stop_loss_data,
                take_profit=take_profit_data,
            )
        else:
            req = MarketOrderRequest(
                symbol=symbol,
                qty=qty,
                side=side_enum,
                time_in_force=TimeInForce.DAY,
            )

        logger.info(
            "Submitting %s %s order for %d shares of %s (stop_loss=%s, take_profit=%s)",
            side,
            order_type,
            qty,
            symbol,
            stop_loss_price,
            take_profit_price,
        )
        return self.client.submit_order(order_data=req)

    def cancel_all_orders(self) -> int:
        """
        Emergency: Cancel all open/working orders on Alpaca.
        Returns the count of canceled order requests.
        """
        logger.warning("EMERGENCY: Executing cancel_all_orders on Alpaca...")
        try:
            cancel_statuses = self.client.cancel_orders()
            cancelled_count = len(cancel_statuses) if cancel_statuses else 0
            logger.info("Successfully requested cancellation for %d orders.", cancelled_count)
            return cancelled_count
        except Exception as e:
            logger.error("Error executing cancel_all_orders on Alpaca: %s", e)
            return 0

    def close_all_positions(self, cancel_orders: bool = True) -> int:
        """
        Emergency: Close all open positions on Alpaca and optionally cancel orders.
        Returns the count of closed position requests.
        """
        logger.warning("EMERGENCY: Executing close_all_positions (cancel_orders=%s) on Alpaca...", cancel_orders)
        try:
            close_statuses = self.client.close_all_positions(cancel_orders=cancel_orders)
            closed_count = len(close_statuses) if close_statuses else 0
            logger.info("Successfully initiated closure for %d positions.", closed_count)
            return closed_count
        except Exception as e:
            logger.error("Error executing close_all_positions on Alpaca: %s", e)
            return 0

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

    def get_historical_bars(self, symbol: str, limit: int):
        """
        Retrieve the latest historical minute bars for a ticker to support strategy warmup.

        Args:
            symbol (str): The target ticker symbol.
            limit (int): Number of bars to retrieve.
        """
        from alpaca.data.historical import StockHistoricalDataClient
        from alpaca.data.requests import StockBarsRequest
        from alpaca.data.timeframe import TimeFrame
        from datetime import datetime, timedelta, timezone

        logger.info("Fetching last %d historical minute bars for symbol: %s", limit, symbol)
        
        # Instantiate historical data client using existing credentials
        client = StockHistoricalDataClient(
            api_key=config.ALPACA_API_KEY,
            secret_key=config.ALPACA_SECRET_KEY,
        )
        
        # Query window: Look back up to 5 days to ensure we bypass weekends and market closures
        start_time = datetime.now(timezone.utc) - timedelta(days=5)
        
        req = StockBarsRequest(
            symbol_or_symbols=symbol,
            timeframe=TimeFrame.Minute,
            start=start_time,
            limit=limit,
        )
        
        bars_response = client.get_stock_bars(req)
        
        # Extract the list of bar objects for this symbol
        return bars_response.data.get(symbol, [])


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
            DataFeed.SIP if getattr(config, "ALPACA_DATA_FEED", "iex").lower() == "sip" else DataFeed.IEX
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
        import telemetry
        with telemetry.TICK_PROCESSING_DURATION.labels(ticker=bar.symbol).time():
            telemetry.TICKS_RECEIVED.labels(ticker=bar.symbol).inc()
            telemetry.TICKS_BROADCASTED.labels(ticker=bar.symbol).inc()
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
        import telemetry
        telemetry.TRADE_UPDATES_PUBLISHED.labels(event=trade_update.event).inc()
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
