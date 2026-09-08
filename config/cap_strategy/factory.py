import logging
import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

from base_cap_strategy import BaseCapStrategy
from fixed_unit_cap import FixedUnitCapStrategy
from fixed_dollar_cap import FixedDollarCapStrategy
from equity_percentage_cap import EquityPercentageCapStrategy
from volatility_adjusted_cap import VolatilityAdjustedCapStrategy

logger = logging.getLogger("CapStrategyFactory")

class CapStrategyFactory:
    """
    Unified Factory for instantiating CapStrategy implementations.
    """
    @staticmethod
    def create(cap_config: dict = None) -> BaseCapStrategy:
        if not cap_config or not isinstance(cap_config, dict):
            logger.info("No cap_strategy config provided. Defaulting to FixedDollarCapStrategy ($5,000 allocation).")
            strategy = FixedDollarCapStrategy()
            strategy.initialize({"target_cash": 5000.0})
            return strategy

        name = str(cap_config.get("name", cap_config.get("class", "fixed_dollar"))).lower()
        
        if "unit" in name:
            strategy = FixedUnitCapStrategy()
        elif "dollar" in name or "cash" in name:
            strategy = FixedDollarCapStrategy()
        elif "equity" in name or "percentage" in name or "percent" in name:
            strategy = EquityPercentageCapStrategy()
        elif "volatility" in name or "atr" in name:
            strategy = VolatilityAdjustedCapStrategy()
        else:
            logger.warning(f"Unrecognized CapStrategy name '{name}'. Defaulting to FixedDollarCapStrategy.")
            strategy = FixedDollarCapStrategy()

        strategy.initialize(cap_config)
        return strategy
