#!/usr/bin/env bash
set -euo pipefail

PULSAR_ADMIN=${PULSAR_ADMIN:-pulsar-admin}
TENANT="public"
NAMESPACE="default"

echo "=============================================="
echo " Pulsar Health Check for $TENANT/$NAMESPACE "
echo " Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "=============================================="
echo

# --- sanity checks ---
if [ -z "${BASH_VERSION:-}" ]; then
  echo "ERROR: Please run with Bash (e.g., bash ./status.sh or chmod +x ./status.sh && ./status.sh), not a more basic sh." >&2
  exit 1
fi
command -v "$PULSAR_ADMIN" &>/dev/null || {
  echo "ERROR: $PULSAR_ADMIN not found in PATH." >&2
  exit 1
}

# helper to trim quotes/brackets from JSON lists of strings
normalize_list() {
  # Input: ["item1", "item2 with spaces"]
  # Output:
  # item1
  # item2 with spaces
  echo "$1" | tr -d '[]"' | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep . # grep . to remove empty lines if any
}

# 1. CONNECTORS
echo "---- CONNECTORS ----"
for TYPE in sources sinks; do
  echo
  # Use tr for portable uppercase conversion
  UPPER_TYPE=$(echo "$TYPE" | tr '[:lower:]' '[:upper:]')
  echo "Listing all $UPPER_TYPE..."
  # Supress stderr for the list command itself, if it fails RAW will be empty.
  RAW=$($PULSAR_ADMIN "$TYPE" list --tenant "$TENANT" --namespace "$NAMESPACE" 2>/dev/null || true)
  LIST=$(normalize_list "$RAW")
  if [[ -z "$LIST" ]]; then
    echo "  (none in $TENANT/$NAMESPACE)"
  else
    while IFS= read -r NAME; do
      [[ -z "$NAME" ]] && continue
      # singular sub‑command is without the final 's'
      SINGULAR=${TYPE%?}
      echo "  • $SINGULAR '$NAME' status:"
      # Indent the status output for readability
      $PULSAR_ADMIN "$SINGULAR" status \
        --tenant "$TENANT" --namespace "$NAMESPACE" \
        --name "$NAME" 2>/dev/null | sed 's/^/    /' \
        || echo "    ERROR: Could not retrieve status for $SINGULAR '$NAME'."
    done <<<"$LIST"
  fi
done

echo
# 2. TOPICS
echo "---- TOPICS ----"
# (a) non‑partitioned
echo
echo "Non‑partitioned topics in $TENANT/$NAMESPACE:"
# pulsar-admin topics list already outputs one topic per line, not JSON array
RAW_NP=$($PULSAR_ADMIN topics list "$TENANT/$NAMESPACE" 2>/dev/null || true)
if [[ -z "$RAW_NP" ]]; then
  echo "  (none)"
else
  while IFS= read -r TOPIC; do
    [[ -z "$TOPIC" ]] && continue
    echo -e "\n  Topic: $TOPIC"
    echo "    • Stats: "
    # Removed head -n1 to show full stats JSON, indented
    $PULSAR_ADMIN topics stats "$TOPIC" 2>/dev/null | sed 's/^/      /' \
      || echo "      ERROR: Could not retrieve stats for topic '$TOPIC'."
    echo "    • Subscriptions: "
    # Indent the subscriptions output
    $PULSAR_ADMIN topics subscriptions "$TOPIC" 2>/dev/null | sed 's/^/      /' \
      || echo "      ERROR: Could not retrieve subscriptions for topic '$TOPIC'."
  done <<<"$RAW_NP"
fi

# (b) partitioned
echo
echo "Partitioned topics in $TENANT/$NAMESPACE:"
# pulsar-admin topics list-partitioned-topics also outputs one topic per line
RAW_P=$($PULSAR_ADMIN topics list-partitioned-topics "$TENANT/$NAMESPACE" 2>/dev/null || true)
if [[ -z "$RAW_P" ]]; then
  echo "  (none)"
else
  while IFS= read -r PT; do
    [[ -z "$PT" ]] && continue
    echo -e "\n  Partitioned Topic: $PT"
    echo "    • Stats: "
    # Removed head -n1 to show full stats JSON, indented
    $PULSAR_ADMIN topics stats "$PT" 2>/dev/null | sed 's/^/      /' \
      || echo "      ERROR: Could not retrieve stats for partitioned topic '$PT'."
    echo "    • Subscriptions: "
    # Indent the subscriptions output
    $PULSAR_ADMIN topics subscriptions "$PT" 2>/dev/null | sed 's/^/      /' \
      || echo "      ERROR: Could not retrieve subscriptions for partitioned topic '$PT'."
  done <<<"$RAW_P"
fi

echo
# 3. FUNCTIONS
echo "---- FUNCTIONS ----"
echo
echo "Listing all FUNCTIONS in $TENANT/$NAMESPACE..."
# pulsar-admin functions list outputs a JSON array
RAW_F=$($PULSAR_ADMIN functions list --tenant "$TENANT" --namespace "$NAMESPACE" 2>/dev/null || true)
LIST_F=$(normalize_list "$RAW_F") # Use normalize_list here
if [[ -z "$LIST_F" ]]; then
  echo "  (none in $TENANT/$NAMESPACE)"
else
  while IFS= read -r FN; do
    [[ -z "$FN" ]] && continue
    echo "  • Function '$FN' status:"
    # Indent the status output
    $PULSAR_ADMIN functions status \
      --tenant "$TENANT" --namespace "$NAMESPACE" \
      --name "$FN" 2>/dev/null | sed 's/^/    /' \
      || echo "    ERROR: Could not retrieve status for function '$FN'."
  done <<<"$LIST_F"
fi

echo
echo "=============================================="
echo " Health Check Completed: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "=============================================="