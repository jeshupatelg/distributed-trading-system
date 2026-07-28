from base_strategy import BaseStrategy
import logging

logger = logging.getLogger("SmaCrossoverStrategy")

class SmaCrossoverStrategy(BaseStrategy):
    def initialize(self, parameters: dict) -> None:
        self.fast_period = int(parameters.get("fast_period", 10))
        self.slow_period = int(parameters.get("slow_period", 30))
        self.prices = []
        self.last_fast_ma = None
        self.last_slow_ma = None
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
        
        signal = None
        if self.last_fast_ma is not None and self.last_slow_ma is not None:
            # Check for crossover
            if self.last_fast_ma <= self.last_slow_ma and fast_ma > slow_ma:
                signal = {
                    "symbol": symbol,
                    "action": "BUY",
                    "qty": 100,
                    "price": close,
                    "provider": provider,
                    "strategy": "SmaCrossover"
                }
            elif self.last_fast_ma >= self.last_slow_ma and fast_ma < slow_ma:
                signal = {
                    "symbol": symbol,
                    "action": "SELL",
                    "qty": 100,
                    "price": close,
                    "provider": provider,
                    "strategy": "SmaCrossover"
                }
                
        self.last_fast_ma = fast_ma
        self.last_slow_ma = slow_ma
        
        if signal:
            logger.info(f"SMA Crossover Triggered Signal: {signal}")
        return signal
