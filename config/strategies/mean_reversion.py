from base_strategy import BaseStrategy
import logging
import math

logger = logging.getLogger("MeanReversionStrategy")

class MeanReversionStrategy(BaseStrategy):
    def initialize(self, parameters: dict) -> None:
        self.period = int(parameters.get("period", 20))
        self.threshold = float(parameters.get("threshold", 2.0))
        self.prices = []
        self.position = 0 # Track local position state for signal transitions
        logger.info(f"Initialized Mean Reversion Strategy with period={self.period}, threshold={self.threshold}")

    def on_bar(self, bar: dict) -> dict:
        close = float(bar.get("close", 0))
        symbol = bar.get("symbol")
        provider = bar.get("provider", "unknown")
        
        self.prices.append(close)
        if len(self.prices) > self.period:
            self.prices.pop(0)
            
        if len(self.prices) < self.period:
            return None
            
        mean = sum(self.prices) / len(self.prices)
        variance = sum((p - mean) ** 2 for p in self.prices) / len(self.prices)
        std_dev = math.sqrt(variance)
        
        if std_dev == 0:
            return None
            
        z_score = (close - mean) / std_dev
        
        try:
            import telemetry
            telemetry.INDICATOR_VALUE.labels(ticker=symbol, indicator_name="z_score").set(z_score)
        except ImportError:
            pass
            
        signal = None
        # Oversold - trigger buy if z-score is below negative threshold and we are not long
        if z_score < -self.threshold and self.position <= 0:
            signal = {
                "symbol": symbol,
                "action": "BUY",
                "qty": 100,
                "price": close,
                "provider": provider,
                "strategy": "MeanReversion"
            }
            self.position = 1
        # Overbought - trigger sell if z-score is above positive threshold and we are not short
        elif z_score > self.threshold and self.position >= 0:
            signal = {
                "symbol": symbol,
                "action": "SELL",
                "qty": 100,
                "price": close,
                "provider": provider,
                "strategy": "MeanReversion"
            }
            self.position = -1
            
        if signal:
            logger.info(f"Mean Reversion Triggered Signal (z_score={z_score:.2f}): {signal}")
        return signal
