#!/usr/bin/env bash
#
# start.sh — start the Halo Spring Boot jar as a background process.
#
# Usage:
#   ./start.sh [profile]          # profile defaults to $SPRING_PROFILES_ACTIVE or "prod"
#
# Environment overrides:
#   SPRING_PROFILES_ACTIVE   active Spring profile (dev|test|prod|...)
#   JAVA_OPTS                JVM options (defaults to container-aware heap sizing)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_NAME="halo"
PROFILE="${SPRING_PROFILES_ACTIVE:-${1:-prod}}"
JAVA_OPTS="${JAVA_OPTS:--XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0}"
LIB_DIR="$SCRIPT_DIR/build/libs"
PID_FILE="$SCRIPT_DIR/${APP_NAME}.pid"
LOG_DIR="$SCRIPT_DIR/logs"

# Pick the bootable jar, ignoring the Gradle "-plain" jar.
JAR_FILE="$(ls -1 "$LIB_DIR"/halo-*.jar 2>/dev/null | grep -v -- '-plain.jar' | head -n 1 || true)"
if [[ -z "$JAR_FILE" ]]; then
  echo "[start] No bootable jar found in $LIB_DIR." >&2
  echo "[start] Build it first: ./deploy.sh   (or ./gradlew clean build -x test)" >&2
  exit 1
fi

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[start] $APP_NAME is already running (PID $(cat "$PID_FILE"))."
  exit 0
fi

mkdir -p "$LOG_DIR"
echo "[start] Starting $APP_NAME (profile=$PROFILE) from $(basename "$JAR_FILE")"

# shellcheck disable=SC2086
nohup java $JAVA_OPTS \
  -Dspring.profiles.active="$PROFILE" \
  -Djava.security.egd=file:/dev/./urandom \
  -jar "$JAR_FILE" > "$LOG_DIR/console.log" 2>&1 &

echo $! > "$PID_FILE"
echo "[start] Started with PID $(cat "$PID_FILE"). Logs: $LOG_DIR/console.log"
