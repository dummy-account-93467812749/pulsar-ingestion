#!/usr/bin/env bash
set -e

# --------- LOGGING CONFIGURATION ---------
# Where to store bootstrap and pulsar logs
LOG_DIR="${PULSAR_BOOTSTRAP_LOG_DIR:-/pulsar/logs}"
mkdir -p "${LOG_DIR}"
BOOT_LOG="${LOG_DIR}/bootstrap.log"
PULSAR_LOG="${LOG_DIR}/pulsar.log"

# Start fresh
: > "${BOOT_LOG}"
: > "${PULSAR_LOG}"

echo "======== CUSTOM PULSAR ENTRYPOINT STARTING ========"
echo "Bootstrap logs will be at ${BOOT_LOG}"
echo "Pulsar logs will be at ${PULSAR_LOG}"

# --------- BOOTSTRAP FUNCTION ---------
run_bootstrap() {
  echo "[Bootstrap] Waiting for Pulsar admin API..." >>"${BOOT_LOG}" 2>&1
  until curl --silent --fail --head http://localhost:8080/admin/v2/clusters/standalone > /dev/null; do
    printf '.' >>"${BOOT_LOG}" 2>&1
    sleep 3
  done
  echo "\n[Bootstrap] Pulsar is ready. Executing bootstrap script..." >>"${BOOT_LOG}" 2>&1

  if [ -f "/pulsar/conf/bootstrap.sh" ]; then
    bash /pulsar/conf/bootstrap.sh >>"${BOOT_LOG}" 2>&1
    echo "[Bootstrap] Script finished." >>"${BOOT_LOG}" 2>&1
  else
    echo "[Bootstrap] WARNING: /pulsar/conf/bootstrap.sh not found." >>"${BOOT_LOG}" 2>&1
  fi
  echo "[Bootstrap] Done." >>"${BOOT_LOG}" 2>&1
}

# Launch bootstrap in background
run_bootstrap &
BOOT_PID=$!
echo "Bootstrap process started (PID ${BOOT_PID})"

echo "Starting Pulsar standalone in background..."
# Run Pulsar standalone, redirecting its logs to file, so container stdout stays focused on bootstrap
/pulsar/bin/pulsar standalone \
  --advertised-address localhost \
  >>"${PULSAR_LOG}" 2>&1 &
PULSAR_PID=$!
echo "Pulsar standalone started (PID ${PULSAR_PID})"

echo "Container will now stream bootstrap logs. Use 'docker logs' to follow."
echo "======== TAILING BOOTSTRAP LOG ========"

# Tail bootstrap log so container output shows only bootstrap messages
exec tail -F "${BOOT_LOG}"
