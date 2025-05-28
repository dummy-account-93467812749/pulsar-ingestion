#!/usr/bin/env bash

set -e # Exit immediately if a command exits with a non-zero status.
# set -x # Print commands and their arguments as they are executed (for debugging).

ADMIN_CMD="pulsar-admin"
TENANT="{{ .Values.tenant | default "public" }}"
NAMESPACE="{{ .Values.namespace | default "default" }}"

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

# --- Deploy Functions ---
{{- range $name, $func := .Values.functions }}
echo "Deploying function '{{ $name }}'..."
${ADMIN_CMD} functions create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name {{ $name }} \
  {{- if $func.className }}
  --classname {{ $func.className }} \
  {{- end }}
  {{- /* Assume JARs are mounted in /pulsar/functions/ in the Pulsar container */}} \
  {{- if $func.jar }}
  --jar /pulsar/functions/{{ $func.jar }} \
  {{- else if $func.image }}
  # If image is provided, for local docker-compose, we assume it's a JAR name convention
  --jar /pulsar/functions/{{ $name }}.jar \
  {{- end }}
  {{- if $func.inputs }}
  --inputs "{{ $func.inputs }}" \
  {{- end }}
  {{- if $func.output }}
  --output "{{ $func.output }}" \
  {{- end }}
  {{- if $func.parallelism }}
  --parallelism {{ $func.parallelism }} \
  {{- end }}
  {{- if $func.userConfig }}
  --user-config '{{ $func.userConfig | toJson }}' \
  {{- end }}
  --auto-ack true || echo "Failed to create function '{{ $name }}', it might already exist."
{{- end }}

# --- Deploy Connectors (Sources) ---
{{- range $name, $conn := .Values.connectors }}
echo "Deploying connector '{{ $name }}'..."
# Config file for the connector is expected to be mounted at /pulsar/connectors/<connector_name>_config.yml
# The actual config file from connectors/<connector_name>/<configFile> needs to be mounted there.
CONFIG_FILE_PATH="/pulsar/connectors/{{ $name }}_config.yml"

# Ensure the config content is available for pulsar-admin, if not using config file path directly
# This example assumes config file is mounted and path is used.
# If you need to pass JSON directly (less common for bootstrap.sh):
# --source-config '{{ $conn.config | toJson }}' 
${ADMIN_CMD} sources create \
  --tenant ${TENANT} \
  --namespace ${NAMESPACE} \
  --name {{ $name }} \
  {{- if $conn.isCustom }}
    {{- /* Assume custom connector NARs/JARs are mounted in /pulsar/connectors/ in the Pulsar container */}} \
    {{- if $conn.archive }}
  --archive /pulsar/connectors/{{ $conn.archive }} \
    {{- else if $conn.image }}
    # If custom and image is specified, for local, assume it's an archive name convention
  --archive /pulsar/connectors/{{ $name }}.nar \
    {{- else }}
  # Custom connector needs an archive for bootstrap.sh deployment with pulsar-admin
  echo "WARNING: Custom connector '{{ $name }}' does not specify 'archive' or 'image' for local deployment pack." \
    {{- end }}
  {{- else }}
    {{- if $conn.type }}
  --source-type {{ $conn.type }} \
    {{- end }}
  {{- end }}
  {{- if $conn.topic }}
  --topic-name "{{ $conn.topic }}" \
  {{- end }}
  {{- if $conn.parallelism }}
  --parallelism {{ $conn.parallelism }} \
  {{- end }}
  --processing-guarantees ATLEAST_ONCE \
  --source-config-file ${CONFIG_FILE_PATH} || echo "Failed to create connector '{{ $name }}', it might already exist."
  # Ensure that ${CONFIG_FILE_PATH} which is derived from $name (e.g. /pulsar/connectors/kafka-01_config.yml)
  # correctly corresponds to the 'configFile' field from connector.yaml (e.g. config.sample.yml)
  # This requires a consistent mounting strategy in docker-compose.yml:
  # - ./connectors/kafka-01/config.sample.yml:/pulsar/connectors/kafka-01_config.yml
{{- end }}

echo "------------------------------------------"
echo "Pulsar pipeline bootstrap complete."
echo "Tenant: ${TENANT}, Namespace: ${NAMESPACE}"
echo "Deployed {{ len .Values.functions }} functions and {{ len .Values.connectors }} connectors."
echo "------------------------------------------"

# Keep script running if needed for logs, or exit
# tail -f /dev/null
