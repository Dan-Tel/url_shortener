
#!/usr/bin/env bash
set -euo pipefail

# Usage:
# bash benchmarks/run-write-benchmarks.sh rest-http1
# bash benchmarks/run-write-benchmarks.sh rest-http2
# bash benchmarks/run-write-benchmarks.sh grpc

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

cd "$(dirname "$0")/.."

RPS="${RPS:-100}"
RUNS="${RUNS:-3}"
DURATION="${DURATION:-30s}"

REST_URL="${REST_URL:-https://127.0.0.1:8443}"
GRPC_ADDRESS="${GRPC_ADDRESS:-127.0.0.1:9090}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9091}"

# Calculate required number of DELETE links
if [[ ! "$DURATION" =~ ^([0-9]+)s$ ]]; then
  echo "DURATION must be in seconds, e.g. 30s"
  exit 1
fi

DURATION_SECONDS="${BASH_REMATCH[1]}"
SEED_COUNT=$((RPS * DURATION_SECONDS + 500))

SESSION_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="benchmarks/reports/write/$SESSION_ID/$STACK"

mkdir -p "$RESULTS_DIR"

# Trust the local self-signed certificate in Node.js
export NODE_EXTRA_CA_CERTS="$PWD/benchmarks/certs/benchmark-cert.pem"

# Prometheus Remote Write
export K6_PROMETHEUS_RW_SERVER_URL="$PROMETHEUS_URL/api/v1/write"
export K6_PROMETHEUS_RW_TREND_STATS='avg,med,p(95),p(99)'

for command_name in k6 node curl; do
  if ! command -v "$command_name" >/dev/null; then
    echo "Missing dependency: $command_name"
    exit 1
  fi
done

if [[ ! -s "$NODE_EXTRA_CA_CERTS" ]]; then
  echo "Missing certificate: $NODE_EXTRA_CA_CERTS"
  exit 1
fi

if ! curl -fsS "$PROMETHEUS_URL/-/ready" >/dev/null; then
  echo "Prometheus is not ready"
  exit 1
fi

K6_ARGS=(
  --insecure-skip-tls-verify
  --summary-trend-stats='avg,min,med,max,p(95),p(99)'
  -e "PROTOCOL=$PROTOCOL"
  -e "BASE_URL=$REST_URL"
  -e "GRPC_ADDRESS=$GRPC_ADDRESS"
  -e "GRPC_TLS=true"
)

if [[ -n "$HTTP_VERSION" ]]; then
  K6_ARGS+=(-e "EXPECTED_HTTP_VERSION=$HTTP_VERSION")
fi

echo "====================================="
echo "WRITE BENCHMARKS"
echo "====================================="
echo "Stack: $STACK"
echo "Storage: PostgreSQL + Redis"
echo "RPS: $RPS"
echo "Duration: $DURATION"
echo "Runs: $RUNS"
echo "DELETE seed count: $SEED_COUNT"
echo "Results: $RESULTS_DIR"
echo "====================================="

for OPERATION in create delete; do

  echo
  echo "====== $OPERATION ======"

  for ((run=1; run<=RUNS; run++)); do

    TEST_ID="${OPERATION}-${STACK}-${RPS}-${run}-${SESSION_ID}"

    echo
    echo "Running: $TEST_ID"

    # DELETE requires fresh, existing URLs on every run
    if [[ "$OPERATION" == "delete" ]]; then

      echo "Generating $SEED_COUNT fresh URLs..."

      COUNT="$SEED_COUNT" \
      OUTPUT_FILE="delete-urls.json" \
      BASE_URL="$REST_URL" \
      node benchmarks/setup/seed-data.mjs \
        > "$RESULTS_DIR/${TEST_ID}-seed.log" 2>&1

      # Check that enough URLs were generated
      node -e '
        const fs = require("node:fs");
        const urls = JSON.parse(
          fs.readFileSync("benchmarks/data/delete-urls.json", "utf8")
        );
        const required = Number(process.argv[1]);

        if (!Array.isArray(urls) || urls.length < required) {
          throw new Error(
            `Not enough DELETE URLs: ${urls.length} / ${required}`
          );
        }

        if (new Set(urls.map(u => u.shortCode)).size !== urls.length) {
          throw new Error("Duplicate shortCodes in DELETE dataset");
        }

        console.log(`Ready: ${urls.length} unique URLs`);
      ' "$SEED_COUNT"
    fi

    k6 run \
      "${K6_ARGS[@]}" \
      -o experimental-prometheus-rw \
      --summary-export="$RESULTS_DIR/${TEST_ID}.json" \
      --tag "testid=$TEST_ID" \
      --tag "stack=$STACK" \
      --tag "storage=postgres-redis" \
      --tag "operation=$OPERATION" \
      -e "RPS=$RPS" \
      -e "DURATION=$DURATION" \
      "benchmarks/scenarios/${OPERATION}.js" \
      2>&1 | tee "$RESULTS_DIR/${TEST_ID}.log"

    echo "Completed: $TEST_ID"

  done
done

echo
echo "All $STACK WRITE benchmarks completed."

node benchmarks/summarize-write-results.mjs
