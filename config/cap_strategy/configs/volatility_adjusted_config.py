import logging

logger = logging.getLogger("VolatilityAdjustedCapConfig")

class VolatilityAdjustedCapConfig:
    """
    Configuration parser for Volatility / ATR-based Risk Parity Sizing Strategy.
    """
    def __init__(self, parameters: dict):
        self.risk_pct = float(parameters.get("risk_pct", 1.0))
        self.atr_period = int(parameters.get("atr_period", 14))
        self.atr_multiplier = float(parameters.get("atr_multiplier", 2.0))
        self.account_capital = float(parameters.get("account_capital", 100000.0))
        if self.risk_pct <= 0 or self.risk_pct > 100.0:
            self.risk_pct = 1.0
        if self.atr_period <= 0:
            self.atr_period = 14
        if self.atr_multiplier <= 0:
            self.atr_multiplier = 2.0
        logger.info(
            f"Initialized VolatilityAdjustedCapConfig: risk_pct={self.risk_pct}%, "
            f"atr_period={self.atr_period}, atr_multiplier={self.atr_multiplier}, "
            f"capital=${self.account_capital:.2f}"
        )
