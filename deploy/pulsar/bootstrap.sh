#!/bin/bash
set -e

echo "Custom Pulsar Bootstrap: Performing initial setup..."

# Example: Create a tenant and namespace if they don't exist
# Wait for Pulsar to be ready (simple check)
# This is a very basic readiness check, a more robust one would be better
until curl -s -f http://localhost:8080/admin/v2/clusters/standalone; do
  echo "Waiting for Pulsar admin interface to be ready..."
  sleep 5
done
echo "Pulsar admin interface is ready."

# Pulsar admin commands
# Note: pulsar-admin might not be in PATH immediately or might require 'bin/pulsar-admin'
# Adjust the path to pulsar-admin if necessary.
PULSAR_ADMIN_CMD="/pulsar/bin/pulsar-admin"

echo "Checking/creating public tenant..."
if ! $PULSAR_ADMIN_CMD tenants get public >/dev/null 2>&1; then
  $PULSAR_ADMIN_CMD tenants create public --admin-roles admin --allowed-clusters standalone
  echo "Tenant 'public' created."
else
  echo "Tenant 'public' already exists."
fi

echo "Checking/creating public/default namespace..."
if ! $PULSAR_ADMIN_CMD namespaces get public/default >/dev/null 2>&1; then
  $PULSAR_ADMIN_CMD namespaces create public/default --clusters standalone
  echo "Namespace 'public/default' created."
else
  echo "Namespace 'public/default' already exists."
fi

echo "Bootstrap script finished."
# Add any other commands here, like creating topics, setting permissions, etc.
# e.g., $PULSAR_ADMIN_CMD topics create-partitioned-topic persistent://public/default/my-partitioned-topic -p 3
