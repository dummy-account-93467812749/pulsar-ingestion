!/usr/bin/env bash
set -e

ADMIN_CMD="pulsar-admin"
TENANT="public"
NAMESPACE="default"

echo "Waiting for Pulsar to be ready..."

${ADMIN_CMD} tenants get ${TENANT}
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
