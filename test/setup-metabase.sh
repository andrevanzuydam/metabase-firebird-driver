#!/usr/bin/env bash
# Auto-setup Metabase with admin user and Firebird database connection.
# Usage: ./setup-metabase.sh
#
# This script waits for Metabase to be ready, then uses the API to:
# 1. Create the admin user (skips if already set up)
# 2. Add the Firebird database connection

set -eo pipefail

MB_URL="${MB_URL:-http://localhost:3001}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@test.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Metabase1!}"
ADMIN_FIRST="${ADMIN_FIRST:-Test}"
ADMIN_LAST="${ADMIN_LAST:-Admin}"

FB_HOST="${FB_HOST:-firebird}"
FB_PORT="${FB_PORT:-3050}"
FB_DB="${FB_DB:-/var/lib/firebird/data/metabase.fdb}"
FB_USER="${FB_USER:-SYSDBA}"
FB_PASS="${FB_PASS:-masterkey}"

echo "==> Waiting for Metabase to be ready..."
for i in $(seq 1 60); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${MB_URL}/api/health" 2>/dev/null || echo "000")
    if [ "$STATUS" = "200" ]; then
        echo "    Metabase is ready!"
        break
    fi
    echo "    Waiting... (${i}s)"
    sleep 5
done

if [ "$STATUS" != "200" ]; then
    echo "ERROR: Metabase did not start within 5 minutes"
    exit 1
fi

# Check if setup is needed
SETUP_TOKEN=$(curl -s "${MB_URL}/api/session/properties" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('setup-token',''))" 2>/dev/null || echo "")

if [ -z "$SETUP_TOKEN" ]; then
    echo "==> Metabase already set up. Logging in..."
    SESSION=$(curl -s -X POST "${MB_URL}/api/session" \
        -H "Content-Type: application/json" \
        -d "{\"username\": \"${ADMIN_EMAIL}\", \"password\": \"${ADMIN_PASSWORD}\"}" | \
        python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

    if [ -z "$SESSION" ]; then
        echo "ERROR: Could not log in. Try resetting with: docker compose down (without -v to keep data)"
        exit 1
    fi
else
    echo "==> Running initial setup with token: ${SETUP_TOKEN:0:8}..."

    # Complete setup via API
    SETUP_RESPONSE=$(curl -s -X POST "${MB_URL}/api/setup" \
        -H "Content-Type: application/json" \
        -d "{
            \"token\": \"${SETUP_TOKEN}\",
            \"user\": {
                \"email\": \"${ADMIN_EMAIL}\",
                \"password\": \"${ADMIN_PASSWORD}\",
                \"first_name\": \"${ADMIN_FIRST}\",
                \"last_name\": \"${ADMIN_LAST}\",
                \"site_name\": \"Firebird Driver Test\"
            },
            \"prefs\": {
                \"site_name\": \"Firebird Driver Test\",
                \"site_locale\": \"en\",
                \"allow_tracking\": false
            }
        }")

    SESSION=$(echo "$SETUP_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

    if [ -z "$SESSION" ]; then
        echo "ERROR: Setup failed. Response: ${SETUP_RESPONSE}"
        exit 1
    fi
    echo "    Setup complete!"
fi

echo "==> Session token: ${SESSION:0:8}..."

# Check if Firebird database already exists
EXISTING=$(curl -s "${MB_URL}/api/database" \
    -H "X-Metabase-Session: ${SESSION}" | \
    python3 -c "
import sys, json
dbs = json.load(sys.stdin).get('data', [])
for db in dbs:
    if db.get('engine') == 'firebird':
        print(db['id'])
        break
" 2>/dev/null || echo "")

if [ -n "$EXISTING" ]; then
    echo "==> Firebird database already configured (ID: ${EXISTING}). Triggering sync..."
    curl -s -X POST "${MB_URL}/api/database/${EXISTING}/sync_schema" \
        -H "X-Metabase-Session: ${SESSION}" > /dev/null
else
    echo "==> Adding Firebird database connection..."
    DB_RESPONSE=$(curl -s -X POST "${MB_URL}/api/database" \
        -H "Content-Type: application/json" \
        -H "X-Metabase-Session: ${SESSION}" \
        -d "{
            \"engine\": \"firebird\",
            \"name\": \"Firebird Test\",
            \"details\": {
                \"host\": \"${FB_HOST}\",
                \"port\": ${FB_PORT},
                \"dbname\": \"${FB_DB}\",
                \"user\": \"${FB_USER}\",
                \"password\": \"${FB_PASS}\"
            }
        }")

    DB_ID=$(echo "$DB_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

    if [ -n "$DB_ID" ]; then
        echo "    Firebird database added (ID: ${DB_ID})"
    else
        echo "    WARNING: Could not add database. Response: ${DB_RESPONSE}"
        echo "    You may need to add it manually at ${MB_URL}"
    fi
fi

echo ""
echo "==> Setup complete!"
echo "    URL:      ${MB_URL}"
echo "    Email:    ${ADMIN_EMAIL}"
echo "    Password: ${ADMIN_PASSWORD}"
