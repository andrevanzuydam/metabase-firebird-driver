#!/usr/bin/env bash
# Reset and reload test data into the Firebird container.
# Usage: ./reset-test-data.sh
#
# This script:
# 1. Stops Metabase (to release locks)
# 2. Drops all test tables
# 3. Reloads init-test-data.sql
# 4. Restarts Metabase

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

FIREBIRD_CONTAINER="${FIREBIRD_CONTAINER:-metabase_firebird}"
METABASE_CONTAINER="${METABASE_CONTAINER:-metabase}"
FB_USER="${FB_USER:-SYSDBA}"
FB_PASS="${FB_PASS:-masterkey}"
FB_DB="${FB_DB:-/var/lib/firebird/data/metabase.fdb}"
ISQL="/opt/firebird/bin/isql"

echo "==> Stopping Metabase to release locks..."
docker stop "$METABASE_CONTAINER" 2>/dev/null || true

echo "==> Dropping all TEST_* tables..."
docker exec -i "$FIREBIRD_CONTAINER" "$ISQL" -user "$FB_USER" -password "$FB_PASS" "$FB_DB" <<'DROPSQL'
EXECUTE BLOCK AS
DECLARE VARIABLE tbl VARCHAR(63);
BEGIN
  FOR SELECT TRIM(RDB$RELATION_NAME) FROM RDB$RELATIONS
      WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0
        AND RDB$RELATION_NAME STARTING WITH 'TEST_'
      INTO :tbl
  DO BEGIN
    /* Drop FK-child tables first by trying all and ignoring errors */
    BEGIN
      EXECUTE STATEMENT 'DROP TABLE ' || :tbl;
    WHEN ANY DO
      BEGIN /* ignore, will retry */ END
    END
  END
  /* Second pass for tables that had dependencies */
  FOR SELECT TRIM(RDB$RELATION_NAME) FROM RDB$RELATIONS
      WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0
        AND RDB$RELATION_NAME STARTING WITH 'TEST_'
      INTO :tbl
  DO BEGIN
    BEGIN
      EXECUTE STATEMENT 'DROP TABLE ' || :tbl;
    WHEN ANY DO
      BEGIN /* ignore */ END
    END
  END
END
DROPSQL

echo "==> Verifying clean state..."
TABLE_COUNT=$(docker exec -i "$FIREBIRD_CONTAINER" "$ISQL" -user "$FB_USER" -password "$FB_PASS" "$FB_DB" <<'SQL' | grep -oE '[0-9]+'
SELECT COUNT(*) FROM RDB$RELATIONS WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 AND RDB$RELATION_NAME STARTING WITH 'TEST_';
SQL
)

if [ "$TABLE_COUNT" != "0" ]; then
    echo "WARNING: $TABLE_COUNT test tables still remain. Trying force cleanup..."
    docker stop "$FIREBIRD_CONTAINER"
    docker rm "$FIREBIRD_CONTAINER"
    docker volume rm test_firebird-data 2>/dev/null || true
    cd "$SCRIPT_DIR"
    docker compose up -d firebird
    echo "Waiting for Firebird to start..."
    sleep 5
fi

echo "==> Loading test data..."
docker cp "$SCRIPT_DIR/init-test-data.sql" "$FIREBIRD_CONTAINER:/tmp/init-test-data.sql"
docker exec -i "$FIREBIRD_CONTAINER" "$ISQL" -user "$FB_USER" -password "$FB_PASS" "$FB_DB" -i /tmp/init-test-data.sql

echo "==> Verifying data..."
docker exec -i "$FIREBIRD_CONTAINER" "$ISQL" -user "$FB_USER" -password "$FB_PASS" "$FB_DB" <<'SQL'
SELECT 'TEST_TYPES' as TBL, COUNT(*) as ROWS_CNT FROM TEST_TYPES
UNION ALL SELECT 'TEST_ORDERS', COUNT(*) FROM TEST_ORDERS
UNION ALL SELECT 'TEST_LONG_TEXT', COUNT(*) FROM TEST_LONG_TEXT
UNION ALL SELECT 'TEST_CATEGORIES', COUNT(*) FROM TEST_CATEGORIES
UNION ALL SELECT 'TEST_PRODUCTS', COUNT(*) FROM TEST_PRODUCTS
UNION ALL SELECT 'TEST_COMPOSITE_PK', COUNT(*) FROM TEST_COMPOSITE_PK
UNION ALL SELECT 'TEST_NUMERIC_PREC', COUNT(*) FROM TEST_NUMERIC_PRECISION
UNION ALL SELECT 'TEST_DATETIME_EDG', COUNT(*) FROM TEST_DATETIME_EDGES
UNION ALL SELECT 'TEST_STRING_EDGES', COUNT(*) FROM TEST_STRING_EDGES
UNION ALL SELECT 'TEST_SALES', COUNT(*) FROM TEST_SALES;
SQL

echo "==> Starting Metabase..."
docker start "$METABASE_CONTAINER"

echo "==> Done! Metabase will be available at http://localhost:3001 once it finishes starting."
