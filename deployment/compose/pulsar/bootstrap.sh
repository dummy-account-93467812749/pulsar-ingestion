#!/usr/bin/env bash
# set -e # Exit immediately if a command exits with a non-zero status.
# We will manage exit more granularly or rely on the || echo "WARNING..." pattern

# --- Configuration for Logging ---
LOG_DIR_BASE="${PULSAR_BOOTSTRAP_LOG_DIR:-/pulsar/logs/bootstrap}" # Base directory for logs
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="${LOG_DIR_BASE}/pulsar_bootstrap_${TIMESTAMP}.log"

# --- Initial Console Output ---
echo "======== STARTING PULSAR BOOTSTRAP SCRIPT ========"
echo "Attempting to create log directory: ${LOG_DIR_BASE}"
mkdir -p "${LOG_DIR_BASE}"
if [ $? -ne 0 ]; then
  echo "ERROR: Could not create log directory ${LOG_DIR_BASE}. Exiting."
  exit 1
fi
echo "Logging all output to: ${LOG_FILE}"
echo "You can tail this file in another terminal: tail -f \"${LOG_FILE}\""
echo "----------------------------------------------------"

# --- Redirect all subsequent stdout and stderr to the log file ---
#exec &> "${LOG_FILE}"

# --- Script Body (now writing to log file) ---
#set -e # Exit immediately if a command exits with a non-zero status.

echo "======== STARTING PULSAR BOOTSTRAP SCRIPT (LOGGING TO FILE) ========"
echo "Script started at: $(date)"
echo "Log file: ${LOG_FILE}"
echo "----------------------------------------------------"


# The pulsar-admin binary is in /pulsar/bin, which should be in PATH
# If not, use /pulsar/bin/pulsar-admin
ADMIN_CMD="pulsar-admin"

TENANT="${PULSAR_TENANT:-public}" # Use environment variable or default
NAMESPACE="${PULSAR_NAMESPACE:-default}" # Use environment variable or default
CLUSTER="${PULSAR_CLUSTER:-standalone}" # Pulsar cluster name, usually 'standalone' for this setup

echo "Effective TENANT: ${TENANT}"
echo "Effective NAMESPACE: ${NAMESPACE}"
echo "Effective CLUSTER: ${CLUSTER}"
echo "ADMIN_CMD: ${ADMIN_CMD}"


echo "Waiting for Pulsar broker to be ready..."
# A more robust check might involve checking a specific broker health endpoint or brokers list
# For standalone, checking the list of clusters is a good indicator.
until ${ADMIN_CMD} clusters list; do # Output will go to log
  echo -n "." # This dot will also go to log
  sleep 5
done
echo " Pulsar broker seems ready."

echo "Creating tenant '${TENANT}' if it doesn't exist..."
if ! ${ADMIN_CMD} tenants get "${TENANT}"; then # Output of 'tenants get' (if any on error) goes to log
  echo "Tenant '${TENANT}' does not exist. Creating..."
  ${ADMIN_CMD} tenants create "${TENANT}" --allowed-clusters "${CLUSTER}"
  echo "Tenant '${TENANT}' created."
else
  echo "Tenant '${TENANT}' already exists. Output of 'tenants get' was:"
  ${ADMIN_CMD} tenants get "${TENANT}" # Show details if it exists
fi

echo "Creating namespace '${TENANT}/${NAMESPACE}' if it doesn't exist..."
if ! ${ADMIN_CMD} namespaces get "${TENANT}/${NAMESPACE}"; then # Output of 'namespaces get' (if any on error) goes to log
  echo "Namespace '${TENANT}/${NAMESPACE}' does not exist. Creating..."
  ${ADMIN_CMD} namespaces create "${TENANT}/${NAMESPACE}" --clusters "${CLUSTER}"
  echo "Namespace '${TENANT}/${NAMESPACE}' created."
else
  echo "Namespace '${TENANT}/${NAMESPACE}' already exists. Output of 'namespaces get' was:"
  ${ADMIN_CMD} namespaces get "${TENANT}/${NAMESPACE}" # Show details if it exists
fi

echo "--- Deploying Connectors ---"

# Ensure your config files and NAR files are in /pulsar/build/ or /pulsar/connectors/ as appropriate
# Based on your Dockerfile, things in `build/` go to `/pulsar/build/`

echo "Deploying source connector 'kinesis'..."
${ADMIN_CMD} source create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "kinesis-source" \
  --source-type "kinesis" \
  --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/kinesis-ingest" \
  --source-config-file "/pulsar/build/kinesis-config.yaml" || echo "WARNING: Failed to create/update connector 'kinesis-source', it might already exist or config is missing. Check pulsar-admin output above."

# Note: The default http-source is deprecated. `netty` is preferred.
# If you want the built-in one for simple tests:
# echo "Deploying source connector 'http-source' (built-in, deprecated)..."
# ${ADMIN_CMD} source create \
#   --tenant "${TENANT}" \
#   --namespace "${NAMESPACE}" \
#   --name "http-deprecated-source" \
#   --source-type "http" \
#   --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/http-deprecated-ingest" \
#   --source-config '{ "port": 8888 }' || echo "WARNING: Failed to create/update connector 'http-deprecated-source'."

