FROM python:3.12-slim

# Install system dependencies (curl for healthcheck, ca-certificates)
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -g 1000 piphotos && \
    useradd -u 1000 -g piphotos -s /bin/bash -m piphotos

WORKDIR /app

# Copy dependency definition and source
COPY pyproject.toml requirements.txt /app/
COPY piphotos_server /app/piphotos_server/
COPY *.py /app/

# Install server package
RUN pip install --no-cache-dir -r requirements.txt && \
    pip install --no-cache-dir -e .

# Create persistent mount directories
RUN mkdir -p /data /storage && \
    chown -R piphotos:piphotos /data /storage /app

USER piphotos

ENV PYTHONUNBUFFERED=1 \
    PIPHOTOS_DATA_DIR=/data \
    PIPHOTOS_STORAGE=/storage

EXPOSE 8443/tcp 5353/udp

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -k -s -f https://127.0.0.1:8443/api/v1/info || exit 1

ENTRYPOINT ["piphotos-server"]
CMD ["run", "--data-dir", "/data", "--host", "0.0.0.0", "--port", "8443"]
