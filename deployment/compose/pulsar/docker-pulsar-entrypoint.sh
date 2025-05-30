#!/usr/bin/env bash
set -e

echo "======== CUSTOM PULSAR ENTRYPOINT STARTING ========"

# --- Function to run the bootstrap script ---
# This will run in the background and wait for Pulsar to be ready.
run_bootstrap() {
  echo "[Bootstrap Sub-Process] Waiting for Pulsar to be ready for admin operations (checking http://localhost:8080/admin/v2/clusters/standalone)..."
  until curl --output /dev/null --silent --head --fail http://localhost:8080/admin/v2/clusters/standalone; do
    printf '.'
    sleep 3 # Check a bit more frequently
  done
  echo "[Bootstrap Sub-Process] Pulsar is ready for admin operations."

  echo "[Bootstrap Sub-Process] Executing bootstrap script: /pulsar/conf/bootstrap.sh"
  if [ -f "/pulsar/conf/bootstrap.sh" ]; then
    # Run with bash and let it inherit set -e or handle its own errors
    bash /pulsar/conf/bootstrap.sh
    echo "[Bootstrap Sub-Process] Bootstrap script finished."
  else
    echo "[Bootstrap Sub-Process] WARNING: Bootstrap script /pulsar/conf/bootstrap.sh not found."
  fi
  echo "[Bootstrap Sub-Process] Finished."
}

# --- Main Entrypoint Logic ---

# Run the bootstrap function in the background.
# It will wait for Pulsar to be ready before executing the actual bootstrap script.
run_bootstrap &
BOOTSTRAP_BG_PID=$!
echo "Bootstrap process launched in background (PID: ${BOOTSTRAP_BG_PID}). It will run once Pulsar is available."

# Now, start Pulsar standalone IN THE FOREGROUND.
# The `exec` command replaces the current shell process with the pulsar command.
# This makes `pulsar standalone` the main process (PID 1 if this script is PID 1).
# The container will stay alive as long as `pulsar standalone` is running.
# Pulsar's own scripts will handle PULSAR_MEM, PULSAR_EXTRA_OPTS, PULSAR_PREFIX_ variables.

# Common flags for standalone:
# --no-function-worker  (if you don't need functions/connectors initially, but your bootstrap uses them)
# --no-stream-storage (if you don't need Pulsar's stream storage/table service)
# --advertised-address (if running in Docker and need to specify accessible address)
# For your case, you need the functions worker. Stream storage is optional.
PULSAR_STANDALONE_CMD_FLAGS="--advertised-address localhost" # Adjust if needed, e.g. for Docker networking

echo "Starting Pulsar standalone in FOREGROUND with flags: ${PULSAR_STANDALONE_CMD_FLAGS}..."
echo "Pulsar logs will be streamed to stdout/stderr of this container."

# Make sure PULSAR_STANDALONE_CONF points to your desired standalone.conf if you have custom settings,
# e.g., export PULSAR_STANDALONE_CONF=/pulsar/conf/my-standalone.conf
# Default is /pulsar/conf/standalone.conf

exec /pulsar/bin/pulsar standalone ${PULSAR_STANDALONE_CMD_FLAGS}

# --- This part is unlikely to be reached if `exec` is successful ---
# If `exec` fails (e.g., pulsar command not found), the script would continue here.
# If pulsar standalone exits, the container will stop.

# Wait for the background bootstrap process to complete if exec failed or pulsar exited quickly (optional cleanup)
# This is mostly for completeness; if exec works, this script is replaced.
if jobs -p | grep -q "${BOOTSTRAP_BG_PID}"; then
    echo "Pulsar standalone exited or failed to exec. Waiting for bootstrap process (PID: ${BOOTSTRAP_BG_PID}) to finish..."
    wait ${BOOTSTRAP_BG_PID} || echo "Bootstrap process also finished or had an error."
fi

echo "======== CUSTOM PULSAR ENTRYPOINT EXITED (Pulsar standalone process ended or failed to start) ========"