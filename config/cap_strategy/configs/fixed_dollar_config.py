import logging

logger = logging.getLogger("FixedDollarCapConfig")

class FixedDollarCapConfig:
    """
    Configuration parser for Fixed Dollar Allocation Strategy.
    """
    def __init__(self, parameters: dict):
        self.target_cash = float(parameters.get("target_cash", 5000.0))
        if self.target_cash <= 0:
            logger.warning(f"Invalid target_cash ({self.target_cash}). Defaulting to 5000.0.")
            self.target_cash = 5000.0
        logger.info(f"Initialized FixedDollarCapConfig: target_cash=${self.target_cash:.2f}")