# For a NAR-based HTTP source (like the one from StreamNative Hub or your custom one)
# Assuming you have a netty based http source config.
echo "Deploying source connector 'http-netty-source'..."
${ADMIN_CMD} source create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "http-netty-source" \
  --source-type "netty" \
  --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/http-netty-ingest" \
  --source-config-file "/pulsar/build/http-config.yaml" || echo "WARNING: Failed to create/update connector 'http-netty-source'. Check pulsar-admin output above."


echo "Deploying source connector 'grpc-source'..."
# Assuming grpc.nar is in /pulsar/build/connectors/ or just /pulsar/build/
# Let's assume it's in /pulsar/build/grpc.nar based on COPY build/
# YOU NEED TO PROVIDE THE CLASSNAME for NAR based connectors. Placeholder used.
GRPC_CLASSNAME_PLACEHOLDER="your.grpc.source.classname"
echo "Attempting to deploy grpc-source with classname: ${GRPC_CLASSNAME_PLACEHOLDER}"
if [ "${GRPC_CLASSNAME_PLACEHOLDER}" == "your.grpc.source.classname" ]; then
    echo "WARNING: GRPC_CLASSNAME_PLACEHOLDER is not set. grpc-source deployment will likely fail or is skipped."
    echo "Skipping grpc-source deployment due to placeholder classname."
else
    ${ADMIN_CMD} source create \
      --tenant "${TENANT}" \
      --namespace "${NAMESPACE}" \
      --name "grpc-source" \
      --archive "/pulsar/build/grpc.nar" \
      --classname "${GRPC_CLASSNAME_PLACEHOLDER}" \
      --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/grpc-ingest" \
      --source-config-file "/pulsar/build/grpc-config.yaml" || echo "WARNING: Failed to create/update connector 'grpc-source'. Check pulsar-admin output above."
fi


echo "Deploying source connector 'kafka-source'..."
${ADMIN_CMD} source create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "kafka-source" \
  --source-type "kafka" \
  --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/kafka-ingest" \
  --source-config-file "/pulsar/build/kafka-config.yaml" || echo "WARNING: Failed to create/update connector 'kafka-source'. Check pulsar-admin output above."

echo "Deploying source connector 'rabbitmq-source'..."
${ADMIN_CMD} source create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "rabbitmq-source" \
  --source-type "rabbitmq" \
  --destination-topic-name "persistent://${TENANT}/${NAMESPACE}/rabbitmq-ingest" \
  --source-config-file "/pulsar/build/rabbitmq-config.yaml" || echo "WARNING: Failed to create/update connector 'rabbitmq-source'. Check pulsar-admin output above."

# Removed redundant/similarly named connectors for brevity. Add them back if distinct.
# For example, "kinesis" and "kinesis-source" are likely the same.
# "azure-eventhub-source" and "azure-eventhub" also seem redundant.
# Pick one name and stick to it. I've used "-source" suffix for clarity.

echo "--- Deploying Functions ---"
# Ensure function NARs are in /pulsar/build/

echo "Deploying function 'user-profile-translator'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "user-profile-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.UserProfileTranslator" \
  --jar "/pulsar/build/user-profile-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-azure-events,persistent://${TENANT}/${NAMESPACE}/raw-pulsar-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'user-profile-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'order-record-translator'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "order-record-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.OrderRecordTranslator" \
  --jar "/pulsar/build/order-record-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-grpc-events,persistent://${TENANT}/${NAMESPACE}/raw-rabbitmq-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'order-record-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'inventory-update-translator'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "inventory-update-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.InventoryUpdateTranslator" \
  --jar "/pulsar/build/inventory-update-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-http-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'inventory-update-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'payment-notice-translator'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "payment-notice-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.PaymentNoticeTranslator" \
  --jar "/pulsar/build/payment-notice-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-kafka-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'payment-notice-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'shipment-status-translator'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "shipment-status-translator" \
  --classname "com.example.pulsar.functions.transforms.translators.ShipmentStatusTranslator" \
  --jar "/pulsar/build/shipment-status-translator.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/raw-kinesis-events" \
  --output "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true || echo "WARNING: Failed to create function 'shipment-status-translator', it might already exist. Check pulsar-admin output above."

echo "Deploying function 'event-type-splitter'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "event-type-splitter" \
  --classname "com.example.pulsar.functions.routing.EventTypeSplitter" \
  --jar "/pulsar/build/event-type-splitter.nar" \
  --inputs "persistent://${TENANT}/${NAMESPACE}/common-events" \
  --auto-ack true \
  # For splitter/router functions, you often specify --output-topic-type or use a function config for dynamic routing
  # For example, if it routes to multiple topics based on event type.
  # --topic-pattern "persistent://${TENANT}/${NAMESPACE}/split-events-.*" # Example if using topic patterns
  || echo "WARNING: Failed to create function 'event-type-splitter', it might already exist. Check pulsar-admin output above."


echo "------------------------------------------"
echo "Pulsar pipeline bootstrap complete."
echo "Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Ensure connector configs & NARs are in '/pulsar/build/' (copied from local 'pulsar/build/')."
echo "And any custom connector NARs are in '/pulsar/connectors/' (if you add a COPY for that path)."
echo "Script finished at: $(date)"
echo "======== FINISHED PULSAR BOOTSTRAP SCRIPT (LOGGED TO FILE) ========"

# End of script. Output redirection via 'exec' remains in effect until script exits.