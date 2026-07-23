#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_SCRIPT="$PROJECT_ROOT/ops/aws/deploy-backend.sh"

FULL_REVISION="0123456789abcdef0123456789abcdef01234567"
IMAGE_TAG="sha-0123456"
IMAGE="ghcr.io/gtestino92/reals-backend:${IMAGE_TAG}"
PREVIOUS_IMAGE_ID="sha256:previous-image"
PREVIOUS_IMAGE_REF="ghcr.io/gtestino92/reals-backend:sha-abcdef0"

pass_count=0

fail_test() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq "$expected" "$file" || fail_test "expected '$expected' in $file"
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq "$unexpected" "$file"; then
    fail_test "did not expect '$unexpected' in $file"
  fi
}

create_stub_environment() {
  TEST_ROOT="$(mktemp -d)"
  export TEST_ROOT
  unset DOCKER_PULL_FAIL DOCKER_LABEL_REVISION CURL_MODE
  mkdir -p "$TEST_ROOT/bin"
  printf 'SPRING_PROFILES_ACTIVE=dev\n' > "$TEST_ROOT/backend.env"
  : > "$TEST_ROOT/docker.log"

  cat > "$TEST_ROOT/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "docker $*" >> "$TEST_ROOT/docker.log"
}

state_file() {
  printf '%s/container_%s\n' "$TEST_ROOT" "$1"
}

read_state() {
  local key="$1"
  local default="${2:-}"
  local file
  file="$(state_file "$key")"
  if [[ -f "$file" ]]; then
    cat "$file"
  else
    printf '%s' "$default"
  fi
}

write_state() {
  local key="$1"
  local value="$2"
  printf '%s' "$value" > "$(state_file "$key")"
}

case "${1:-}" in
  info)
    log "$@"
    exit 0
    ;;
  pull)
    log "$@"
    if [[ "${DOCKER_PULL_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    exit 0
    ;;
  image)
    log "$@"
    if [[ "${2:-}" == "inspect" ]]; then
      printf '%s\n' "${DOCKER_LABEL_REVISION:-0123456789abcdef0123456789abcdef01234567}"
      exit 0
    fi
    ;;
  container)
    log "$@"
    if [[ "${2:-}" == "inspect" ]]; then
      if [[ "$(read_state exists false)" != "true" ]]; then
        exit 1
      fi
      if [[ "${3:-}" == "--format" ]]; then
        case "${4:-}" in
          *".State.Running"*) read_state running false; echo ;;
          *".Config.Image"*) read_state image_ref; echo ;;
          *".Image"*) read_state image_id; echo ;;
          *) echo "" ;;
        esac
      fi
      exit 0
    fi
    ;;
  stop)
    log "$@"
    write_state running false
    exit 0
    ;;
  rm)
    log "$@"
    write_state exists false
    write_state running false
    exit 0
    ;;
  run)
    log "$@"
    image="${@: -1}"
    write_state exists true
    write_state running true
    write_state image_ref "$image"
    if [[ "$image" == sha256:* ]]; then
      write_state image_id "$image"
    else
      write_state image_id "sha256:new-image"
    fi
    echo "new-container-id"
    exit 0
    ;;
  logs)
    log "$@"
    echo "simulated log tail without secrets"
    exit 0
    ;;
esac

echo "unexpected docker invocation: $*" >&2
exit 64
STUB

  cat > "$TEST_ROOT/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

image_ref=""
if [[ -f "$TEST_ROOT/container_image_ref" ]]; then
  image_ref="$(cat "$TEST_ROOT/container_image_ref")"
fi

url="${@: -1}"
mode="${CURL_MODE:-success}"

if [[ "$mode" == "rollback_success" && "$image_ref" != sha256:previous-image ]]; then
  exit 22
fi

if [[ "$mode" == "always_fail" ]]; then
  exit 22
fi

case "$url" in
  *readiness*) printf '{"status":"UP"}\n' ;;
  *ping*) printf '{"status":"ok"}\n' ;;
  *) printf '{"status":"unknown"}\n' ;;
