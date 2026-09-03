# Stage 1: Build dependencies
FROM python:3.12-slim AS builder

WORKDIR /app

# Install build tools for confluent-kafka if wheel compilation is needed
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Stage 2: Final runner image
FROM python:3.12-slim AS runner

WORKDIR /app

COPY --from=builder /opt/venv /opt/venv

ENV PYTHONUNBUFFERED=1
ENV PATH="/opt/venv/bin:$PATH"

COPY . /app/

EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8085/health')" || exit 1

RUN useradd -u 8888 appuser && chown -R appuser:appuser /app
USER appuser

CMD ["python", "main.py"]
