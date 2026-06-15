#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
ENV_FILE="${SCRIPT_DIR}/.env"

# Load environment variables
if [ -f "${ENV_FILE}" ]; then
    echo "[INFO] Loading environment from ${ENV_FILE}"
    set -a
    source "${ENV_FILE}"
    set +a
else
    echo "[WARN] No .env file found, using default values or .env.example"
    if [ -f "${SCRIPT_DIR}/.env.example" ]; then
        echo "[INFO] Copy .env.example to .env and configure it first"
        echo "[INFO] cp .env.example .env"
    fi
fi

echo "[INFO] Starting Halo services..."
docker compose -f "${COMPOSE_FILE}" up -d

echo "[INFO] Waiting for services to be healthy..."
TIMEOUT=120
ELAPSED=0
while [ $ELAPSED -lt $TIMEOUT ]; do
    if docker compose -f "${COMPOSE_FILE}" ps | grep -q "healthy"; then
        HALO_STATUS=$(docker inspect --format='{{.State.Health.Status}}' halo 2>/dev/null || echo "not_found")
        if [ "${HALO_STATUS}" = "healthy" ]; then
            echo "[INFO] Halo service is healthy!"
            break
        fi
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo "[INFO] Waiting... (${ELAPSED}s/${TIMEOUT}s)"
done

if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "[WARN] Timeout waiting for services to be healthy"
    echo "[INFO] Current service status:"
    docker compose -f "${COMPOSE_FILE}" ps
fi

echo "[INFO] Services started successfully"
docker compose -f "${COMPOSE_FILE}" ps
