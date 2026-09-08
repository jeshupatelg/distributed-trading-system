from abc import ABC, abstractmethod
import logging

logger = logging.getLogger("BaseCapStrategy")

class BaseCapStrategy(ABC):
    """
    Abstract Base Class for all Capital / Quantity Sizing Strategies (CapStrategy).
    Decouples position sizing calculations from trading signal indicators.
    """

    @abstractmethod
    def initialize(self, parameters: dict) -> None:
        """
        Initialize the sizing strategy parameters.
        """
        pass

    @abstractmethod
    def calculate_qty(self, symbol: str, price: float, action: str, bar: dict = None) -> int:
        """
        Calculates the order quantity for a trade signal.
        Must return a positive integer quantity (minimum 1).
        """
        pass
