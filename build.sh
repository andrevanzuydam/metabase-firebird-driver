#!/usr/bin/env bash

set -eo pipefail

DRIVER_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METABASE_PATH="${METABASE_PATH:-$DRIVER_PATH/../metabase}"

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
  echo "Either clone Metabase as a sibling directory:"
  echo "  git clone --depth 1 https://github.com/metabase/metabase.git $DRIVER_PATH/../metabase"
  echo "Or set METABASE_PATH to your Metabase checkout:"
  echo "  METABASE_PATH=/path/to/metabase ./build.sh"
  exit 1
fi

echo "Building Firebird driver..."
echo "  Driver path:   $DRIVER_PATH"
echo "  Metabase path: $METABASE_PATH"

cd "$METABASE_PATH"

clojure \
  -Sdeps "{:aliases {:firebird {:extra-deps {metabase/firebird-driver {:local/root \"$DRIVER_PATH\"}}}}}" \
  -X:build:firebird \
  build-drivers.build-driver/build-driver! \
  "{:driver :firebird, :project-dir \"$DRIVER_PATH\", :target-dir \"$DRIVER_PATH/target\"}"

echo ""
echo "Build complete! Driver JAR is at: $DRIVER_PATH/target/firebird.metabase-driver.jar"
