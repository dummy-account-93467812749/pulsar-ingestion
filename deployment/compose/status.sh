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
  echo "ERROR: Please run with Bash (./status.sh), not sh." >&2
  exit 1
fi
command -v "$PULSAR_ADMIN" &>/dev/null || {
  echo "ERROR: $PULSAR_ADMIN not found in PATH." >&2
  exit 1
}

# helper to trim quotes/brackets from JSON lists
normalize_list() {
  echo "$1" | tr -d '[]"' | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# 1. CONNECTORS
echo "---- CONNECTORS ----"
for TYPE in sources sinks; do
  echo
  echo "Listing all ${TYPE^^}..."
  RAW=$($PULSAR_ADMIN $TYPE list --tenant "$TENANT" --namespace "$NAMESPACE" 2>/dev/null)
  LIST=$(normalize_list "$RAW")
  if [[ -z "$LIST" ]]; then
    echo "  (none)"
  else
    while read -r NAME; do
      [[ -z "$NAME" ]] && continue
      # singular sub‑command is without the final 's'
      SINGULAR=${TYPE%?}
      echo -n "  • $SINGULAR '$NAME' : "
      $PULSAR_ADMIN $SINGULAR status \
        --tenant "$TENANT" --namespace "$NAMESPACE" \
        --name "$NAME" 2>/dev/null \
        || echo "ERROR retrieving status"
    done <<<"$LIST"
  fi
done

echo
# 2. TOPICS
echo "---- TOPICS ----"
# (a) non‑partitioned
echo
echo "Non‑partitioned topics:"
RAW_NP=$($PULSAR_ADMIN topics list "$TENANT/$NAMESPACE" 2>/dev/null)  # :contentReference[oaicite:0]{index=0}
if [[ -z "$RAW_NP" ]]; then
  echo "  (none)"
else
  while read -r TOPIC; do
    [[ -z "$TOPIC" ]] && continue
    echo -e "\n  Topic: $TOPIC"
    echo -n "    • Stats: "
    $PULSAR_ADMIN topics stats "$TOPIC" 2>/dev/null | head -n1  \
      || echo "ERROR retrieving stats"                                # :contentReference[oaicite:1]{index=1}
    echo -n "    • Subscriptions: "
    $PULSAR_ADMIN topics subscriptions "$TOPIC" 2>/dev/null | sed 's/^/      - /' \
      || echo "ERROR retrieving subscriptions"
  done <<<"$RAW_NP"
fi

# (b) partitioned
echo
echo "Partitioned topics:"
RAW_P=$($PULSAR_ADMIN topics list-partitioned-topics "$TENANT/$NAMESPACE" 2>/dev/null)  # :contentReference[oaicite:2]{index=2}
if [[ -z "$RAW_P" ]]; then
  echo "  (none)"
else
  while read -r PT; do
    [[ -z "$PT" ]] && continue
    echo -e "\n  Partitioned Topic: $PT"
    echo -n "    • Stats: "
    $PULSAR_ADMIN topics stats "$PT" 2>/dev/null | head -n1 \
      || echo "ERROR retrieving stats"
    echo -n "    • Subscriptions: "
    $PULSAR_ADMIN topics subscriptions "$PT" 2>/dev/null | sed 's/^/      - /' \
      || echo "ERROR retrieving subscriptions"
  done <<<"$RAW_P"
fi

echo
# 3. FUNCTIONS
echo "---- FUNCTIONS ----"
RAW_F=$($PULSAR_ADMIN functions list --tenant "$TENANT" --namespace "$NAMESPACE" 2>/dev/null)
if [[ -z "$RAW_F" ]]; then
  echo "  (none)"
else
  while read -r FN; do
    [[ -z "$FN" ]] && continue
    echo -n "  • Function '$FN' : "
    $PULSAR_ADMIN functions status \
      --tenant "$TENANT" --namespace "$NAMESPACE" \
      --name "$FN" 2>/dev/null \
      || echo "ERROR retrieving status"
  done <<<"$RAW_F"
fi

echo
echo "=============================================="
echo " Completed: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "=============================================="
