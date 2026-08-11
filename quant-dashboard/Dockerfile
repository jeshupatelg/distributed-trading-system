# Stage 1: Build dependencies
FROM python:3.12-slim AS builder

WORKDIR /app

# Install build dependencies if needed
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# Initialize virtual environment and install packages
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Stage 2: Final runner image
FROM python:3.12-slim AS runner

WORKDIR /app

# Copy the pre-built virtual environment containing all dependencies
COPY --from=builder /opt/venv /opt/venv

# Configure system environment
ENV PYTHONUNBUFFERED=1
ENV PATH="/opt/venv/bin:$PATH"

# Copy python source files
COPY *.py /app/

# Expose Streamlit port
EXPOSE 8501

# Declare health check utilizing native python urllib to check Streamlit health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8501/_stcore/health')" || exit 1

# Define running user as non-root for container hardening
RUN useradd -u 8888 appuser && chown -R appuser:appuser /app
USER appuser

# Start Streamlit application
CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0"]
