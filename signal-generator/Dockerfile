# Stage 1: Build stage
FROM python:3.12-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Stage 2: Final runtime stage
FROM python:3.12-slim AS runner

WORKDIR /app

# Setup non-root execution environment
RUN groupadd -g 8888 appgroup && \
    useradd -u 8888 -g appgroup -m -s /bin/bash appuser

# Copy installed dependencies and codebase
COPY --from=builder /root/.local /home/appuser/.local
COPY . /app

# Set correct paths and owner permissions
ENV PATH=/home/appuser/.local/bin:$PATH
ENV PYTHONPATH=/app
RUN chown -R appuser:appgroup /app /home/appuser

USER appuser

EXPOSE 8000
EXPOSE 50051

# Container healthcheck using standard library urllib request
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')" || exit 1

ENTRYPOINT ["python", "main.py"]
