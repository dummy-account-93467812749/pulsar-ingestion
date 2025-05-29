#!/usr/bin/env bash
set -euo pipefail

# —— CONFIG ——  
PULSAR_CLI="docker exec compose-pulsar-1 bin/pulsar-client"  
INPUT_TOPIC="persistent://public/default/common-events"  
OUTPUT_PREFIX="persistent://public/default/fn-split"  
TIMEOUT_S=5         # how many seconds to wait for the routed msg  
# --------------

# 1) build & publish test payload
EVENT_ID="evt-$(date +%s)"
EVENT_TYPE="MyTestType"
PAYLOAD="{\"eventId\":\"${EVENT_ID}\",\"eventType\":\"${EVENT_TYPE}\"}"

echo "→ Producing to $INPUT_TOPIC:"
echo "  $PAYLOAD"
$PULSAR_CLI produce "$INPUT_TOPIC" -m "$PAYLOAD"

# 2) give the function a moment
sleep 1

# 3) sanitize, consume with bytes schema, no hide-content
SANITIZED=$(echo "$EVENT_TYPE" \
  | tr '[:upper:]' '[:lower:]' \
  | sed 's/[^a-z0-9]/-/g')
OUT_TOPIC="${OUTPUT_PREFIX}-${SANITIZED}"
SUB_NAME="sub-${EVENT_ID}"

echo
echo "→ Consuming 1 msg from $OUT_TOPIC (sub=$SUB_NAME), waiting up to ${TIMEOUT_S}s …"

# start consumer in background
$PULSAR_CLI consume "$OUT_TOPIC" \
  -s "$SUB_NAME" \
  -n 1 \
  -p Earliest \
  -st bytes \
  --print-metadata &

cons_pid=$!

# watcher kills it if nothing arrives
(
  sleep "$TIMEOUT_S"
  if kill -0 "$cons_pid" 2>/dev/null; then
    echo
    echo "⏱️  No message received in ${TIMEOUT_S}s, killing consumer."
    kill "$cons_pid" 2>/dev/null
  fi
) & watcher_pid=$!

wait "$cons_pid" 2>/dev/null || true
kill "$watcher_pid" 2>/dev/null || true
