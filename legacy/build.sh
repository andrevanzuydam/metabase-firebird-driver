#!/usr/bin/env bash

set -eo pipefail

DRIVER_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METABASE_PATH="${METABASE_PATH:-$DRIVER_PATH/../../metabase}"

if [ ! -d "$METABASE_PATH" ]; then
  echo "Error: Metabase source not found at $METABASE_PATH"
  echo "Either clone Metabase as a sibling directory or set METABASE_PATH."
  exit 1
fi

echo "Building Firebird LEGACY driver (Jaybird 2.2.x for Firebird 1.5+)..."
echo "  Driver path:   $DRIVER_PATH"
echo "  Metabase path: $METABASE_PATH"

cd "$METABASE_PATH"

clojure \
  -Sdeps "{:aliases {:firebird-legacy {:extra-deps {metabase/firebird-legacy-driver {:local/root \"$DRIVER_PATH\"}}}}}" \
  -X:build:firebird-legacy \
  build-drivers.build-driver/build-driver! \
  "{:driver :firebird-legacy, :project-dir \"$DRIVER_PATH\", :target-dir \"$DRIVER_PATH/target\"}"

echo ""
echo "Build complete! Legacy driver JAR is at: $DRIVER_PATH/target/firebird-legacy.metabase-driver.jar"
echo ""
echo "NOTE: Do NOT install this alongside the standard firebird.metabase-driver.jar."
echo "      Use ONE or the OTHER, not both."
