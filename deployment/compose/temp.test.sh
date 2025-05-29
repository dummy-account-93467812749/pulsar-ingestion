#!/usr/bin/env bash
set -euo pipefail

# —— CONFIG ——
PULSAR_CONTAINER_NAME="compose-pulsar-1"
# Note: -i is not strictly needed for consume or admin, but harmless if PULSAR_CLI_CMD is reused.
PULSAR_CLI_CMD=(docker exec "$PULSAR_CONTAINER_NAME" bin/pulsar-client)
PULSAR_ADMIN_CMD=(docker exec "$PULSAR_CONTAINER_NAME" bin/pulsar-admin)

INPUT_TOPIC="persistent://public/default/common-events"
OUTPUT_PREFIX="persistent://public/default/fn-split"
CONSUME_TIMEOUT_S=10 # How long the watcher waits for the consumer
INITIAL_SLEEP_S=4
# --------------

# 1) Build payload
EVENT_ID="evt-$(date +%s)"
EVENT_TYPE="MyTestType"
EVENT_SOURCE="test-script-source"
EVENT_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EVENT_DATA_JSON="{\"detailKey\":\"detailValue\",\"count\":123}"
PAYLOAD_JSON="{\"eventId\":\"${EVENT_ID}\",\"eventType\":\"${EVENT_TYPE}\",\"source\":\"${EVENT_SOURCE}\",\"timestamp\":\"${EVENT_TIMESTAMP}\",\"data\":${EVENT_DATA_JSON}}"

CONTAINER_TMP_PAYLOAD_PATH="/tmp/payload-${EVENT_ID}.json"

echo "→ Producing to $INPUT_TOPIC:"
echo "  Payload: $PAYLOAD_JSON"

echo "  Creating file $CONTAINER_TMP_PAYLOAD_PATH inside container via pipe..."
printf "%s" "$PAYLOAD_JSON" | docker exec -i "$PULSAR_CONTAINER_NAME" sh -c 'cat > /tmp/payload-temp.json && mv /tmp/payload-temp.json "$1"' -- "$CONTAINER_TMP_PAYLOAD_PATH"

echo "  Verifying file in container:"
docker exec "$PULSAR_CONTAINER_NAME" ls -l "$CONTAINER_TMP_PAYLOAD_PATH"
docker exec "$PULSAR_CONTAINER_NAME" cat "$CONTAINER_TMP_PAYLOAD_PATH"
echo

echo "  Producing message from in-container file: $CONTAINER_TMP_PAYLOAD_PATH"
docker exec "$PULSAR_CONTAINER_NAME" \
  bash -c "${PULSAR_CLI_CMD[0]} ${PULSAR_CLI_CMD[1]} ${PULSAR_CLI_CMD[2]} ${PULSAR_CLI_CMD[3]} produce \"$INPUT_TOPIC\" --files \"$CONTAINER_TMP_PAYLOAD_PATH\" -n 1 >/dev/null 2>&1; rm -f \"$CONTAINER_TMP_PAYLOAD_PATH\""


echo "  Attempting to remove in-container file (should have been done by producer command block)..."
if ! docker exec "$PULSAR_CONTAINER_NAME" test -f "$CONTAINER_TMP_PAYLOAD_PATH"; then
  echo "  Successfully confirmed in-container file was removed."
else
  echo "  WARNING: In-container file $CONTAINER_TMP_PAYLOAD_PATH still exists. Manual cleanup might be needed or an error occurred in the produce/rm block."
  # Optionally, try a more direct rm if the above check fails to confirm removal
  docker exec "$PULSAR_CONTAINER_NAME" rm -f "$CONTAINER_TMP_PAYLOAD_PATH"
fi


echo
echo "→ Waiting ${INITIAL_SLEEP_S}s for function processing..."
sleep "$INITIAL_SLEEP_S"

# 4) Sanitize and determine output topic
SANITIZED_EVENT_TYPE=$(echo "$EVENT_TYPE" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g')
OUT_TOPIC="${OUTPUT_PREFIX}-${SANITIZED_EVENT_TYPE}"
SUB_NAME="sub-${EVENT_ID}"

echo
echo "→ Consuming 1 msg from $OUT_TOPIC (sub=$SUB_NAME), waiting up to ${CONSUME_TIMEOUT_S}s …"

# Temporary file to capture consumer output
CONSUMER_OUTPUT_FILE=$(mktemp)
trap 'rm -f "$CONSUMER_OUTPUT_FILE"' EXIT # Ensure temp file for consumer output is cleaned

# Start consumer in background, redirecting its output (message) to the temp file
# and its stderr (client logs) to /dev/null
"${PULSAR_CLI_CMD[@]}" consume "$OUT_TOPIC" \
  -s "$SUB_NAME" \
  -n 1 \
  -p Earliest \
  -st bytes \
  --print-metadata > "$CONSUMER_OUTPUT_FILE" 2>/dev/null &
cons_pid=$!

# Watcher kills it if nothing arrives
(
  sleep "$CONSUME_TIMEOUT_S"
  # Check if consumer process is still running
  if kill -0 "$cons_pid" 2>/dev/null; then
    echo # Newline before timeout message
    echo "⏱️  No message received by consumer in ${CONSUME_TIMEOUT_S}s, killing consumer (PID: $cons_pid)."
    kill "$cons_pid" 2>/dev/null || true # Kill the consumer
  fi
) &
watcher_pid=$!

# Wait for the consumer process to complete or be killed
# Redirect wait's stderr to hide "Terminated" messages if watcher kills it
wait "$cons_pid" 2>/dev/null
consumer_exit_status=$?

# Kill the watcher if it's still running (e.g., if consumer exited early)
kill "$watcher_pid" 2>/dev/null || true

echo # Add a newline for cleaner output separation

# Check if the consumer output file has content
if [ -s "$CONSUMER_OUTPUT_FILE" ]; then
  echo "✔️ Successfully consumed message from $OUT_TOPIC:"
  cat "$CONSUMER_OUTPUT_FILE" # Print the consumed message
else
  echo "❌ No message received from $OUT_TOPIC within ${CONSUME_TIMEOUT_S}s (Consumer PID: $cons_pid, Exit Status: $consumer_exit_status)."
  echo "→ Current function status:"
  "${PULSAR_ADMIN_CMD[@]}" functions status \
    --tenant public --namespace default --name event-type-splitter
  echo "❗PLEASE CHECK FUNCTION LOGS: docker logs $PULSAR_CONTAINER_NAME"
  # Peek at messages on the output topic to see if anything is there
  echo "→ Peeking at messages on $OUT_TOPIC (if any exist):"
  "${PULSAR_ADMIN_CMD[@]}" topics peek-messages "$OUT_TOPIC" -s "peek-after-fail-$(date +%s)" -n 5 || echo " (peek command failed or no messages)"
  exit 1
fi

rm -f "$CONSUMER_OUTPUT_FILE" # Clean up temp file explicitly if not caught by trap
exit 0