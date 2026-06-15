#!/usr/bin/env bash
#
# deploy.sh — build the Halo jar and (re)start the service.
#
# Usage:
#   ./deploy.sh [profile]         # profile defaults to $SPRING_PROFILES_ACTIVE or "prod"
#
# Environment overrides:
#   SPRING_PROFILES_ACTIVE   active Spring profile (dev|test|prod|...)
#   RUN_TESTS                "true" to run the full test suite during build (default: false)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PROFILE="${SPRING_PROFILES_ACTIVE:-${1:-prod}}"
RUN_TESTS="${RUN_TESTS:-false}"

if [[ ! -x "$SCRIPT_DIR/gradlew" ]]; then
  echo "[deploy] gradlew not found or not executable in $SCRIPT_DIR." >&2
  exit 1
fi

echo "[deploy] Building Halo (profile=$PROFILE, run_tests=$RUN_TESTS)..."
if [[ "$RUN_TESTS" == "true" ]]; then
  ./gradlew clean build
else
  ./gradlew clean build -x test
fi

echo "[deploy] Restarting service..."
"$SCRIPT_DIR/stop.sh" || true
SPRING_PROFILES_ACTIVE="$PROFILE" "$SCRIPT_DIR/start.sh"

echo "[deploy] Deploy complete (profile=$PROFILE)."