esac
STUB

  chmod +x "$TEST_ROOT/bin/docker" "$TEST_ROOT/bin/curl"
  export PATH="$TEST_ROOT/bin:$PATH"
  export ENV_FILE="$TEST_ROOT/backend.env"
  export HEALTH_RETRIES=2
  export HEALTH_DELAY_SECONDS=0
  export HEALTH_TIMEOUT_SECONDS=1
}

destroy_stub_environment() {
  rm -rf "$TEST_ROOT"
}

seed_previous_container() {
  printf 'true' > "$TEST_ROOT/container_exists"
  printf 'true' > "$TEST_ROOT/container_running"
  printf '%s' "$PREVIOUS_IMAGE_REF" > "$TEST_ROOT/container_image_ref"
  printf '%s' "$PREVIOUS_IMAGE_ID" > "$TEST_ROOT/container_image_id"
}

run_deploy() {
  local output_file="$1"
  shift

  (
    set +e
    "$DEPLOY_SCRIPT" "$@" >"$output_file" 2>&1
    echo "$?" > "$output_file.exit"
  )
}

expect_success() {
  local output_file="$1"
  [[ "$(cat "$output_file.exit")" == "0" ]] || {
    cat "$output_file" >&2
    fail_test "expected success"
  }
}

expect_failure() {
  local output_file="$1"
  [[ "$(cat "$output_file.exit")" != "0" ]] || {
    cat "$output_file" >&2
    fail_test "expected failure"
  }
}

test_case() {
  local name="$1"
  shift

  create_stub_environment
  "$@"
  destroy_stub_environment

  pass_count=$((pass_count + 1))
  echo "ok $pass_count - $name"
}

malformed_tag_rejected() {
  run_deploy "$TEST_ROOT/out" "development" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "image tag must be an immutable"
}

malformed_revision_rejected() {
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "not-a-full-sha"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "full 40-character"
}

revision_mismatch_rejected() {
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "1123456789abcdef0123456789abcdef01234567"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "does not match expected revision"
}

pull_failure_leaves_current_container() {
  seed_previous_container
  export DOCKER_PULL_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_not_contains "$TEST_ROOT/docker.log" "docker stop"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rm"
}

revision_label_mismatch_leaves_current_container() {
  seed_previous_container
  export DOCKER_LABEL_REVISION="abcdef0123456789abcdef0123456789abcdef01"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "revision label does not match"
  assert_not_contains "$TEST_ROOT/docker.log" "docker stop"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rm"
}

successful_deployment_starts_requested_image() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "$IMAGE"
}

readiness_failure_triggers_rollback() {
  seed_previous_container
  export CURL_MODE=rollback_success
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLED_BACK"
}

rollback_restores_previous_image() {
  seed_previous_container
  export CURL_MODE=rollback_success
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "docker run"
  assert_contains "$TEST_ROOT/docker.log" "$PREVIOUS_IMAGE_ID"
}

rollback_failure_reports_failure() {
  seed_previous_container
  export CURL_MODE=always_fail
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLBACK_FAILED"
}

successful_deployment_reports_success() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=SUCCESS"
  assert_contains "$TEST_ROOT/out" "DEPLOYED_REVISION=$FULL_REVISION"
}

test_case "malformed image tag is rejected" malformed_tag_rejected
test_case "malformed full revision is rejected" malformed_revision_rejected
test_case "short tag and revision mismatch is rejected" revision_mismatch_rejected
test_case "pull failure leaves current container untouched" pull_failure_leaves_current_container
test_case "revision label mismatch leaves current container untouched" revision_label_mismatch_leaves_current_container
test_case "successful deployment starts requested image" successful_deployment_starts_requested_image
test_case "readiness failure triggers rollback" readiness_failure_triggers_rollback
test_case "rollback restores previous image" rollback_restores_previous_image
test_case "rollback failure reports ROLLBACK_FAILED" rollback_failure_reports_failure
test_case "successful deployment reports SUCCESS" successful_deployment_reports_success

echo "$pass_count tests passed"
