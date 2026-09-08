import logging
import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from base_cap_strategy import BaseCapStrategy
from configs.equity_percentage_config import EquityPercentageCapConfig

logger = logging.getLogger("EquityPercentageCapStrategy")

class EquityPercentageCapStrategy(BaseCapStrategy):
    """
    Option C: Percentage of Account Equity Sizing Strategy.
    Calculates order quantity = max(1, floor((account_equity * pct) / price)).
    """
    def initialize(self, parameters: dict) -> None:
        self.config = EquityPercentageCapConfig(parameters)

    def calculate_qty(self, symbol: str, price: float, action: str, bar: dict = None) -> int:
        if price <= 0:
            return 1

        allocated_cash = self.config.fallback_equity * (self.config.equity_pct / 100.0)
        qty = max(1, int(allocated_cash / price))
        logger.info(
            f"EquityPercentageCapStrategy calculated qty={qty} for symbol={symbol} @ price=${price:.2f} "
            f"(equity_pct={self.config.equity_pct}%, allocated_cash=${allocated_cash:.2f})"
        )
        return qty
