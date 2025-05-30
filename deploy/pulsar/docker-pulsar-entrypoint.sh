#!/bin/bash
set -e

echo "Custom Pulsar Entrypoint: Starting Pulsar Standalone..."

# If bootstrap.sh exists and is executable, run it
if [ -f "/pulsar/conf/bootstrap.sh" ] && [ -x "/pulsar/conf/bootstrap.sh" ]; then
  echo "Running bootstrap.sh script..."
  /pulsar/conf/bootstrap.sh
else
  echo "bootstrap.sh not found or not executable. Skipping."
fi

# The original command to start Pulsar standalone, adapted from Pulsar's official Docker images
# This might need adjustments based on the exact behavior of the base image's original entrypoint.
# Typically, the base image's entrypoint does more setup before exec'ing the bin/pulsar command.
# For simplicity, we assume the necessary environment is set up by the base image.
# If you were truly replacing the original entrypoint, you'd replicate its logic here.
# However, since we specify 'entrypoint' in docker-compose, this script *becomes* the primary command.

# Start Pulsar in standalone mode
exec /pulsar/bin/pulsar standalone
