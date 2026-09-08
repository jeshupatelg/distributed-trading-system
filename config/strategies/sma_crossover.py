from base_strategy import BaseStrategy
import logging
import sys
import os

# Import CapStrategyFactory from cap_strategy package
try:
    from cap_strategy.factory import CapStrategyFactory
except ImportError:
    cap_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "cap_strategy")
    if cap_dir not in sys.path:
        sys.path.insert(0, cap_dir)
    try:
        from factory import CapStrategyFactory
    except ImportError:
        CapStrategyFactory = None

logger = logging.getLogger("SmaCrossoverStrategy")

class SmaCrossoverStrategy(BaseStrategy):
    def initialize(self, parameters: dict) -> None:
        self.fast_period = int(parameters.get("fast_period", 10))
        self.slow_period = int(parameters.get("slow_period", 30))
        self.prices = []
        self.last_fast_ma = None
        self.last_slow_ma = None
        
        cap_config = parameters.get("cap_strategy", {})
        if CapStrategyFactory:
            self.cap_strategy = CapStrategyFactory.create(cap_config)
        else:
            self.cap_strategy = None
            
        logger.info(f"Initialized SMA Crossover Strategy with fast_period={self.fast_period}, slow_period={self.slow_period}")

    def on_bar(self, bar: dict) -> dict:
        close = float(bar.get("close", 0))
        symbol = bar.get("symbol")
        provider = bar.get("provider", "unknown")
        
        self.prices.append(close)
        if len(self.prices) > self.slow_period:
            self.prices.pop(0)
            
        if len(self.prices) < self.slow_period:
            return None
            
        fast_prices = self.prices[-self.fast_period:]
        fast_ma = sum(fast_prices) / len(fast_prices)
        slow_ma = sum(self.prices) / len(self.prices)
        
        try:
            import telemetry
            telemetry.INDICATOR_VALUE.labels(ticker=symbol, indicator_name="fast_ma").set(fast_ma)
            telemetry.INDICATOR_VALUE.labels(ticker=symbol, indicator_name="slow_ma").set(slow_ma)
        except ImportError:
            pass
        
        signal = None
        if self.last_fast_ma is not None and self.last_slow_ma is not None:
            # Calculate dynamic order quantity via CapStrategy
            calc_qty = self.cap_strategy.calculate_qty(symbol, close, "BUY", bar) if self.cap_strategy else 100
            
            # Check for crossover
            if self.last_fast_ma <= self.last_slow_ma and fast_ma > slow_ma:
                signal = {
                    "symbol": symbol,
                    "action": "BUY",
                    "qty": calc_qty,
                    "price": close,
                    "provider": provider,
                    "strategy": "SmaCrossover"
                }
            elif self.last_fast_ma >= self.last_slow_ma and fast_ma < slow_ma:
                signal = {
                    "symbol": symbol,
                    "action": "SELL",
                    "qty": calc_qty,
                    "price": close,
                    "provider": provider,
                    "strategy": "SmaCrossover"
                }
                
        self.last_fast_ma = fast_ma
        self.last_slow_ma = slow_ma
        
        if signal:
            logger.info(f"SMA Crossover Triggered Signal: {signal}")
        return signal

