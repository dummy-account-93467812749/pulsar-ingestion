#!/usr/bin/env bash
set -e

ADMIN_CMD="pulsar-admin"
TENANT="acme"
NAMESPACE="ingest"

echo "Waiting for Pulsar to be ready..."
until ${ADMIN_CMD} tenants get ${TENANT} > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done
echo "Pulsar is ready."

echo "Creating tenant '${TENANT}' if it doesn't exist..."
${ADMIN_CMD} tenants create ${TENANT} --allowed-clusters standalone || echo "Tenant '${TENANT}' already exists or error creating."

echo "Creating namespace '${TENANT}/${NAMESPACE}' if it doesn't exist..."
${ADMIN_CMD} namespaces create ${TENANT}/${NAMESPACE} --clusters standalone || echo "Namespace '${TENANT}/${NAMESPACE}' already exists or error creating."

# --- Deploy Connectors ---
echo "Deploying source connector 'kinesis'..."
${ADMIN_CMD} \
  sources \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kinesis" \
  --source-type "kinesis" \
  --topic-name "persistent://public/default/kinesis-topic" \
  --source-config '{\"awsEndpoint\":\"http://localstack:4566\",\"dynamoEndpoint\":\"http://localstack:4566\",\"cloudwatchEndpoint\":\"http://localstack:4566\",\"awsRegion\":\"us-east-1\",\"awsKinesisStreamName\":\"my-kinesis-stream\",\"awsCredentialPluginName\":\"\",\"awsCredentialPluginParam\":\"{"accessKey":"test","secretKey":"test"}\",\"applicationName\":\"pulsar-kinesis-local\",\"initialPositionInStream\":\"TRIM_HORIZON\",\"checkpointInterval\":60000,\"backoffTime\":3000,\"numRetries\":3,\"receiveQueueSize\":\"1000%\"}' || echo "Failed to create connector 'kinesis', it might already exist."

echo "Deploying source connector 'grpc'..."
${ADMIN_CMD} \
  sources \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "grpc" \
  --archive "/pulsar/connectors/grpc.nar" \
  --topic-name "persistent://public/default/grpc-topic" \
  --source-config '{\"grpcEndpoint\":\"localhost:50051\"}' || echo "Failed to create connector 'grpc', it might already exist."

echo "Deploying source connector 'rabbitmq'..."
${ADMIN_CMD} \
  sources \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "rabbitmq" \
  --source-type "rabbitmq" \
  --topic-name "persistent://public/default/rabbitmq-topic" \
  --source-config '{\"connectionName\":\"pulsar-rabbitmq-source\",\"host\":\"localhost\",\"port\":5672,\"virtualHost\":\"/\",\"queueName\":\"your-rabbitmq-queue\"}' || echo "Failed to create connector 'rabbitmq', it might already exist."

echo "Deploying source connector 'kafka'..."
${ADMIN_CMD} \
  sources \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "kafka" \
  --source-type "kafka" \
  --topic-name "persistent://public/default/kafka-topic" \
  --source-config '{\"bootstrapServers\":\"your-kafka-broker:9092\",\"groupId\":\"pulsar-kafka-group\",\"topic\":\"your-kafka-topic-to-read-from\",\"fetchMessageMaxBytes\":1048576,\"autoCommitEnabled\":true}' || echo "Failed to create connector 'kafka', it might already exist."

echo "Deploying source connector 'pulsar'..."
${ADMIN_CMD} \
  sources \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "pulsar" \
  --source-type "pulsar" \
  --topic-name "persistent://public/default/pulsar-input-topic" \
  --source-config '{\"sourceServiceUrl\":\"pulsar://source-cluster-host:6650\",\"sourceTopicName\":\"persistent://public/default/some-other-topic\",\"subscriptionName\":\"pulsar-source-subscription\"}' || echo "Failed to create connector 'pulsar', it might already exist."

# --- Deploy Functions ---
echo "Deploying function 'user-profile-translator'..."
${ADMIN_CMD} \
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
${ADMIN_CMD} \
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
${ADMIN_CMD} \
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
${ADMIN_CMD} \
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
${ADMIN_CMD} \
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
${ADMIN_CMD} \
  functions \
  create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name "event-type-splitter" \
  --classname "com.example.pulsar.functions.routing.EventTypeSplitter" \
  --jar "/pulsar/functions/event-type-splitter.jar" \
  --inputs "common-events" \
  --auto-ack true || echo "Failed to create function 'event-type-splitter', it might already exist."

echo "------------------------------------------"
echo "Pulsar pipeline bootstrap complete."
echo "Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Deployed functions and connectors."
echo "------------------------------------------"
