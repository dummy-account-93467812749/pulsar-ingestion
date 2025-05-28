#!/bin/bash
# This script is intended to be run after the Pulsar cluster is up and running.
# It registers functions and connectors based on the pipeline configuration.

# Ensure the script exits on any error
set -e

PULSAR_CONTAINER_NAME="pulsar" # Default Pulsar container name in docker-compose

echo "Waiting for Pulsar service to be ready..."
# Simple wait loop, adjust timeout and check command as needed
# timeout 60s bash -c 'until docker exec $PULSAR_CONTAINER_NAME bin/pulsar-admin tenants get {{ $.Values.tenant }} > /dev/null 2>&1; do echo "Pulsar not ready yet, sleeping..."; sleep 5; done'
echo "Pulsar service is ready. (NOTE: Wait loop temporarily commented out for debugging Helm parsing)"

echo "Starting pipeline registration for tenant {{ $.Values.tenant }} and namespace {{ $.Values.namespace }}..."

{{- range $name, $func := .Values.functions }}
echo "Registering function: {{ $name }}..."
{{/* Assuming the function package (e.g., JAR, Python file, NAR) is available in the Pulsar container. */}}
{{/* The `image` field from pipeline.yaml (e.g., $func.image) is used as a placeholder for the package name/path. */}}
{{/* For example, if image is "ghcr.io/acme/splitter:0.1.0", the actual file might be "splitter.jar" or "splitter.py". */}}
{{/* Users need to ensure these files are mounted or copied into the Pulsar container at the specified paths. */}}
{{/* Common paths could be /pulsar/functions/ or /pulsar/connectors/. */}}
{{/* Adjust --jar, --py, or --go based on the function type. */}}
{{/* For this example, we'll assume --jar and that the image name can be mapped to a JAR file name. */}}
{{/* Example: /pulsar/functions/<name-from-image>.jar */}}

# The actual command might depend on whether it's a JAR, Python file, etc.
# Using a generic placeholder for the package path.
PACKAGE_PATH="/pulsar/functions/{{ $func.image | splitList "/" | last }}" # Example, adjust as needed

docker exec -i $PULSAR_CONTAINER_NAME \
  bin/pulsar-admin functions create \
  --tenant {{ $.Values.tenant }} \
  --namespace {{ $.Values.namespace }} \
  --name {{ $name }} \
  --inputs {{ $func.input }} \
  {{- if $func.outputs }}
  {{- $outputs := $func.outputs | default "" }}
  {{- if kindIs "slice" $outputs -}}
  --output "{{ join "," $outputs }}" \
  {{- else }}
  --output "{{ $outputs }}" \
  {{- end -}}
  {{- end }}
  # This part is highly dependent on the function package type (JAR, Python, Go)
  # and where it's located inside the Pulsar container.
  # Assuming a JAR for now, derived from the image name.
  --jar $PACKAGE_PATH \
  --class-name {{ $func.className }}
echo "Function {{ $name }} registered."
{{- end }}

{{- range $name, $conn := .Values.connectors }}
echo "Registering connector: {{ $name }}..."
{{/* Similar to functions, the `image` field ($conn.image) is a placeholder for the connector package (NAR file). */}}
{{/* Example: /pulsar/connectors/<name-from-image>.nar */}}
CONNECTOR_ARCHIVE_PATH="/pulsar/connectors/{{ $conn.image | splitList "/" | last }}" # Example, adjust

{{- if $conn.source }}
docker exec -i $PULSAR_CONTAINER_NAME \
  bin/pulsar-admin sources create \
  --tenant {{ $.Values.tenant }} \
  --namespace {{ $.Values.namespace }} \
  --name {{ $name }} \
  {{- if $conn.output }}
  --topic-name {{ $conn.output }} \
  {{- end }}
  --archive $CONNECTOR_ARCHIVE_PATH \
  {{- if $conn.configRef }}
  {{/* In a Docker Compose setup, configRef might refer to a file path mounted into the pulsar container */}}
  {{/* or a key in a JSON config file. This template assumes it's a path to a config file. */}}
  {{/* Example: /pulsar/configs/{{ $conn.configRef }}.json - Note: $conn.configRef is evaluated by Helm */}}
  --source-config-file /pulsar/configs/{{ $conn.configRef }}.yaml \
  {{- end }}
echo "Source connector {{ $name }} registered."
{{- else if $conn.sink }}
# Placeholder for sink creation
# docker exec -i $PULSAR_CONTAINER_NAME \
#   bin/pulsar-admin sinks create \
#   ...
echo "Sink connector {{ $name }} registration not fully implemented in this template."
{{- end }}
{{- end }}

echo "Pipeline registration completed for tenant {{ $.Values.tenant }} and namespace {{ $.Values.namespace }}."
echo "You can inspect functions and connectors using pulsar-admin."
echo "Example: docker exec -it $PULSAR_CONTAINER_NAME bin/pulsar-admin functions list --tenant {{ $.Values.tenant }} --namespace {{ $.Values.namespace }}"
echo "Example: docker exec -it $PULSAR_CONTAINER_NAME bin/pulsar-admin sources list --tenant {{ $.Values.tenant }} --namespace {{ $.Values.namespace }}"
