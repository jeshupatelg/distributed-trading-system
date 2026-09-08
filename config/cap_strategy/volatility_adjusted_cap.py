import logging
import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from base_cap_strategy import BaseCapStrategy
from configs.volatility_adjusted_config import VolatilityAdjustedCapConfig

logger = logging.getLogger("VolatilityAdjustedCapStrategy")

class VolatilityAdjustedCapStrategy(BaseCapStrategy):
    """
    Option D: Volatility / ATR Risk Parity Sizing Strategy.
    Calculates quantity based on risking a fixed percentage of capital relative to price volatility.
    """
    def initialize(self, parameters: dict) -> None:
        self.config = VolatilityAdjustedCapConfig(parameters)

    def calculate_qty(self, symbol: str, price: float, action: str, bar: dict = None) -> int:
        if price <= 0:
            return 1

        # Use ATR from bar if provided, else default to 2% price volatility estimation
        atr = 0.0
        if bar and isinstance(bar, dict):
            atr = float(bar.get("atr", 0.0))
        if atr <= 0:
            atr = price * 0.02  # 2% estimate fallback

        risk_amount = self.config.account_capital * (self.config.risk_pct / 100.0)
        stop_distance = atr * self.config.atr_multiplier
        
        qty = max(1, int(risk_amount / stop_distance))
        logger.info(
            f"VolatilityAdjustedCapStrategy calculated qty={qty} for symbol={symbol} @ price=${price:.2f} "
            f"(risk_amount=${risk_amount:.2f}, atr=${atr:.2f}, stop_dist=${stop_distance:.2f})"
        )
        return qty
