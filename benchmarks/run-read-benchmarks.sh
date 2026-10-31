#!/usr/bin/env bash

set -euo pipefail

# Usage:
# bash benchmarks/run-read-benchmarks.sh rest-http1
# bash benchmarks/run-read-benchmarks.sh rest-http2
# bash benchmarks/run-read-benchmarks.sh grpc

STACK="${1:-}"

case "$STACK" in
  rest-http1)
    PROTOCOL="rest"
    HTTP_VERSION="HTTP/1.1"
    ;;
  rest-http2)
    PROTOCOL="rest"
    HTTP_VERSION="HTTP/2.0"
    ;;
  grpc)
    PROTOCOL="grpc"
    HTTP_VERSION=""
    ;;
  *)
    echo "Usage: $0 {rest-http1|rest-http2|grpc}"
    exit 1
    ;;
esac

# Always work from the project root
cd "$(dirname "$0")/.."

# Benchmark settings
RPS_LIST="${RPS_LIST:-100 1000 5000}"
RUNS="${RUNS:-3}"
DURATION="${DURATION:-30s}"

# Redis warmup settings
WARMUP_RPS="${WARMUP_RPS:-400}"
WARMUP_DURATION="${WARMUP_DURATION:-15s}"

# Server addresses
REST_URL="${REST_URL:-https://127.0.0.1:8443}"
GRPC_ADDRESS="${GRPC_ADDRESS:-127.0.0.1:9090}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9091}"

# Unique session identifier
SESSION_ID="$(date -u +%Y%m%dT%H%M%SZ)"

RESULTS_DIR="benchmarks/reports/read/$SESSION_ID/$STACK"

mkdir -p "$RESULTS_DIR"

# Validate dependencies and input data
for command_name in k6 node curl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing dependency: $command_name"
    exit 1
  fi
done

if [[ ! -s benchmarks/data/read-urls.json ]]; then
  echo "Missing benchmarks/data/read-urls.json"
  exit 1
fi

if ! curl -fsS "$PROMETHEUS_URL/-/ready" >/dev/null; then
  echo "Prometheus is not ready at $PROMETHEUS_URL"
  exit 1
fi

# Prometheus Remote Write
export K6_PROMETHEUS_RW_SERVER_URL="$PROMETHEUS_URL/api/v1/write"
export K6_PROMETHEUS_RW_TREND_STATS='avg,med,p(95),p(99)'

# Common k6 arguments
K6_ARGS=(
  --insecure-skip-tls-verify
  --summary-trend-stats='avg,min,med,max,p(95),p(99)'
  -e "PROTOCOL=$PROTOCOL"
  -e "READ_MODE=random"
  -e "BASE_URL=$REST_URL"
  -e "GRPC_ADDRESS=$GRPC_ADDRESS"
  -e "GRPC_TLS=true"
)

if [[ -n "$HTTP_VERSION" ]]; then
  K6_ARGS+=(
    -e "EXPECTED_HTTP_VERSION=$HTTP_VERSION"
  )
fi

echo "=========================================="
echo "          READ BENCHMARKS"
echo "=========================================="
echo "Protocol: $STACK"
echo "Storage: PostgreSQL + Redis"
echo "RPS: $RPS_LIST"
echo "Runs per RPS: $RUNS"
echo "Duration: $DURATION"
echo "Warmup: $WARMUP_RPS RPS / $WARMUP_DURATION"
echo "Results: $RESULTS_DIR"
echo "=========================================="

for rps in $RPS_LIST; do
  for ((run=1; run<=RUNS; run++)); do

    TEST_ID="read-${STACK}-${rps}-${run}-${SESSION_ID}"

    echo
    echo "------------------------------------------"
    echo "Test: $TEST_ID"
    echo "------------------------------------------"

    # STEP 1: Warm up Redis
    echo "[1/2] Warming up Redis..."

    k6 run \
      "${K6_ARGS[@]}" \
      -e "RPS=$WARMUP_RPS" \
      -e "DURATION=$WARMUP_DURATION" \
      benchmarks/scenarios/read.js \
      > "$RESULTS_DIR/${TEST_ID}-warmup.log" 2>&1

    echo "Warmup completed."

    # STEP 2: Actual benchmark
    echo "[2/2] Running benchmark..."

    k6 run \
      "${K6_ARGS[@]}" \
      -o experimental-prometheus-rw \
      --summary-export="$RESULTS_DIR/${TEST_ID}.json" \
      --tag "testid=$TEST_ID" \
      --tag "stack=$STACK" \
      --tag "storage=postgres-redis" \
      --tag "operation=read" \
      -e "RPS=$rps" \
      -e "DURATION=$DURATION" \
      benchmarks/scenarios/read.js \
      2>&1 | tee "$RESULTS_DIR/${TEST_ID}.log"

    echo "Completed: $TEST_ID"

  done
done

echo
echo "=========================================="
echo "All $STACK benchmarks completed."
echo "=========================================="

# Automatically generate comparison tables
node benchmarks/summarize-results.mjs