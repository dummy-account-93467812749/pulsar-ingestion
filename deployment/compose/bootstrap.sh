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
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kinesis" \
  --source-type "kinesis" \
  --destination-topic-name "persistent://public/default/kinesis-topic" \
  --source-config-file "/pulsar/build/kinesis-config.yaml" || echo "Failed to create connector 'kinesis', it might already exist."

echo "Deploying source connector 'grpc'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "grpc" \
  --archive "/pulsar/connectors/grpc.nar" \
  --destination-topic-name "persistent://public/default/grpc-topic" \
  --source-config-file "/pulsar/build/grpc-config.yaml" || echo "Failed to create connector 'grpc', it might already exist."

echo "Deploying source connector 'rabbitmq'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "rabbitmq" \
  --source-type "rabbitmq" \
  --destination-topic-name "persistent://public/default/rabbitmq-topic" \
  --source-config-file "/pulsar/build/rabbitmq-config.yaml" || echo "Failed to create connector 'rabbitmq', it might already exist."

echo "Deploying source connector 'http'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "http" \
  --source-type "netty" \
  --destination-topic-name "persistent://public/default/http-netty-input-topic" \
  --source-config-file "/pulsar/build/http-config.yaml" || echo "Failed to create connector 'http', it might already exist."

echo "Deploying source connector 'kafka'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kafka" \
  --source-type "kafka" \
  --destination-topic-name "persistent://public/default/kafka-topic" \
  --source-config-file "/pulsar/build/kafka-config.yaml" || echo "Failed to create connector 'kafka', it might already exist."

echo "Deploying source connector 'azure-eventhub'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "azure-eventhub" \
  --source-type "azure-eventhub" \
  --destination-topic-name "persistent://public/default/eventhub-amqp-input-topic" \
  --source-config-file "/pulsar/build/azure-eventhub-config.yaml" || echo "Failed to create connector 'azure-eventhub', it might already exist."

echo "Deploying source connector 'pulsar'..."
${ADMIN_CMD_DOCKER_EXEC} \
  source \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "pulsar" \
  --source-type "pulsar" \
  --destination-topic-name "persistent://public/default/pulsar-input-topic" \
  --source-config-file "/pulsar/build/pulsar-config.yaml" || echo "Failed to create connector 'pulsar', it might already exist."

echo "NOTE: Ensure connector config files from 'deployment/compose/build/' are mounted to '/pulsar/build/' in your Pulsar container (${PULSAR_CONTAINER_NAME})."
echo "And custom connector NARs are mounted to '/pulsar/connectors/' in ${PULSAR_CONTAINER_NAME}."

# --- Deploy Functions (using '${ADMIN_CMD_DOCKER_EXEC}') ---
echo "Deploying function 'user-profile-translator'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "user-profile-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.UserProfileTranslator" \
  --jar "/pulsar/functions/user-profile-translator.jar" \
  --inputs "raw-azure-events,raw-pulsar-events" \
  --output "common-events" \
  --auto-ack true || echo "Failed to create function 'user-profile-translator', it might already exist."

echo "Deploying function 'order-record-translator'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "order-record-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.OrderRecordTranslator" \
  --jar "/pulsar/functions/order-record-translator.jar" \
  --inputs "raw-grpc-events,raw-rabbitmq-events" \
  --output "common-events" \
  --auto-ack true || echo "Failed to create function 'order-record-translator', it might already exist."

echo "Deploying function 'inventory-update-translator'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "inventory-update-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.InventoryUpdateTranslator" \
  --jar "/pulsar/functions/inventory-update-translator.jar" \
  --inputs "raw-http-events" \
  --output "common-events" \
  --auto-ack true || echo "Failed to create function 'inventory-update-translator', it might already exist."

echo "Deploying function 'payment-notice-translator'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "payment-notice-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.PaymentNoticeTranslator" \
  --jar "/pulsar/functions/payment-notice-translator.jar" \
  --inputs "raw-kafka-events" \
  --output "common-events" \
  --auto-ack true || echo "Failed to create function 'payment-notice-translator', it might already exist."

echo "Deploying function 'shipment-status-translator'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "shipment-status-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.ShipmentStatusTranslator" \
  --jar "/pulsar/functions/shipment-status-translator.jar" \
  --inputs "raw-kinesis-events" \
  --output "common-events" \
  --auto-ack true || echo "Failed to create function 'shipment-status-translator', it might already exist."

echo "Deploying function 'event-type-splitter'..."
${ADMIN_CMD_DOCKER_EXEC} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "event-type-splitter" \
  --classname "com.example.pulsar.functions.routing.EventTypeSplitter" \
  --jar "/pulsar/functions/event-type-splitter.jar" \
  --inputs "common-events" \
  --auto-ack true || echo "Failed to create function 'event-type-splitter', it might already exist."

echo "NOTE: Ensure function JARs are mounted to '/pulsar/functions/' in your Pulsar container (${PULSAR_CONTAINER_NAME})."

echo "------------------------------------------"
echo "Pulsar pipeline bootstrap complete for Compose."
echo "Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Review notes above for required Docker volume mounts into ${PULSAR_CONTAINER_NAME}."
echo "------------------------------------------"
