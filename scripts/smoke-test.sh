#!/usr/bin/env bash
# ============================================================
# Smoke tests — functional verification of the LIVE deployment
# ------------------------------------------------------------
# Verifies the deployed API contract end to end:
#   health, register, login (positive + negative), authenticated
#   reads, auth-required enforcement, forgot-password, ML price advice.
#
# Expected responses were captured from the live server (2026-08-18)
# and are asserted here — a broken deploy fails this job.
#
# Usage:  SMOKE_BASE_URL=http://host:8080 bash scripts/smoke-test.sh
# ============================================================
set -uo pipefail

BASE_URL="${SMOKE_BASE_URL:-http://168.144.253.212:8080}"
SMOKE_USER="${SMOKE_USER:-smokeuser}"
SMOKE_PASS="${SMOKE_PASS:-Smoke@2026!}"
SMOKE_EMAIL="${SMOKE_EMAIL:-smoke@team7.test}"
SMOKE_DEPT="${SMOKE_DEPT:-IT}"
SMOKE_PHONE="${SMOKE_PHONE:-60000000}"

PASS=0
FAIL=0

# check <name> <expected_http_code> <expected_body_substr> <curl args...>
check() {
    local name="$1" want_code="$2" want_body="$3"
    shift 3
    local out code body
    out=$(curl -s -m 30 -w "\n%{http_code}" "$@")
    code=$(echo "$out" | tail -1)
    body=$(echo "$out" | head -n -1)
    if [ "$code" = "$want_code" ] && echo "$body" | grep -qF "$want_body"; then
        PASS=$((PASS + 1))
        echo "PASS  $name"
    else
        FAIL=$((FAIL + 1))
        echo "FAIL  $name  (HTTP $code, expected $want_code + body containing: $want_body)"
        echo "      body: $(echo "$body" | head -c 200)"
    fi
}

echo "== Smoke tests against $BASE_URL =="

# The API may still be booting right after a fresh deploy — wait up to 60s
for i in $(seq 1 20); do
    curl -s -m 3 -o /dev/null "$BASE_URL/actuator/health" && break
    [ "$i" = "20" ] && echo "WARN: API not reachable yet, continuing anyway"
    sleep 3
done

# 1. Health check (public)
check "health" 200 'UP' "$BASE_URL/actuator/health"

# 2. Register the smoke account (idempotent: 200 first time, 409 if exists)
REG=$(curl -s -m 15 -X POST "$BASE_URL/api/auth/register" \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\",\"email\":\"$SMOKE_EMAIL\",\"department\":\"$SMOKE_DEPT\",\"phone\":\"$SMOKE_PHONE\"}")
if echo "$REG" | grep -qF "Registration successful" || echo "$REG" | grep -qF "already exists"; then
    PASS=$((PASS + 1)); echo "PASS  register (or already exists)"
else
    FAIL=$((FAIL + 1)); echo "FAIL  register  -> $REG"
fi

# 3. Login with wrong password is rejected
check "login wrong password" 401 'Invalid username or password' \
    -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$SMOKE_USER\",\"password\":\"wrongpass\"}"

# 4. Login with correct credentials yields a token
LOGIN=$(curl -s -m 15 -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\"}")
TOKEN=$(echo "$LOGIN" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then
    PASS=$((PASS + 1)); echo "PASS  login -> token (${#TOKEN} chars)"
else
    FAIL=$((FAIL + 1)); echo "FAIL  login -> no token: $LOGIN"
fi

# 5. Authenticated reads
check "GET /api/users/me" 200 "\"username\":\"$SMOKE_USER\"" \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/users/me"
check "GET /api/trips" 200 '[' \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/trips"
check "GET /api/expenses" 200 '[' \
    -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/expenses"

# 6. Auth is enforced (no token -> 403)
check "trips require auth" 403 '' "$BASE_URL/api/trips"

# 7. Forgot-password rejects an unknown account
check "forgot-password unknown account" 404 'Account not found' \
    -X POST "$BASE_URL/api/auth/forgot-password" -H "Content-Type: application/json" \
    -d '{"username":"nosuchuser","email":"x@y.z","department":"IT","phone":"00000000","newPassword":"New@2026!"}'

# 8. ML price advisor answers
check "price-advice" 200 'prediction_available' \
    -X POST "$BASE_URL/api/ml/v2/price-advice" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"city":"Tokyo","checkInDate":"2026-08-22","checkOutDate":"2026-08-24","roomType":"double","numberOfGuests":2}'

echo
echo "== Result: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
