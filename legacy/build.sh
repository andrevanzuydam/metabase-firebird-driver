#!/usr/bin/env bash

set -eo pipefail

DRIVER_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METABASE_PATH="${METABASE_PATH:-$DRIVER_PATH/../../metabase}"

# On Windows (git-bash/MSYS), translate Unix-style paths to Windows native so the
# JVM and tools.deps resolve :local/root correctly. No-op on Linux/macOS.
if command -v cygpath >/dev/null 2>&1; then
  # -m gives mixed form (D:/foo/bar) — accepted by the JVM and safe inside EDN strings,
  # unlike -w which produces backslashes that the EDN reader treats as escape chars.
  DRIVER_PATH="$(cygpath -m "$DRIVER_PATH")"
  METABASE_PATH="$(cygpath -m "$METABASE_PATH")"
fi

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
