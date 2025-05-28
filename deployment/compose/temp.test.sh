#!/usr/bin/env bash
set -e

# Command for admin operations executed directly (tenant, namespace, readiness check)
ADMIN_CMD_LOCAL="pulsar-admin"
# Command for admin operations executed via docker exec (connectors, functions)
PULSAR_CONTAINER_NAME="compose-pulsar-1"
ADMIN_CMD_DOCKER_EXEC="docker exec ${PULSAR_CONTAINER_NAME} bin/pulsar-admin"

TENANT="public"
NAMESPACE="default"

echo "Waiting for Pulsar to be ready (using '${ADMIN_CMD_LOCAL}')..."
until ${ADMIN_CMD_LOCAL} tenants get ${TENANT} > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done
echo "Pulsar is ready."

echo "Creating tenant '${TENANT}' if it doesn't exist (using '${ADMIN_CMD_LOCAL}')..."
${ADMIN_CMD_LOCAL} tenants create ${TENANT} --allowed-clusters standalone || echo "Tenant '${TENANT}' already exists or error creating."

echo "Creating namespace '${TENANT}/${NAMESPACE}' if it doesn't exist (using '${ADMIN_CMD_LOCAL}')..."
${ADMIN_CMD_LOCAL} namespaces create ${TENANT}/${NAMESPACE} --clusters standalone || echo "Namespace '${TENANT}/${NAMESPACE}' already exists or error creating."

# --- Deploy Connectors (using '${ADMIN_CMD_DOCKER_EXEC}') ---
echo "Deploying source connector 'kinesis'..."
echo ${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kinesis" \
  --source-type "kinesis" \
  --destination-topic-name "persistent://public/default/kinesis-topic" \
  --source-config-file "/pulsar/build/kinesis-config.yaml" || echo "Failed to create connector 'kinesis', it might already exist."
