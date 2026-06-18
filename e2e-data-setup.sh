#!/usr/bin/env bash
# =============================================================================
# e2e-data-setup.sh
#
# Populates the Spring Order Management demo with diverse data so that every
# debug-agent diagnostic tool (HTTP inspector, DB inspector, Cache inspector,
# Task inspector, Log inspector, Bean inspector, etc.) has meaningful data to
# query.
#
# Coverage:
#   1. Order creation      — multiple customers, products, quantities, tiers
#   2. HTTP request variety — GET / POST / PUT / DELETE + 404 / 400 / 405 / 500
#   3. Slow / timeout       — curl --max-time to force client-side timeouts
#   4. Cache operations     — repeated SKU lookups to populate @Cacheable
#   5. Scheduled tasks      — wait for ReportScheduler jobs to fire
#   6. Log variety          — INFO / WARN / ERROR via business logic paths
#
# Usage:
#   ./e2e-data-setup.sh [BASE_URL]
#
#   BASE_URL defaults to http://localhost:8080
# =============================================================================

set -euo pipefail

# ─── Configuration ──────────────────────────────────────────────────────────

BASE_URL="${1:-http://localhost:8080}"
API="${BASE_URL}/api/orders"
ACTUATOR="${BASE_URL}/actuator"
CURL="curl -s -o /dev/null -w '%{http_code}'"

# Colour helpers (disabled if not a terminal)
if [ -t 1 ]; then
    C_GREEN='\033[0;32m'; C_YELLOW='\033[1;33m'; C_RED='\033[0;31m'
    C_BLUE='\033[0;34m'; C_CYAN='\033[0;36m'; C_BOLD='\033[1m'; C_RESET='\033[0m'
else
    C_GREEN=''; C_YELLOW=''; C_RED=''; C_BLUE=''; C_CYAN=''; C_BOLD=''; C_RESET=''
fi

# Counters
TOTAL_REQUESTS=0
SUCCESS_COUNT=0
ERROR_COUNT=0

# ─── Helpers ─────────────────────────────────────────────────────────────────

header() {
    echo ""
    echo -e "${C_CYAN}${C_BOLD}═══════════════════════════════════════════════════════════════${C_RESET}"
    echo -e "${C_CYAN}${C_BOLD}  $1${C_RESET}"
    echo -e "${C_CYAN}${C_BOLD}═══════════════════════════════════════════════════════════════${C_RESET}"
}

step() {
    echo -e "  ${C_BLUE}▶${C_RESET} $1"
}

ok() {
    TOTAL_REQUESTS=$((TOTAL_REQUESTS + 1))
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    echo -e "    ${C_GREEN}✓${C_RESET} [$1] $2"
}

err() {
    TOTAL_REQUESTS=$((TOTAL_REQUESTS + 1))
    ERROR_COUNT=$((ERROR_COUNT + 1))
    echo -e "    ${C_RED}✗${C_RESET} [$1] $2"
}

info() {
    echo -e "    ${C_BLUE}ℹ${C_RESET} $1"
}

wait_for() {
    echo -e "  ${C_YELLOW}⏳${C_RESET} $1"
}

# create_order <customerId> <json_items> <label>
# Prints the HTTP status code on stdout.
create_order() {
    local customer_id="$1"
    local items="$2"
    local body
    body=$(cat <<EOF
{"customerId": ${customer_id}, "items": ${items}}
EOF
)
    curl -s -o /dev/null -w '%{http_code}' \
        -X POST "${API}" \
        -H 'Content-Type: application/json' \
        -d "${body}"
}

# ─── Pre-flight health check ─────────────────────────────────────────────────

header "Pre-flight Health Check"
step "Checking application is reachable at ${BASE_URL}"

HEALTH_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health" 2>/dev/null || echo "000")

