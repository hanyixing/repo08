#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

echo "[INFO] Stopping Halo services..."
docker compose -f "${COMPOSE_FILE}" down

echo "[INFO] All services stopped"

# Optionally remove volumes
if [ "${1:-}" = "--remove-volumes" ] || [ "${1:-}" = "-v" ]; then
    echo "[WARN] Removing volumes..."
    docker compose -f "${COMPOSE_FILE}" down -v
    echo "[INFO] Volumes removed"
fi
