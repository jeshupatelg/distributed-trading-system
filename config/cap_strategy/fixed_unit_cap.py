import logging
import sys
import os

# Ensure current directory is in path for imports
current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from base_cap_strategy import BaseCapStrategy
from configs.fixed_unit_config import FixedUnitCapConfig

logger = logging.getLogger("FixedUnitCapStrategy")

class FixedUnitCapStrategy(BaseCapStrategy):
    """
    Option A: Fixed Unit/Share Sizing Strategy.
    Returns a static share count per signal.
    """
    def initialize(self, parameters: dict) -> None:
        self.config = FixedUnitCapConfig(parameters)

    def calculate_qty(self, symbol: str, price: float, action: str, bar: dict = None) -> int:
        qty = self.config.unit_qty
        logger.debug(f"FixedUnitCapStrategy calculated qty={qty} for symbol={symbol}")
        return qty
