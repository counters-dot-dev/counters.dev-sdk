#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

expected=$(cat <<'EOF_EXPECTED'
keys:
ci.builds.ok
ci.builds.failed
ci.build_seconds
ci.tests.passed
ci.tests.failed
ci.test_seconds
ci.deploys
members:
web
server
admin-web
ops-cli
operations-gateway
deploy-pipeline
openapi
sdk-go
sdk-java
sdk-typescript
server-integration
sdk-e2e
EOF_EXPECTED
)
actual="$(bash scripts/ci/counters-count.sh --print-contract)"

if [[ "$actual" != "$expected" ]]; then
  diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual")
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cat > "$tmpdir/curl" <<'EOF_CURL'
#!/usr/bin/env bash
if [[ -n "${CURL_SLEEP:-}" ]]; then
  sleep "$CURL_SLEEP"
fi
{
  echo "CALL"
  printf '%s\n' "$@"
} >> "$CURL_LOG"
exit "${CURL_EXIT:-0}"
EOF_CURL
chmod +x "$tmpdir/curl"

export CURL_LOG="$tmpdir/curl.log"

PATH="$tmpdir:$PATH" \
  COUNTERS_API_KEY=ck_test \
  APP=sdk-go \
  BUILD_OUTCOME=success \
  PASSED=2 \
  FAILED=0 \
  CI_T0=1000000000 \
  IDEM_SCOPE=counters-dot-dev/counters.dev-sdk:123:2:test \
  BASE_URL=https://example.test/v1 \
  bash scripts/ci/counters-count.sh > "$tmpdir/success.out"

calls="$(grep -c '^CALL$' "$CURL_LOG" || true)"
if [[ "$calls" != "4" ]]; then
  echo "expected 4 non-zero writes, got $calls" >&2
  cat "$CURL_LOG" >&2
  exit 1
fi
grep -Fx 'Idempotency-Key: gh:counters-dot-dev/counters.dev-sdk:123:2:test:ci.builds.ok' "$CURL_LOG" > /dev/null
grep -Fx -- '--fail' "$CURL_LOG" > /dev/null
grep -Fx 'https://example.test/v1/counters/ci.tests.passed/members/sdk-go/add' "$CURL_LOG" > /dev/null
if grep -Fq 'ci.tests.failed' "$CURL_LOG"; then
  echo "zero failed-test delta should not be written" >&2
  cat "$CURL_LOG" >&2
  exit 1
fi

: > "$CURL_LOG"
started="$(python3 -c 'import time; print(time.monotonic())')"
PATH="$tmpdir:$PATH" \
  CURL_SLEEP=0.5 \
  COUNTERS_API_KEY=ck_test \
  APP=sdk-go \
  BUILD_OUTCOME=success \
  PASSED=2 \
  FAILED=0 \
  CI_T0=1000000000 \
  IDEM_SCOPE=counters-dot-dev/counters.dev-sdk:123:2:test \
  BASE_URL=https://example.test/v1 \
  bash scripts/ci/counters-count.sh > "$tmpdir/concurrent.out"
python3 - "$started" <<'EOF_TIMING'
import sys
import time

elapsed = time.monotonic() - float(sys.argv[1])
if elapsed >= 1.5:
    raise SystemExit(f"metric writes took {elapsed:.2f}s; expected concurrent dispatch")
EOF_TIMING

: > "$CURL_LOG"
PATH="$tmpdir:$PATH" \
  APP=not-a-member \
  BUILD_OUTCOME=success \
  PASSED=1 \
  FAILED=0 \
  CI_T0=1000000000 \
  IDEM_SCOPE=counters-dot-dev/counters.dev-sdk:123:2:test \
  bash scripts/ci/counters-count.sh > "$tmpdir/no-key.out"
grep -Fx 'no key; skipping counters.dev metrics' "$tmpdir/no-key.out" > /dev/null
if [[ -s "$CURL_LOG" ]]; then
  echo "empty key should not invoke curl" >&2
  cat "$CURL_LOG" >&2
  exit 1
fi

: > "$CURL_LOG"
PATH="$tmpdir:$PATH" \
  CURL_EXIT=7 \
  COUNTERS_API_KEY=ck_test \
  APP=sdk-go \
  BUILD_OUTCOME=failure \
  PASSED=0 \
  FAILED=1 \
  CI_T0=1000000000 \
  IDEM_SCOPE=counters-dot-dev/counters.dev-sdk:123:2:test \
  BASE_URL=https://example.test/v1 \
  bash scripts/ci/counters-count.sh > "$tmpdir/fail-open.out"
grep -F '::warning::counters.dev write failed for ci.build_seconds (ignored)' "$tmpdir/fail-open.out" > /dev/null

mkdir "$tmpdir/junit"
cat > "$tmpdir/junit/TEST-example.xml" <<'EOF_JUNIT'
<testsuite tests="4" failures="1" errors="0" skipped="1"></testsuite>
EOF_JUNIT
bash scripts/ci/junit-jacoco-summary.sh "$tmpdir/junit" "" "$tmpdir/junit.out" > "$tmpdir/junit-summary.md"
grep -Fx -- '- unit: **2 passed / 1 failed / 1 skipped**' "$tmpdir/junit-summary.md" > /dev/null
grep -Fx 'passed=2' "$tmpdir/junit.out" > /dev/null
grep -Fx 'failed=1' "$tmpdir/junit.out" > /dev/null

echo "counters-count contract: ok"
