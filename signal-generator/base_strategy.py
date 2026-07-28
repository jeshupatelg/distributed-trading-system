from abc import ABC, abstractmethod

class BaseStrategy(ABC):
    @abstractmethod
    def initialize(self, parameters: dict) -> None:
        """Initialize the strategy indicators, thresholds and windows."""
        pass

    @abstractmethod
    def on_bar(self, bar: dict) -> dict | None:
        """
        Processes a single price bar dictionary.
        Returns a trade signal event dictionary, or None if no trade conditions are met.
        """
        pass
