# Official Envoy proxy image (Hardened non-root image)
FROM envoyproxy/envoy:v1.28.0

# Copy the custom routing configurations
COPY envoy.yaml /etc/envoy/envoy.yaml

# Expose proxy port (50051) and admin port (9901)
EXPOSE 50051
EXPOSE 9901

# Health check using the Envoy admin '/ready' status endpoint
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --spider -q http://127.0.0.1:9901/ready || exit 1
