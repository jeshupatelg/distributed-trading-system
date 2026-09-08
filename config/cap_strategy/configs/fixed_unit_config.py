import logging

logger = logging.getLogger("FixedUnitCapConfig")

class FixedUnitCapConfig:
    """
    Configuration parser for Fixed Unit Sizing Strategy.
    """
    def __init__(self, parameters: dict):
        self.unit_qty = int(parameters.get("unit_qty", parameters.get("qty", 10)))
        if self.unit_qty <= 0:
            logger.warning(f"Invalid unit_qty ({self.unit_qty}). Defaulting to 10.")
            self.unit_qty = 10
        logger.info(f"Initialized FixedUnitCapConfig: unit_qty={self.unit_qty}")
