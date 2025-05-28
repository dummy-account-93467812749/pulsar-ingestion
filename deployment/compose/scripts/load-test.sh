#!/bin/bash
set -e

# Pulsar connection details
PULSAR_ADMIN_URL="http://localhost:8080"
PULSAR_BROKER_URL="pulsar://localhost:6650"
PULSAR_CLIENT_CONF_OPTS="--url $PULSAR_BROKER_URL --admin-url $PULSAR_ADMIN_URL"

# LocalStack Kinesis details
KINESIS_ENDPOINT_URL="http://localhost:4566"
KINESIS_STREAM_NAME="raw-kinesis-events"

# Kafka details
KAFKA_BROKER_LIST="localhost:19092"
KAFKA_TOPIC_NAME="raw-kafka-events"

# RabbitMQ details
RABBITMQ_HOST="localhost"
RABBITMQ_PORT="15672" # Management port for rabbitmqadmin
RABBITMQ_USER="user"
RABBITMQ_PASS="password"
RABBITMQ_ROUTING_KEY="raw-rabbitmq-events"

# HTTP Source details
HTTP_SOURCE_URL="http://localhost:38080/raw-http-events" # Assuming HTTP source listens on port 38080 and path /raw-http-events

# Pulsar Source details
PULSAR_SOURCE_TOPIC="persistent://acme/ingest/raw-pulsar-events"

echo "Waiting for Pulsar to be ready..."
until curl -s -f $PULSAR_ADMIN_URL/admin/v2/brokers/health > /dev/null; do
  echo "Pulsar not ready yet, sleeping for 5 seconds..."
  sleep 5
done
echo "Pulsar is ready!"
echo

# --- Send to Kinesis (LocalStack) ---
echo "Attempting to create Kinesis stream '$KINESIS_STREAM_NAME' if it doesn't exist..."
aws --endpoint-url=$KINESIS_ENDPOINT_URL kinesis create-stream --stream-name $KINESIS_STREAM_NAME --shard-count 1 > /dev/null 2>&1 || echo "Kinesis stream '$KINESIS_STREAM_NAME' likely already exists or another error occurred."
echo "Sending message to Kinesis stream '$KINESIS_STREAM_NAME'..."
aws --endpoint-url=$KINESIS_ENDPOINT_URL kinesis put-record \
  --stream-name "$KINESIS_STREAM_NAME" \
  --partition-key "ship123" \
  --data '{"shipId": "SHIP-K1", "status": "DISPATCHED", "dispatchTime": 1620100000}'
echo "Message sent to Kinesis."
echo
sleep 2

# --- Send to Kafka ---
echo "Attempting to create Kafka topic '$KAFKA_TOPIC_NAME' if it doesn't exist (requires Kafka CLI tools in PATH)..."
# This command might fail if topic exists or Kafka tools aren't present.
# Consider adding --if-not-exists if your kafka-topics.sh version supports it.
# kafka-topics.sh --bootstrap-server $KAFKA_BROKER_LIST --create --topic $KAFKA_TOPIC_NAME --partitions 1 --replication-factor 1 || echo "Kafka topic '$KAFKA_TOPIC_NAME' creation skipped or failed."
echo "Sending message to Kafka topic '$KAFKA_TOPIC_NAME'..."
echo '{"txnId": "TXN-K1", "amount": 19.99, "currency": "USD", "time": "2025-05-27T10:30:00Z"}' | kafka-console-producer.sh \
  --broker-list "$KAFKA_BROKER_LIST" \
  --topic "$KAFKA_TOPIC_NAME"
echo "Message sent to Kafka."
echo
sleep 2

# --- Placeholder for Azure Event Hubs ---
echo "Sending to Azure Event Hubs (raw-azure-events) - Placeholder"
# To send to Azure Event Hubs, you would typically use:
# 1. Azure CLI:
#    az login
#    az eventhubs event send --resource-group <RG_NAME> --namespace-name <NAMESPACE_NAME> --eventhub-name raw-azure-events --data '{"uid": "AZ-USER-1", "name": "Azure User", "created": 1620000100}'
# 2. Other SDKs/tools specific to Azure.
# This requires Azure CLI to be installed and configured.
echo "Skipping Azure Event Hubs."
echo
sleep 2

# --- Send to RabbitMQ ---
echo "Sending message to RabbitMQ (routing_key: '$RABBITMQ_ROUTING_KEY')..."
# This assumes rabbitmqadmin is available and the Pulsar connector has set up the necessary exchange/queue.
# The default exchange is an empty string. If publishing to default exchange, routing_key is the queue name.
# If the connector uses a topic exchange, the routing_key might be different or empty if fanout.
# Let's assume the connector creates a queue named 'raw-rabbitmq-events' and binds it appropriately.
if command -v rabbitmqadmin &> /dev/null
then
    rabbitmqadmin -H $RABBITMQ_HOST -P $RABBITMQ_PORT -u $RABBITMQ_USER -p $RABBITMQ_PASS publish \
      exchange=amq.default \
      routing_key="$RABBITMQ_ROUTING_KEY" \
      payload='{"orderId": "RABBIT-ORD-1", "items": ["itemA"], "placedAt": "2025-05-28T11:00:00Z"}'
    echo "Message sent to RabbitMQ."
else
    echo "rabbitmqadmin command not found. Skipping RabbitMQ message."
fi
echo
sleep 2

# --- Send to HTTP Source ---
echo "Sending message via HTTP to '$HTTP_SOURCE_URL'..."
# This assumes an HTTP source connector is running and configured to listen on the specified URL
# and map requests to the 'raw-http-events' Pulsar topic.
curl -X POST -H "Content-Type: application/json" \
  -d '{"sku": "HTTP-SKU-1", "qty": 75, "updateTime": 1620050100}' \
  "$HTTP_SOURCE_URL"
echo
echo "Message sent via HTTP."
echo
sleep 2

# --- Placeholder for gRPC Source ---
echo "Sending to gRPC Source (raw-grpc-events) - Placeholder"
# To send to a gRPC source, you would typically use a generated gRPC client or a tool like grpcurl.
# The exact command depends on the .proto service definition.
# Example (replace placeholders):
# grpcurl -plaintext \
#   -d '{"orderId": "GRPC-ORD-1", "items": ["itemX"], "placedAt": "2025-05-29T12:00:00Z"}' \
#   localhost:GRPC_SOURCE_PORT your.package.YourService/YourMethod
# GRPC_SOURCE_PORT would be the port the gRPC source connector is listening on (e.g., 38081).
echo "Skipping gRPC Source."
echo
sleep 2

# --- Send to Pulsar Source Topic ---
echo "Sending message to Pulsar topic '$PULSAR_SOURCE_TOPIC'..."
# Ensure pulsar-client is installed and configured, or available in PATH.
pulsar-client produce "$PULSAR_SOURCE_TOPIC" \
  --messages '{"uid": "PULSAR-USER-1", "name": "Pulsar User", "created": 1620000200}' \
  $PULSAR_CLIENT_CONF_OPTS
echo "Message sent to Pulsar topic."
echo

echo "Load test script finished."
echo "Note: Some message sending steps depend on external CLI tools (aws, kafka-console-producer, rabbitmqadmin, pulsar-client, curl)."
echo "Ensure these are installed and in your PATH or the script is run in an environment where they are available."
echo "For Kafka, topic creation might need to be handled separately if auto-creation is disabled."
echo "For RabbitMQ, the connector is expected to declare the queue/exchange."
