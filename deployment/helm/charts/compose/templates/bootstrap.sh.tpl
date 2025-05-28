#!/usr/bin/env bash

set -e  # Exit immediately on error
# set -x  # Uncomment to debug command execution

ADMIN_CMD="pulsar-admin"
TENANT="{{ default "public" .Values.tenant }}"
NAMESPACE="{{ default "default" .Values.namespace }}"

# Ensure .Values.functions and .Values.connectors are never nil
{{- $functions := default (list) .Values.functions }}
{{- $connectors := default (list) .Values.connectors }}

echo "Waiting for Pulsar to be ready..."
until ${ADMIN_CMD} tenants get "${TENANT}" > /dev/null 2>&1; do
  echo -n "."
  sleep 5
done

echo "Creating tenant '${TENANT}' if missing..."
${ADMIN_CMD} tenants create "${TENANT}" --allowed-clusters standalone \
  || echo "Tenant '${TENANT}' exists or creation failed."

echo "Creating namespace '${TENANT}/${NAMESPACE}' if missing..."
${ADMIN_CMD} namespaces create "${TENANT}/${NAMESPACE}" --clusters standalone \
  || echo "Namespace '${TENANT}/${NAMESPACE}' exists or creation failed."

# --- Deploy Functions ---
{{- range $name, $func := $functions }}
echo "Deploying function '{{ $name }}'..."
${ADMIN_CMD} functions create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "{{ $name }}" \
  {{- if $func.className }}--classname "{{ $func.className }}" \{{ end }}
  {{- if $func.jar }}--jar "/pulsar/functions/{{ $func.jar }}" \{{ else if $func.image }}--jar "/pulsar/functions/{{ $name }}.jar" \{{ end }}
  {{- if $func.inputs }}--inputs "{{ $func.inputs }}" \{{ end }}
  {{- if $func.output }}--output "{{ $func.output }}" \{{ end }}
  {{- if $func.parallelism }}--parallelism {{ $func.parallelism }} \{{ end }}
  {{- if $func.userConfig }}--user-config '{{ $func.userConfig | toJson }}' \{{ end }}
  --auto-ack true \
  || echo "Failed to create function '{{ $name }}'."
{{- end }}

# --- Deploy Connectors ---
{{- range $name, $conn := $connectors }}
echo "Deploying connector '{{ $name }}'..."
CONFIG_FILE="/pulsar/connectors/{{ $name }}_config.yml"
${ADMIN_CMD} sources create \
  --tenant "${TENANT}" \
  --namespace "${NAMESPACE}" \
  --name "{{ $name }}" \
  {{- if $conn.isCustom }}
    {{- if $conn.archive }}--archive "/pulsar/connectors/{{ $conn.archive }}" \{{ else if $conn.image }}--archive "/pulsar/connectors/{{ $name }}.nar" \{{ else }}echo "WARNING: custom '{{ $name }}' missing archive/image." ;\{{ end }}
  {{- else }}
    {{- if $conn.type }}--source-type "{{ $conn.type }}" \{{ end }}
  {{- end }}
  {{- if $conn.topic }}--topic-name "{{ $conn.topic }}" \{{ end }}
  {{- if $conn.parallelism }}--parallelism {{ $conn.parallelism }} \{{ end }}
  --processing-guarantees ATLEAST_ONCE \
  --source-config-file "${CONFIG_FILE}" \
  || echo "Failed to create connector '{{ $name }}'."
{{- end }}

echo "------------------------------------------"
echo "Pipeline bootstrap complete: Tenant=${TENANT}, Namespace=${NAMESPACE}."
echo "Deployed {{ len $functions }} functions and {{ len $connectors }} connectors."
echo "------------------------------------------"

# tail -f /dev/null  # Keep container alive for logs if needed