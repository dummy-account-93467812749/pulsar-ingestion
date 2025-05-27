#!/bin/bash
set -e

# Start Pulsar in the background
echo "Starting Pulsar standalone..."
bin/pulsar standalone &
PULSAR_PID=$!
echo "Pulsar standalone started with PID $PULSAR_PID."

# Wait for Pulsar to be healthy
PULSAR_ADMIN_URL="http://localhost:8080"
echo "Waiting for Pulsar to become healthy at $PULSAR_ADMIN_URL..."

PULSAR_READY=0
TIMEOUT_SECONDS=120 # 2 minutes
SECONDS_WAITED=0

# Give Pulsar a few seconds to initialize before starting health checks
sleep 10

while [ $PULSAR_READY -eq 0 ] && [ $SECONDS_WAITED -lt $TIMEOUT_SECONDS ]; do
  if curl -s -f "$PULSAR_ADMIN_URL/admin/v2/brokers/health" > /dev/null 2>&1; then
    PULSAR_READY=1
    echo # Newline after dots
    echo "Pulsar is ready."
  else
    echo -n "." # Print a dot for progress
    sleep 5
    SECONDS_WAITED=$((SECONDS_WAITED + 5)) # Increment by sleep duration
  fi
done

if [ $PULSAR_READY -eq 0 ]; then
  echo # Newline after dots
  echo "ERROR: Pulsar did not become ready within $TIMEOUT_SECONDS seconds."
  # Optionally, kill the Pulsar process if it started but isn't healthy
  # kill $PULSAR_PID
  exit 1
fi

# Execute bootstrap.sh if it exists
BOOTSTRAP_SCRIPT="/pulsar/conf/bootstrap.sh"
if [ -f "$BOOTSTRAP_SCRIPT" ]; then
  echo "Executing Pulsar bootstrap script: $BOOTSTRAP_SCRIPT"
  # Ensure bootstrap.sh is executable if it wasn't already
  # chmod +x "$BOOTSTRAP_SCRIPT" # This might be needed if permissions are not set correctly
  bash "$BOOTSTRAP_SCRIPT"
  echo "Bootstrap script execution finished."
else
  echo "WARNING: Bootstrap script $BOOTSTRAP_SCRIPT not found. Skipping."
fi

echo "Pulsar setup complete. Tailing logs or waiting for Pulsar process to exit."
# Wait for the Pulsar process to ensure the container keeps running
wait $PULSAR_PID
