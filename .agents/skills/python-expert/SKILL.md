---
name: "python-expert"
description: "Specialized skill in Python programming, Asyncio patterns, and Alpaca/Kafka integration."
---
# Python Expert Skill

## Instructions
1. Use `asyncio` for concurrent, non-blocking I/O operations (WebSockets, gRPC streaming).
2. Offload synchronous blocking library calls (like Alpaca REST calls) to a `ThreadPoolExecutor` or `ProcessPoolExecutor` to avoid blocking the asyncio event loop.
3. Adhere to PEP 8 style guidelines.
4. Ensure proper logging using Python's standard `logging` library.
5. **Python Docstrings**: Always write descriptive docstring comments (PEP 257 standard) for all modules, classes, methods, and functions to keep code documentation explicit.
