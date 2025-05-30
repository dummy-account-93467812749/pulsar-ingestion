#!/usr/bin/env bash
set -e

echo "======== CUSTOM PULSAR ENTRYPOINT STARTING ========"

# Default Pulsar command is to start standalone.
# The base image's entrypoint might have more sophisticated logic to handle PULSAR_MEM, etc.
# We want to run Pulsar standalone in the background, then run our script,
# then bring Pulsar to the foreground (or keep the script alive).

# These are often default flags for pulsar-all standalone, but good to be explicit if functions are critical
# PULSAR_STANDALONE_FLAGS="--no-stream-storage" # Remove --no-function-worker if you had it
PULSAR_STANDALONE_FLAGS="" # Let Pulsar use its defaults for functions worker, etc.

# Start Pulsar standalone in the background
echo "Starting Pulsar standalone in background..."
# The PULSAR_MEM env var should be picked up by the pulsar script itself.
# Any PULSAR_PREFIX_ vars from docker-compose will be applied to conf files.
/pulsar/bin/pulsar standalone ${PULSAR_STANDALONE_FLAGS} &
PULSAR_PID=$!
echo "Pulsar standalone started with PID ${PULSAR_PID}."

# Wait for Pulsar to be healthy using the same healthcheck as in docker-compose
# (or the bootstrap script's initial wait)
echo "Waiting for Pulsar to be ready for admin operations (checking http://localhost:8080/admin/v2/clusters/standalone)..."
until curl --output /dev/null --silent --head --fail http://localhost:8080/admin/v2/clusters/standalone; do
  printf '.'
  sleep 5
done
echo "Pulsar is ready for admin operations."

# Execute the bootstrap script
echo "Executing bootstrap script: /pulsar/conf/bootstrap.sh"
if [ -f "/pulsar/conf/bootstrap.sh" ]; then
  bash /pulsar/conf/bootstrap.sh
  echo "Bootstrap script finished."
else
  echo "WARNING: Bootstrap script /pulsar/conf/bootstrap.sh not found."
fi

echo "Pulsar setup process complete. Pulsar is running with PID ${PULSAR_PID}."
echo "To stop Pulsar, stop the container."

# Keep the entrypoint script running by waiting for the Pulsar process
# This ensures the container doesn't exit if Pulsar is still running.
wait ${PULSAR_PID}

echo "======== CUSTOM PULSAR ENTRYPOINT EXITED ========" # Should not be reached if Pulsar runs indefinitely