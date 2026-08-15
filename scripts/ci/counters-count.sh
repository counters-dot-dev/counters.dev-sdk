#!/usr/bin/env bash
# Frozen CI counter keys:
#   ci.builds.ok ci.builds.failed ci.build_seconds ci.tests.passed
#   ci.tests.failed ci.test_seconds ci.deploys
# Frozen app members:
#   web server admin-web ops-cli operations-gateway deploy-pipeline openapi
#   sdk-go sdk-java sdk-typescript server-integration sdk-e2e
# Never use unbounded members: PR number, branch name, commit SHA, run id,
# run attempt, actor, org id, user id, email, or counter key.
set -euo pipefail

readonly DEFAULT_BASE_URL="https://api.counters.dev/v1"
readonly CI_COUNTER_KEYS=(
  ci.builds.ok
  ci.builds.failed
  ci.build_seconds
  ci.tests.passed
  ci.tests.failed
  ci.test_seconds
  ci.deploys
)
readonly CI_APP_MEMBERS=(
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
)

contains() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

print_contract() {
  printf 'keys:\n'
  printf '%s\n' "${CI_COUNTER_KEYS[@]}"
  printf 'members:\n'
  printf '%s\n' "${CI_APP_MEMBERS[@]}"
}

warn() {
  echo "::warning::$*"
}

valid_amount() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

count() {
  local key="$1"
  local member="$2"
  local amount="$3"
  local base_url

  [[ -n "${COUNTERS_API_KEY:-}" ]] || { echo "no key; skipping $key"; return 0; }
  contains "$key" "${CI_COUNTER_KEYS[@]}" || { warn "unknown counters.dev key '$key' ignored"; return 0; }
  contains "$member" "${CI_APP_MEMBERS[@]}" || { warn "unknown counters.dev member '$member' ignored"; return 0; }
  valid_amount "$amount" || { warn "invalid counters.dev amount '$amount' for $key ignored"; return 0; }
  [[ "$amount" != "0" ]] || return 0
  [[ -n "${IDEM_SCOPE:-}" ]] || { warn "missing IDEM_SCOPE; counters.dev write for $key ignored"; return 0; }

  base_url="${BASE_URL:-$DEFAULT_BASE_URL}"
  base_url="${base_url%/}"
  curl -sS --fail --max-time 10 -o /dev/null \
    -X POST "${base_url}/counters/${key}/members/${member}/add" \
    -H "Authorization: Bearer ${COUNTERS_API_KEY}" \
    -H "Idempotency-Key: gh:${IDEM_SCOPE}:${key}" \
    -H "Content-Type: application/json" \
    -d "{\"amount\":\"${amount}\"}" || echo "::warning::counters.dev write failed for $key (ignored)"
}

queue_count() {
  count "$@" &
}

main() {
  local app="${APP:-}"
  local now
  local start
  local elapsed
  local outcome
  local passed
  local failed

  if [[ "${1:-}" == "--print-contract" ]]; then
    print_contract
    return 0
  fi

  [[ -n "${COUNTERS_API_KEY:-}" ]] || { echo "no key; skipping counters.dev metrics"; return 0; }
  contains "$app" "${CI_APP_MEMBERS[@]}" || { warn "unknown counters.dev APP '$app' ignored"; return 0; }

  now="$(date +%s)"
  start="${CI_T0:-$now}"
  valid_amount "$start" || start="$now"
  elapsed=$((now - start))
  outcome="${BUILD_OUTCOME:-failure}"

  queue_count ci.build_seconds "$app" "$elapsed"
  if [[ "$outcome" == "success" ]]; then
    queue_count ci.builds.ok "$app" 1
  else
    queue_count ci.builds.failed "$app" 1
  fi

  passed="${PASSED:-}"
  failed="${FAILED:-}"
  if [[ -n "${passed}${failed}" ]]; then
    queue_count ci.tests.passed "$app" "${passed:-0}"
    queue_count ci.tests.failed "$app" "${failed:-0}"
    queue_count ci.test_seconds "$app" "$elapsed"
  fi

  wait
}

main "$@"
