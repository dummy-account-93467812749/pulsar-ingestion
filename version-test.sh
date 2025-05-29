#!/usr/bin/env bash
set -euo pipefail

NAR=$(find . -type f -name 'translators-0.1.0-SNAPSHOT.nar' | head -n1)
if [[ -z "$NAR" ]]; then
  echo "ERROR: no .nar file found in $(pwd)" >&2
  exit 1
fi

echo "Found NAR at $NAR"
CLASS=$(jar tf "$NAR" | grep '\.class$' | head -n1)
echo "Inspecting class: $CLASS"

# extract and inspect
unzip -p "$NAR" "$CLASS" > /tmp/Some.class
echo -n "major version: "
javap -verbose /tmp/Some.class | awk '/major version/ { print $3 }'
