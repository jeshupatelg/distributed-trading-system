import logging
import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from base_cap_strategy import BaseCapStrategy
from configs.fixed_dollar_config import FixedDollarCapConfig

logger = logging.getLogger("FixedDollarCapStrategy")

class FixedDollarCapStrategy(BaseCapStrategy):
    """
    Option B: Fixed Dollar Allocation Sizing Strategy.
    Calculates order quantity = max(1, floor(target_cash / price)).
    """
    def initialize(self, parameters: dict) -> None:
        self.config = FixedDollarCapConfig(parameters)

    def calculate_qty(self, symbol: str, price: float, action: str, bar: dict = None) -> int:
        if price <= 0:
            logger.warning(f"Invalid price ({price}) for {symbol}. Returning default qty=1.")
            return 1

        qty = max(1, int(self.config.target_cash / price))
        logger.info(
            f"FixedDollarCapStrategy calculated qty={qty} for symbol={symbol} @ price=${price:.2f} "
            f"(target_cash=${self.config.target_cash:.2f}, total_notional=${qty*price:.2f})"
        )
        return qty
