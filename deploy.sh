#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
ENV_FILE="${SCRIPT_DIR}/.env"

# Default values
HALO_IMAGE="${HALO_IMAGE:-ghcr.io/halo-dev/halo-dev:main}"
BACKUP_DIR="${SCRIPT_DIR}/backups"

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --image IMAGE    Docker image to deploy (default: ${HALO_IMAGE})"
    echo "  --backup         Backup data before deployment"
    echo "  --rollback       Rollback to previous image"
    echo "  -h, --help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 --image ghcr.io/halo-dev/halo:v2.25.0"
    echo "  $0 --backup --image ghcr.io/halo-dev/halo:v2.25.0"
    echo "  $0 --rollback"
}

backup_data() {
    echo "[INFO] Creating backup..."
    mkdir -p "${BACKUP_DIR}"
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    BACKUP_FILE="${BACKUP_DIR}/backup_${TIMESTAMP}.tar.gz"

    # Backup MySQL data
    docker compose -f "${COMPOSE_FILE}" exec -T mysql mysqldump \
        -u root -p"${MYSQL_ROOT_PASSWORD:-halo_root_password}" \
        --all-databases --single-transaction > "${BACKUP_DIR}/db_${TIMESTAMP}.sql" 2>/dev/null || true

    # Backup Halo data
    docker compose -f "${COMPOSE_FILE}" exec -T halo tar czf - /root/.halo2 > "${BACKUP_DIR}/halo_data_${TIMESTAMP}.tar.gz" 2>/dev/null || true

    echo "[INFO] Backup created at ${BACKUP_DIR}"
}

rollback() {
    echo "[INFO] Rolling back to previous image..."
    if [ -f "${SCRIPT_DIR}/.previous-image" ]; then
        PREVIOUS_IMAGE=$(cat "${SCRIPT_DIR}/.previous-image")
        echo "[INFO] Rolling back to: ${PREVIOUS_IMAGE}"
        export HALO_IMAGE="${PREVIOUS_IMAGE}"
        docker compose -f "${COMPOSE_FILE}" up -d --force-recreate halo
        echo "[INFO] Rollback complete"
    else
        echo "[ERROR] No previous image found for rollback"
        exit 1
    fi
}

deploy() {
    echo "[INFO] Deploying Halo with image: ${HALO_IMAGE}"

    # Save current image for rollback
    CURRENT_IMAGE=$(docker inspect --format='{{.Config.Image}}' halo 2>/dev/null || echo "")
    if [ -n "${CURRENT_IMAGE}" ]; then
        echo "${CURRENT_IMAGE}" > "${SCRIPT_DIR}/.previous-image"
    fi

    # Pull the new image
    echo "[INFO] Pulling new image..."
    docker pull "${HALO_IMAGE}"

    # Update the service
    echo "[INFO] Updating Halo service..."
    export HALO_IMAGE="${HALO_IMAGE}"
    docker compose -f "${COMPOSE_FILE}" up -d --force-recreate halo

    # Wait for health check
    echo "[INFO] Waiting for service to be healthy..."
    TIMEOUT=120
    ELAPSED=0
    while [ $ELAPSED -lt $TIMEOUT ]; do
        STATUS=$(docker inspect --format='{{.State.Health.Status}}' halo 2>/dev/null || echo "not_found")
        if [ "${STATUS}" = "healthy" ]; then
            echo "[INFO] Deployment successful! Service is healthy."
            docker compose -f "${COMPOSE_FILE}" ps
            return 0
        elif [ "${STATUS}" = "unhealthy" ]; then
            echo "[ERROR] Service is unhealthy after deployment!"
            echo "[INFO] Use --rollback to revert"
            return 1
        fi
        sleep 5
        ELAPSED=$((ELAPSED + 5))
        echo "[INFO] Waiting... (${ELAPSED}s/${TIMEOUT}s) Status: ${STATUS}"
    done

    echo "[WARN] Timeout waiting for service to become healthy"
    docker compose -f "${COMPOSE_FILE}" ps
    return 1
}

# Parse arguments
DO_BACKUP=false
DO_ROLLBACK=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --image)
            HALO_IMAGE="$2"
            shift 2
            ;;
        --backup)
            DO_BACKUP=true
            shift
            ;;
        --rollback)
            DO_ROLLBACK=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "[ERROR] Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Load environment variables
if [ -f "${ENV_FILE}" ]; then
    set -a
    source "${ENV_FILE}"
    set +a
fi

if [ "${DO_ROLLBACK}" = true ]; then
    rollback
    exit 0
fi

if [ "${DO_BACKUP}" = true ]; then
    backup_data
fi

deploy
