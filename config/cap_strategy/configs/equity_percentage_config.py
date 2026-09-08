import logging

logger = logging.getLogger("EquityPercentageCapConfig")

class EquityPercentageCapConfig:
    """
    Configuration parser for Equity Percentage Sizing Strategy.
    """
    def __init__(self, parameters: dict):
        self.equity_pct = float(parameters.get("equity_pct", 5.0))
        self.fallback_equity = float(parameters.get("fallback_equity", 100000.0))
        if self.equity_pct <= 0 or self.equity_pct > 100.0:
            logger.warning(f"Invalid equity_pct ({self.equity_pct}%). Defaulting to 5.0%.")
            self.equity_pct = 5.0
        if self.fallback_equity <= 0:
            logger.warning(f"Invalid fallback_equity ({self.fallback_equity}). Defaulting to 100000.0.")
            self.fallback_equity = 100000.0
        logger.info(
            f"Initialized EquityPercentageCapConfig: equity_pct={self.equity_pct}%, "
            f"fallback_equity=${self.fallback_equity:.2f}"
        )