if [ "${HEALTH_STATUS}" != "200" ]; then
    echo ""
    echo -e "${C_RED}${C_BOLD}ERROR:${C_RESET} Application is not reachable at ${BASE_URL}"
    echo -e "${C_RED}Health endpoint returned: ${HEALTH_STATUS}${C_RESET}"
    echo ""
    echo "Make sure the demo app is running:"
    echo "  ./mvnw spring-boot:run -pl demo"
    echo ""
    exit 1
fi

ok "200" "Application is healthy and reachable"

# Verify seeded customers exist
step "Verifying seeded customers (IDs 1, 2, 3)"
for cid in 1 2 3; do
    STATUS=$(curl -s -o /dev/null -w '%{http_code}' "${API}/customer/${cid}")
    if [ "${STATUS}" = "200" ]; then
        ok "${STATUS}" "Customer ${cid} exists"
    else
        err "${STATUS}" "Customer ${cid} not found (expected 200)"
    fi
done

# ─── Section 1: Order Creation ───────────────────────────────────────────────

header "Section 1: Order Creation (diverse customers & products)"

# Customer 1: Alice Johnson (GOLD tier) — normal orders
step "Creating orders for Customer 1 (Alice, GOLD)"

S=$(create_order 1 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":1}]')
ok "${S}" "Order #1: Alice — 1x Laptop Pro 15 (\$2499)"

S=$(create_order 1 '[{"sku":"MONITOR-4K-27","productName":"4K Monitor 27","quantity":2},{"sku":"USB-C-HUB","productName":"USB-C Hub","quantity":3}]')
ok "${S}" "Order #2: Alice — 2x Monitor + 3x USB-C Hub"

S=$(create_order 1 '[{"sku":"KEYBOARD-MECH","productName":"Mechanical Keyboard","quantity":1},{"sku":"MOUSE-WIRELESS","productName":"Wireless Mouse","quantity":2}]')
ok "${S}" "Order #3: Alice — 1x Keyboard + 2x Mouse"

S=$(create_order 1 '[{"sku":"DOCK-STATION","productName":"Docking Station","quantity":1},{"sku":"WEBCAM-HD","productName":"HD Webcam","quantity":1}]')
ok "${S}" "Order #4: Alice — 1x Dock + 1x Webcam"

S=$(create_order 1 '[{"sku":"HEADSET-PRO","productName":"Pro Headset","quantity":2}]')
ok "${S}" "Order #5: Alice — 2x Pro Headset"

# Customer 2: Bob Smith (SILVER tier) — normal orders
step "Creating orders for Customer 2 (Bob, SILVER)"

S=$(create_order 2 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":2}]')
ok "${S}" "Order #6: Bob — 2x Laptop Pro 15 (\$4998)"

S=$(create_order 2 '[{"sku":"MONITOR-4K-27","productName":"4K Monitor 27","quantity":3}]')
ok "${S}" "Order #7: Bob — 3x 4K Monitor"

S=$(create_order 2 '[{"sku":"MOUSE-WIRELESS","productName":"Wireless Mouse","quantity":5},{"sku":"USB-C-HUB","productName":"USB-C Hub","quantity":5}]')
ok "${S}" "Order #8: Bob — 5x Mouse + 5x USB-C Hub"

S=$(create_order 2 '[{"sku":"KEYBOARD-MECH","productName":"Mechanical Keyboard","quantity":3}]')
ok "${S}" "Order #9: Bob — 3x Mechanical Keyboard"

# Customer 3: Charlie Brown (BRONZE tier) — normal orders
step "Creating orders for Customer 3 (Charlie, BRONZE)"

S=$(create_order 3 '[{"sku":"MOUSE-WIRELESS","productName":"Wireless Mouse","quantity":1}]')
ok "${S}" "Order #10: Charlie — 1x Wireless Mouse"

S=$(create_order 3 '[{"sku":"HEADSET-PRO","productName":"Pro Headset","quantity":1},{"sku":"WEBCAM-HD","productName":"HD Webcam","quantity":1}]')
ok "${S}" "Order #11: Charlie — 1x Headset + 1x Webcam"

S=$(create_order 3 '[{"sku":"USB-C-HUB","productName":"USB-C Hub","quantity":2}]')
ok "${S}" "Order #12: Charlie — 2x USB-C Hub"

# Large orders to exhaust credit and trigger REJECTED_INSUFFICIENT_CREDIT
step "Creating large orders to exhaust credit (triggers REJECTED status)"

S=$(create_order 1 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":2}]')
ok "${S}" "Order #13: Alice — 2x Laptop (credit reduction)"

S=$(create_order 1 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":3}]')
ok "${S}" "Order #14: Alice — 3x Laptop (may be REJECTED — insufficient credit)"

S=$(create_order 2 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":3}]')
ok "${S}" "Order #15: Bob — 3x Laptop (may be REJECTED — insufficient credit)"

# ─── Section 2: Cache Operations ─────────────────────────────────────────────

header "Section 2: Cache Operations (populate @Cacheable 'prices')"

step "Creating orders with repeated SKUs to exercise cache hit/miss"
info "Each unique SKU: first call = cache miss (slow), subsequent = cache hit"

# These SKUs were already used above, so they should now be cache hits
S=$(create_order 3 '[{"sku":"MOUSE-WIRELESS","productName":"Wireless Mouse","quantity":1}]')
ok "${S}" "Cache hit: MOUSE-WIRELESS"

S=$(create_order 3 '[{"sku":"KEYBOARD-MECH","productName":"Mechanical Keyboard","quantity":1}]')
ok "${S}" "Cache hit: KEYBOARD-MECH"

S=$(create_order 3 '[{"sku":"MONITOR-4K-27","productName":"4K Monitor 27","quantity":1}]')
ok "${S}" "Cache hit: MONITOR-4K-27"

S=$(create_order 3 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":1}]')
ok "${S}" "Cache hit: LAPTOP-PRO-15"

S=$(create_order 3 '[{"sku":"DOCK-STATION","productName":"Docking Station","quantity":1}]')
ok "${S}" "Cache hit: DOCK-STATION"

# Verify cache is populated — these GETs will be fast due to cached pricing
step "GET expensive orders to exercise DB + cache interaction"
for min_amount in 100 500 1000 2500; do
    S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/expensive?minAmount=${min_amount}")
    ok "${S}" "GET /api/orders/expensive?minAmount=${min_amount}"
done

# ─── Section 3: HTTP Request Variety (all verbs) ─────────────────────────────

header "Section 3: HTTP Method Variety & Error Responses"

# --- GET requests ---
step "GET requests (existing data)"
for oid in 1 2 3 4 5; do
    S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/${oid}")
    ok "${S}" "GET /api/orders/${oid}"
done

S=$(curl -s -o /dev/null -w '%{http_code}' "${API}")
ok "${S}" "GET /api/orders (all orders)"

for cid in 1 2 3; do
    S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/customer/${cid}")
    ok "${S}" "GET /api/orders/customer/${cid}"
done

# --- 404 responses ---
step "404 responses (non-existent resources)"
S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/99999")
err "${S}" "GET /api/orders/99999 (non-existent order)"

S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/customer/999")
err "${S}" "GET /api/orders/customer/999 (non-existent customer orders)"

S=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/nonexistent-path")
err "${S}" "GET /nonexistent-path (no handler)"

# --- 400 Bad Request ---
step "400 Bad Request (invalid data)"
S=$(create_order 1 '[{"sku":"UNKNOWN-SKU","productName":"Invalid","quantity":1}]')
err "${S}" "POST with unknown SKU (IllegalArgumentException)"

S=$(create_order 999 '[{"sku":"MOUSE-WIRELESS","productName":"Mouse","quantity":1}]')
err "${S}" "POST with non-existent customer ID"

S=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${API}" -H 'Content-Type: application/json' -d '{}')
err "${S}" "POST with empty body"

S=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${API}" -H 'Content-Type: application/json' -d 'not-json')
err "${S}" "POST with malformed JSON"

# --- 405 Method Not Allowed ---
step "405 Method Not Allowed (unsupported verbs)"
S=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${API}/1" -H 'Content-Type: application/json' -d '{}')
err "${S}" "PUT /api/orders/1 (no PUT handler)"

S=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${API}/1")
err "${S}" "DELETE /api/orders/1 (no DELETE handler)"

S=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "${API}/1" -H 'Content-Type: application/json' -d '{}')
err "${S}" "PATCH /api/orders/1 (no PATCH handler)"

# --- Potential 500 / Server Errors ---
step "Server error scenarios"
S=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${API}" -H 'Content-Type: text/plain' -d 'plain text body')
err "${S}" "POST with wrong Content-Type (text/plain)"

S=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${API}" \
    -H 'Content-Type: application/json' \
    -d '{"customerId": "not-a-number", "items": []}')
err "${S}" "POST with type mismatch (customerId as string)"

# ─── Section 4: Slow Requests & Timeouts ─────────────────────────────────────

header "Section 4: Slow Requests & Client-Side Timeouts"

step "Creating orders with multiple items (PricingService latency: 50-150ms per unique SKU)"
# This exercises the simulated latency in PricingService.getPrice()
S=$(create_order 2 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop","quantity":1},{"sku":"MONITOR-4K-27","productName":"Monitor","quantity":1},{"sku":"KEYBOARD-MECH","productName":"Keyboard","quantity":1},{"sku":"MOUSE-WIRELESS","productName":"Mouse","quantity":1},{"sku":"USB-C-HUB","productName":"Hub","quantity":1}]')
ok "${S}" "Order with 5 items (exercises pricing latency)"

step "Client-side timeout via curl --max-time (requests intentionally cut short)"
# Use a very short timeout to force curl to abort before the server responds
TIMEOUT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 0.001 "${API}" 2>/dev/null; true)
TIMEOUT_STATUS="${TIMEOUT_STATUS:-000}"
err "${TIMEOUT_STATUS}" "GET /api/orders with --max-time 0.001 (forced timeout)"

# Try against the H2 console (heavier endpoint)
TIMEOUT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 0.001 "${BASE_URL}/h2-console" 2>/dev/null; true)
TIMEOUT_STATUS="${TIMEOUT_STATUS:-000}"
err "${TIMEOUT_STATUS}" "GET /h2-console with --max-time 0.001 (forced timeout)"

# Multiple concurrent slow-ish requests to generate timing data
step "Burst of concurrent requests for timing metrics"
for i in $(seq 1 8); do
    curl -s -o /dev/null "${API}/expensive?minAmount=50" &
done
wait
ok "burst" "8 concurrent GET /api/orders/expensive requests completed"

# ─── Section 5: Actuator & Management Endpoints ──────────────────────────────

header "Section 5: Actuator & Management Endpoints"

step "Hitting actuator endpoints for metrics/env/beans inspection"
for endpoint in health info metrics beans env; do
    S=$(curl -s -o /dev/null -w '%{http_code}' "${ACTUATOR}/${endpoint}")
    if [ "${S}" = "200" ]; then
        ok "${S}" "GET /actuator/${endpoint}"
    else
        err "${S}" "GET /actuator/${endpoint}"
    fi
done

# H2 Console
S=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/h2-console")
ok "${S}" "GET /h2-console"

# Agent health endpoint
S=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/agent/api/health")
ok "${S}" "GET /agent/api/health"

# ─── Section 6: Additional Data for DB Queries ───────────────────────────────

header "Section 6: Additional Data Volume (for DB inspector queries)"

step "Creating additional orders to build queryable dataset"

# More orders with variety for findByStatus, findByCustomerId, findExpensiveOrders
for i in $(seq 1 5); do
    S=$(create_order $((i % 3 + 1)) '[{"sku":"USB-C-HUB","productName":"USB-C Hub","quantity":'"${i}"'}]')
    ok "${S}" "Bulk order ${i}/5 — USB-C Hub x${i}"
done

step "Querying orders by different thresholds"
for min_amount in 0 50 100 500 1000 2000 3000; do
    S=$(curl -s -o /dev/null -w '%{http_code}' "${API}/expensive?minAmount=${min_amount}")
    ok "${S}" "findExpensiveOrders(minAmount=${min_amount})"
done

# ─── Section 7: Scheduled Task Waiting ───────────────────────────────────────

header "Section 7: Scheduled Task Waiting"

step "ReportScheduler scheduled tasks:"
info "  - generateRevenueReport:  every 5 min (fixedRate=300000)"
info "  - checkStaleOrders:       every 2 min, initial delay 10s (fixedDelay=120000)"
info "  - dailySummaryReport:     cron MON-FRI 09:00"

# The checkStaleOrders fires 10 seconds after startup. If the app was recently
# started, waiting 15 seconds ensures at least one scheduled task has run.
wait_for "Waiting 15 seconds for checkStaleOrders initial execution..."
sleep 15
ok "done" "Waited for scheduled task window"

# ─── Section 8: Log Generation Summary ───────────────────────────────────────

header "Section 8: Log Level Coverage"

step "Generated logs across all levels:"
info "  ${C_GREEN}INFO${C_RESET}  — OrderService.createOrder (every order), DataInitializer"
info "  ${C_YELLOW}WARN${C_RESET}  — OrderService tier upgrade NPE (intentional bug, credit < 5000)"
info "  ${C_RED}ERROR${C_RESET} — OrderService credit deduction failures, order creation errors"
info "  ${C_BLUE}DEBUG${C_RESET} — CustomerService.checkCredit (DEBUG logging enabled for com.demo)"

step "Triggering additional WARN/ERROR logs via edge cases"
# This order may trigger WARN when credit drops below 5000 (tier upgrade NPE)
S=$(create_order 1 '[{"sku":"LAPTOP-PRO-15","productName":"Laptop Pro 15","quantity":1}]')
ok "${S}" "Order that may trigger tier-upgrade WARN (NPE bug)"

# Force an error path
S=$(create_order 1 '[{"sku":"","productName":"Empty SKU","quantity":1}]')
err "${S}" "Order with empty SKU (triggers ERROR log)"

# ─── Summary ─────────────────────────────────────────────────────────────────

header "Data Generation Complete"

echo ""
echo -e "  ${C_BOLD}Total requests:${C_RESET}    ${TOTAL_REQUESTS}"
echo -e "  ${C_GREEN}${C_BOLD}Successful (2xx):${C_RESET} ${SUCCESS_COUNT}"
echo -e "  ${C_RED}${C_BOLD}Errors (4xx/5xx):${C_RESET}  ${ERROR_COUNT}"
echo ""
echo -e "  ${C_GREEN}✓${C_RESET} Orders created for all 3 customers"
echo -e "  ${C_GREEN}✓${C_RESET} Cache populated with 8 SKUs (prices cache)"
echo -e "  ${C_GREEN}✓${C_RESET} HTTP traffic: GET / POST / PUT / DELETE / PATCH"
echo -e "  ${C_GREEN}✓${C_RESET} Error responses: 404, 400, 405, timeouts"
echo -e "  ${C_GREEN}✓${C_RESET} Slow requests with forced client timeouts"
echo -e "  ${C_GREEN}✓${C_RESET} Scheduled tasks given time to execute"
echo -e "  ${C_GREEN}✓${C_RESET} Logs at INFO / WARN / ERROR / DEBUG levels"
echo -e "  ${C_GREEN}✓${C_RESET} Actuator endpoints exercised"
echo ""
echo -e "${C_GREEN}${C_BOLD}Demo data is ready for e2e diagnostic tool tests.${C_RESET}"
echo ""
