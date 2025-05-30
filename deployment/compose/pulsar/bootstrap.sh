#!/usr/bin/env bash
# set -e # Exit immediately if a command exits with a non-zero status.
# We will manage exit more granularly or rely on the || echo "WARNING..." pattern

# --- Configuration for Logging ---
LOG_DIR_BASE="${PULSAR_BOOTSTRAP_LOG_DIR:-./pulsar-bootstrap-logs}" # Base directory for logs relative to script execution
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="${LOG_DIR_BASE}/pulsar_bootstrap_${TIMESTAMP}.log"

# --- Initial Console Output ---
echo "======== STARTING PULSAR BOOTSTRAP SCRIPT ========"

ADMIN_CMD_LOCAL="pulsar-admin"
PULSAR_CONTAINER_NAME="compose-pulsar-1"
ADMIN_CMD_DOCKER_EXEC="pulsar-admin"

TENANT="public"
NAMESPACE="default"

echo "Waiting for Pulsar to be ready (using '${ADMIN_CMD_LOCAL}')..."
until ${ADMIN_CMD_LOCAL} tenants get ${TENANT} > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done
echo " Pulsar is ready."

echo "Ensuring tenant '${TENANT}' exists (using '${ADMIN_CMD_LOCAL}')..."
${ADMIN_CMD_LOCAL} tenants create ${TENANT} --allowed-clusters standalone || echo "Tenant '${TENANT}' already exists or error creating."

echo "Ensuring namespace '${TENANT}/${NAMESPACE}' exists (using '${ADMIN_CMD_LOCAL}')..."
${ADMIN_CMD_LOCAL} namespaces create ${TENANT}/${NAMESPACE} --clusters standalone || echo "Namespace '${TENANT}/${NAMESPACE}' already exists or error creating."

# --- Deploy Connectors (using '${ADMIN_CMD_DOCKER_EXEC}') ---
echo "Deploying connectors to Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Deploying source connector 'kinesis'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kinesis" \
  --source-type "kinesis" \
  --destination-topic-name "persistent://public/default/kinesis-topic" \
  --source-config-file "/pulsar/build/kinesis-config.yaml" || echo "WARNING: Failed to create connector 'kinesis', it might already exist. Check pulsar-admin output above."

echo "Deploying source connector 'grpc'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "grpc" \
  --archive "/pulsar/connectors/grpc.nar" \
  --destination-topic-name "persistent://public/default/grpc-topic" \
  --source-config-file "/pulsar/build/grpc-config.yaml" || echo "WARNING: Failed to create connector 'grpc', it might already exist. Check pulsar-admin output above."

echo "Deploying source connector 'rabbitmq'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "rabbitmq" \
  --source-type "rabbitmq" \
  --destination-topic-name "persistent://public/default/rabbitmq-topic" \
  --source-config-file "/pulsar/build/rabbitmq-config.yaml" || echo "WARNING: Failed to create connector 'rabbitmq', it might already exist. Check pulsar-admin output above."

echo "Deploying source connector 'http'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "http" \
  --source-type "netty" \
  --destination-topic-name "persistent://public/default/http-netty-input-topic" \
  --source-config-file "/pulsar/build/http-config.yaml" || echo "WARNING: Failed to create connector 'http', it might already exist. Check pulsar-admin output above."

echo "Deploying source connector 'kafka'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kafka" \
  --source-type "kafka" \
  --destination-topic-name "persistent://public/default/kafka-topic" \
  --source-config-file "/pulsar/build/kafka-config.yaml" || echo "WARNING: Failed to create connector 'kafka', it might already exist. Check pulsar-admin output above."

echo "Deploying source connector 'azure-eventhub'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "azure-eventhub" \
  --source-type "azure-eventhub" \
  --destination-topic-name "persistent://public/default/kafka-topic" \
  --source-config-file "/pulsar/build/azure-eventhub-config.yaml" || echo "WARNING: Failed to create connector 'azure-eventhub', it might already exist. Check pulsar-admin output above."

echo "NOTE: Ensure connector config files from 'build/' (relative to compose file) are mounted to '/pulsar/build/' in ${PULSAR_CONTAINER_NAME}."
echo "And custom connector NARs are mounted to '/pulsar/connectors/' in ${PULSAR_CONTAINER_NAME}."

# --- Deploy Functions (using '${ADMIN_CMD_DOCKER_EXEC}') ---
echo "Deploying functions to Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Deploying function 'user-profile-translator'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "user-profile-translator" \
  --classname "com.example.pulsar.functions.cmf.UserProfileTranslator" \
  --jar "/pulsar/build/user-profile-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-azure-events,persistent://${TENANT}/${NAMESPACE}/raw-pulsar-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'user-profile-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'order-record-translator'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "order-record-translator" \
  --classname "com.example.pulsar.functions.cmf.OrderRecordTranslator" \
  --jar "/pulsar/build/order-record-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-grpc-events,persistent://${TENANT}/${NAMESPACE}/raw-rabbitmq-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'order-record-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'inventory-update-translator'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "inventory-update-translator" \
  --classname "com.example.pulsar.functions.cmf.InventoryUpdateTranslator" \
  --jar "/pulsar/build/inventory-update-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-http-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'inventory-update-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'payment-notice-translator'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "payment-notice-translator" \
  --classname "com.example.pulsar.functions.cmf.PaymentNoticeTranslator" \
  --jar "/pulsar/build/payment-notice-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-kafka-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'payment-notice-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'shipment-status-translator'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "shipment-status-translator" \
  --classname "com.example.pulsar.functions.cmf.ShipmentStatusTranslator" \
  --jar "/pulsar/build/shipment-status-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-kinesis-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'shipment-status-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'event-type-splitter'..."
${ADMIN_CMD_DOCKER_EXEC} functions create \
--tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "event-type-splitter" \
  --classname "com.example.pulsar.filterer.EventTypeSplitter" \
  --jar "/pulsar/build/filterer.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'event-type-splitter', it might already exist. Check pulsar-admin output above."

echo "NOTE: Ensure function NARs are mounted to '/pulsar/build/' in your Pulsar container (${PULSAR_CONTAINER_NAME})."

echo "------------------------------------------"
echo "Pulsar pipeline bootstrap complete for Compose."
echo "Target Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Review notes above for required Docker volume mounts into ${PULSAR_CONTAINER_NAME}."
echo "Bootstrap logs are in: ${LOG_FILE}"
echo "------------------------------------------"
