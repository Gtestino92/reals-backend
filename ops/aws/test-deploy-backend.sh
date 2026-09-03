#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_SCRIPT="$PROJECT_ROOT/ops/aws/deploy-backend.sh"
WORKFLOW_FILE="$PROJECT_ROOT/.github/workflows/deploy-aws-dev.yml"
CI_WORKFLOW_FILE="$PROJECT_ROOT/.github/workflows/ci.yml"

FULL_REVISION="0123456789abcdef0123456789abcdef01234567"
IMAGE_TAG="sha-0123456"
IMAGE="ghcr.io/gtestino92/reals-backend:${IMAGE_TAG}"
PREVIOUS_IMAGE_ID="sha256:previous-image"
PREVIOUS_IMAGE_REF="ghcr.io/gtestino92/reals-backend:sha-abcdef0"
SIMULATED_APP_LOG="APP_LOG user@example.com user-id-123 chat-id-456 password=super-secret"

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

assert_no_run_for_image() {
  local file="$1"
  local unexpected_image="$2"
  if grep -F "docker run" "$file" | grep -Fq "$unexpected_image"; then
    fail_test "did not expect docker run for '$unexpected_image' in $file"
  fi
}

line_number_for() {
  local file="$1"
  local pattern="$2"
  grep -n -F "$pattern" "$file" | head -n 1 | cut -d: -f1
}

assert_line_before() {
  local file="$1"
  local first_pattern="$2"
  local second_pattern="$3"
  local first_line
  local second_line

  first_line="$(line_number_for "$file" "$first_pattern")"
  second_line="$(line_number_for "$file" "$second_pattern")"

  [[ -n "$first_line" ]] || fail_test "expected '$first_pattern' in $file"
  [[ -n "$second_line" ]] || fail_test "expected '$second_pattern' in $file"
  (( first_line < second_line )) ||
    fail_test "expected '$first_pattern' before '$second_pattern' in $file"
}

create_stub_environment() {
  TEST_ROOT="$(mktemp -d)"
  export TEST_ROOT IMAGE PREVIOUS_IMAGE_ID PREVIOUS_IMAGE_REF SIMULATED_APP_LOG
  unset DOCKER_PULL_FAIL DOCKER_PULL_OUTPUT DOCKER_LABEL_REVISION DOCKER_NEW_RUN_FAIL DOCKER_ROLLBACK_RUN_FAIL DOCKER_STOP_FAIL DOCKER_RM_FAIL DOCKER_RMI_FAIL CURL_MODE ROLLBACK_CURL_MODE
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

image_state_file() {
  printf '%s/docker_images.tsv\n' "$TEST_ROOT"
}

ensure_image_state() {
  if [[ ! -f "$(image_state_file)" ]]; then
    {
      printf '%s\t%s\n' "ghcr.io/gtestino92/reals-backend:sha-abcdef0" "$PREVIOUS_IMAGE_ID"
      printf '%s\t%s\n' "$IMAGE" "sha256:cached-target"
      printf '%s\t%s\n' "ghcr.io/gtestino92/reals-backend:sha-old111" "sha256:old-backend"
      printf '%s\t%s\n' "postgres:16" "sha256:postgres"
      printf '%s\t%s\n' "other.example/reals-backend:sha-old111" "sha256:old-backend"
    } > "$(image_state_file)"
  fi
}

image_id_for_ref() {
  local requested_ref="$1"
  ensure_image_state
  awk -F '\t' -v ref="$requested_ref" '$1 == ref { print $2; found = 1; exit } END { if (!found) exit 1 }' "$(image_state_file)"
}

remove_image_ref() {
  local requested_ref="$1"
  local next_file
  ensure_image_state
  next_file="$(image_state_file).next"
  awk -F '\t' -v ref="$requested_ref" '$1 != ref' "$(image_state_file)" > "$next_file"
  mv "$next_file" "$(image_state_file)"
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
      echo "${DOCKER_PULL_OUTPUT:-$SIMULATED_APP_LOG}" >&2
      exit 1
    fi
    exit 0
    ;;
  image)
    log "$@"
    case "${2:-}" in
      ls)
        ensure_image_state
        repository="${3:-}"
        awk -F '\t' -v repository="$repository" '
          index($1, repository ":") == 1 { print $1 }
        ' "$(image_state_file)"
        exit 0
        ;;
      inspect)
        if [[ "${3:-}" == "--format" ]]; then
          case "${4:-}" in
            *".Id"*) image_id_for_ref "${5:-}"; exit 0 ;;
            *"org.opencontainers.image.revision"*) printf '%s\n' "${DOCKER_LABEL_REVISION:-0123456789abcdef0123456789abcdef01234567}"; exit 0 ;;
          esac
        fi
        ;;
    esac
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
    if [[ "${DOCKER_STOP_FAIL:-false}" == "true" ]]; then
      echo "$SIMULATED_APP_LOG" >&2
      exit 1
    fi
    write_state running false
    exit 0
    ;;
  rm)
    log "$@"
    if [[ "${DOCKER_RM_FAIL:-false}" == "true" ]]; then
      echo "$SIMULATED_APP_LOG" >&2
      exit 1
    fi
    write_state exists false
    write_state running false
    exit 0
    ;;
  rmi)
    log "$@"
    if [[ "${DOCKER_RMI_FAIL:-}" == "${2:-}" || "${DOCKER_RMI_FAIL:-}" == "true" ]]; then
      echo "$SIMULATED_APP_LOG" >&2
      exit 1
    fi
    remove_image_ref "${2:-}"
    exit 0
    ;;
  run)
    log "$@"
    image="${*: -1}"
    if [[ "$image" == "$IMAGE" && "${DOCKER_NEW_RUN_FAIL:-false}" == "true" ]]; then
      echo "$SIMULATED_APP_LOG" >&2
      exit 1
    fi
    if [[ "$image" == "$PREVIOUS_IMAGE_ID" && "${DOCKER_ROLLBACK_RUN_FAIL:-false}" == "true" ]]; then
      echo "$SIMULATED_APP_LOG" >&2
      exit 1
    fi
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
    echo "$SIMULATED_APP_LOG"
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

url="${*: -1}"
mode="${CURL_MODE:-success}"
if [[ "$image_ref" == "$PREVIOUS_IMAGE_ID" ]]; then
  mode="${ROLLBACK_CURL_MODE:-success}"
fi

case "$mode:$url" in
  always_fail:*) exit 22 ;;
  readiness_fail:*readiness*) exit 22 ;;
  ping_fail:*ping*) exit 22 ;;
esac

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
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=INVALID_IMAGE_TAG"
}

malformed_revision_rejected() {
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "not-a-full-sha"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=INVALID_REVISION"
}

revision_mismatch_rejected() {
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "1123456789abcdef0123456789abcdef01234567"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=TAG_REVISION_MISMATCH"
}

pull_failure_leaves_current_container() {
  seed_previous_container
  export DOCKER_PULL_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=IMAGE_PULL_FAILED"
  assert_not_contains "$TEST_ROOT/docker.log" "docker stop reals-backend"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rm reals-backend"
}

capture_current_occurs_before_cleanup() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_line_before "$TEST_ROOT/out" "DEPLOY_STAGE=CAPTURE_CURRENT" "DEPLOY_STAGE=CLEANUP_OLD_BACKEND_IMAGES"
  assert_line_before "$TEST_ROOT/docker.log" "docker container inspect reals-backend" "docker image ls ghcr.io/gtestino92/reals-backend"
}

cleanup_occurs_before_pull() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_line_before "$TEST_ROOT/out" "DEPLOY_STAGE=CLEANUP_OLD_BACKEND_IMAGES" "DEPLOY_STAGE=PULL_IMAGE"
  assert_line_before "$TEST_ROOT/docker.log" "docker image ls ghcr.io/gtestino92/reals-backend" "docker pull $IMAGE"
}

current_image_id_references_are_preserved() {
  seed_previous_container
  {
    printf '%s\t%s\n' "$PREVIOUS_IMAGE_REF" "$PREVIOUS_IMAGE_ID"
    printf '%s\t%s\n' "ghcr.io/gtestino92/reals-backend:development" "$PREVIOUS_IMAGE_ID"
    printf '%s\t%s\n' "ghcr.io/gtestino92/reals-backend:sha-old111" "sha256:old-backend"
  } > "$TEST_ROOT/docker_images.tsv"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rmi $PREVIOUS_IMAGE_REF"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rmi ghcr.io/gtestino92/reals-backend:development"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi ghcr.io/gtestino92/reals-backend:sha-old111"
}

old_backend_images_are_removed() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi ghcr.io/gtestino92/reals-backend:sha-old111"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi $IMAGE"
}

other_repository_images_are_not_removed() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rmi postgres:16"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rmi other.example/reals-backend:sha-old111"
}

shared_old_image_removes_only_backend_reference() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi ghcr.io/gtestino92/reals-backend:sha-old111"
  assert_contains "$TEST_ROOT/docker_images.tsv" "other.example/reals-backend:sha-old111"
}

without_current_container_cleans_all_backend_references() {
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "PREVIOUS_CONTAINER_EXISTS=false"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi $PREVIOUS_IMAGE_REF"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi $IMAGE"
  assert_contains "$TEST_ROOT/docker.log" "docker rmi ghcr.io/gtestino92/reals-backend:sha-old111"
}

cleanup_failure_aborts_before_pull_and_replace() {
  seed_previous_container
  export DOCKER_RMI_FAIL="ghcr.io/gtestino92/reals-backend:sha-old111"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=IMAGE_CLEANUP_FAILED"
  assert_not_contains "$TEST_ROOT/docker.log" "docker pull"
  assert_not_contains "$TEST_ROOT/docker.log" "docker stop reals-backend"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rm reals-backend"
}

pull_no_space_reports_controlled_detail() {
  seed_previous_container
  export DOCKER_PULL_FAIL=true
  export DOCKER_PULL_OUTPUT="failed to register layer: no space left on device"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=IMAGE_PULL_FAILED"
  assert_contains "$TEST_ROOT/out" "ERROR_DETAIL=NO_SPACE_LEFT_ON_DEVICE"
}

pull_failure_output_is_not_leaked() {
  seed_previous_container
  export DOCKER_PULL_FAIL=true
  export DOCKER_PULL_OUTPUT="unauthorized token=ghp_secret password=super-secret user@example.com"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_DETAIL=REGISTRY_AUTHORIZATION"
  assert_not_contains "$TEST_ROOT/out" "ghp_secret"
  assert_not_contains "$TEST_ROOT/out" "password=super-secret"
  assert_not_contains "$TEST_ROOT/out" "user@example.com"
}

revision_label_mismatch_leaves_current_container() {
  seed_previous_container
  export DOCKER_LABEL_REVISION="abcdef0123456789abcdef0123456789abcdef01"
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=IMAGE_REVISION_MISMATCH"
  assert_not_contains "$TEST_ROOT/docker.log" "docker stop reals-backend"
  assert_not_contains "$TEST_ROOT/docker.log" "docker rm reals-backend"
}

successful_deployment() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "$IMAGE"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=SUCCESS"
  assert_contains "$TEST_ROOT/out" "DEPLOYED_REVISION=$FULL_REVISION"
  assert_contains "$TEST_ROOT/out" "DEPLOYED_IMAGE=$IMAGE"
}

readiness_failure_triggers_rollback() {
  seed_previous_container
  export CURL_MODE=readiness_fail
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=READINESS_FAILED"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLED_BACK"
  assert_contains "$TEST_ROOT/out" "ROLLBACK_IMAGE=$PREVIOUS_IMAGE_ID"
}

ping_failure_triggers_rollback() {
  seed_previous_container
  export CURL_MODE=ping_fail
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=PING_FAILED"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLED_BACK"
  assert_contains "$TEST_ROOT/out" "ROLLBACK_IMAGE=$PREVIOUS_IMAGE_ID"
}

new_container_start_failure_triggers_rollback() {
  seed_previous_container
  export DOCKER_NEW_RUN_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=NEW_CONTAINER_START_FAILED"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLED_BACK"
  assert_contains "$TEST_ROOT/out" "ROLLBACK_IMAGE=$PREVIOUS_IMAGE_ID"
}

rollback_startup_failure_reports_failure() {
  seed_previous_container
  export CURL_MODE=readiness_fail
  export DOCKER_ROLLBACK_RUN_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLBACK_FAILED"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=ROLLBACK_START_FAILED"
}

rollback_health_failure_reports_failure() {
  seed_previous_container
  export CURL_MODE=readiness_fail
  export ROLLBACK_CURL_MODE=always_fail
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=ROLLBACK_FAILED"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=ROLLBACK_HEALTH_FAILED"
}

successful_rollback_restores_previous_exact_image() {
  seed_previous_container
  export CURL_MODE=readiness_fail
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/docker.log" "docker run"
  assert_contains "$TEST_ROOT/docker.log" "$PREVIOUS_IMAGE_ID"
  assert_contains "$TEST_ROOT/out" "ROLLBACK_IMAGE=$PREVIOUS_IMAGE_ID"
}

output_contains_controlled_markers() {
  seed_previous_container
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_success "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "DEPLOY_STAGE=PULL_IMAGE"
  assert_contains "$TEST_ROOT/out" "DEPLOY_STAGE=VERIFY_READINESS"
  assert_contains "$TEST_ROOT/out" "DEPLOY_STAGE=VERIFY_PING"
  assert_contains "$TEST_ROOT/out" "DEPLOY_RESULT=SUCCESS"
}

output_excludes_application_logs_and_secrets() {
  seed_previous_container
  export DOCKER_NEW_RUN_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_not_contains "$TEST_ROOT/out" "$SIMULATED_APP_LOG"
  assert_not_contains "$TEST_ROOT/out" "user@example.com"
  assert_not_contains "$TEST_ROOT/out" "password=super-secret"
  assert_not_contains "$TEST_ROOT/docker.log" "docker logs"
}

workflow_parameters_include_execution_timeout() {
  assert_contains "$WORKFLOW_FILE" 'executionTimeout: ["840"]'
}

workflow_requires_controlled_success_marker() {
  assert_contains "$WORKFLOW_FILE" '$DEPLOY_RESULT" != "SUCCESS"'
}

workflow_parses_and_publishes_error_detail() {
  assert_contains "$WORKFLOW_FILE" "ERROR_DETAIL="
  assert_contains "$WORKFLOW_FILE" "error_detail="
  assert_contains "$WORKFLOW_FILE" "| Error detail |"
}

workflow_does_not_dump_raw_ssm_output() {
  assert_not_contains "$WORKFLOW_FILE" "cat ssm-stdout.raw.txt"
  assert_not_contains "$WORKFLOW_FILE" "cat ssm-stderr.raw.txt"
}

current_container_stop_failure_reports_controlled_error() {
  seed_previous_container
  export DOCKER_STOP_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=CURRENT_CONTAINER_STOP_FAILED"
  assert_not_contains "$TEST_ROOT/out" "$SIMULATED_APP_LOG"
  assert_not_contains "$TEST_ROOT/out" "user@example.com"
  assert_not_contains "$TEST_ROOT/out" "password=super-secret"
  assert_no_run_for_image "$TEST_ROOT/docker.log" "$IMAGE"
}

current_container_remove_failure_reports_controlled_error() {
  seed_previous_container
  export DOCKER_RM_FAIL=true
  run_deploy "$TEST_ROOT/out" "$IMAGE_TAG" "$FULL_REVISION"
  expect_failure "$TEST_ROOT/out"
  assert_contains "$TEST_ROOT/out" "ERROR_CODE=CURRENT_CONTAINER_REMOVE_FAILED"
  assert_not_contains "$TEST_ROOT/out" "$SIMULATED_APP_LOG"
  assert_not_contains "$TEST_ROOT/out" "user@example.com"
  assert_not_contains "$TEST_ROOT/out" "password=super-secret"
  assert_no_run_for_image "$TEST_ROOT/docker.log" "$IMAGE"
}

ci_validates_deployment_scripts_before_docker_build() {
  assert_contains "$CI_WORKFLOW_FILE" "Validate AWS deployment scripts"
  assert_contains "$CI_WORKFLOW_FILE" "bash -n ops/aws/deploy-backend.sh"
  assert_contains "$CI_WORKFLOW_FILE" "bash -n ops/aws/test-deploy-backend.sh"
  assert_contains "$CI_WORKFLOW_FILE" "ops/aws/test-deploy-backend.sh"
  assert_line_before "$CI_WORKFLOW_FILE" "Validate AWS deployment scripts" "Build backend image"
}

test_case "malformed image tag is rejected" malformed_tag_rejected
test_case "malformed full revision is rejected" malformed_revision_rejected
test_case "short tag and revision mismatch is rejected" revision_mismatch_rejected
test_case "capture current occurs before cleanup" capture_current_occurs_before_cleanup
test_case "cleanup occurs before docker pull" cleanup_occurs_before_pull
test_case "current image ID references are preserved" current_image_id_references_are_preserved
test_case "old backend image references are removed" old_backend_images_are_removed
test_case "other repository images are not removed" other_repository_images_are_not_removed
test_case "shared old image removes only backend ref" shared_old_image_removes_only_backend_reference
test_case "without current container cleans backend refs" without_current_container_cleans_all_backend_references
test_case "cleanup failure aborts before pull and replace" cleanup_failure_aborts_before_pull_and_replace
test_case "pull failure leaves current container untouched" pull_failure_leaves_current_container
test_case "pull no-space failure reports controlled detail" pull_no_space_reports_controlled_detail
test_case "pull failure output is not leaked" pull_failure_output_is_not_leaked
test_case "revision label mismatch leaves current container untouched" revision_label_mismatch_leaves_current_container
test_case "successful deploy reports immutable image" successful_deployment
test_case "readiness failure triggers rollback" readiness_failure_triggers_rollback
test_case "ping failure triggers rollback" ping_failure_triggers_rollback
test_case "new-container startup failure triggers rollback" new_container_start_failure_triggers_rollback
test_case "rollback startup failure reports ROLLBACK_FAILED" rollback_startup_failure_reports_failure
test_case "rollback health failure reports ROLLBACK_FAILED" rollback_health_failure_reports_failure
test_case "successful rollback restores previous exact image" successful_rollback_restores_previous_exact_image
test_case "output contains controlled deployment markers" output_contains_controlled_markers
test_case "output excludes simulated application logs and secrets" output_excludes_application_logs_and_secrets
test_case "workflow SSM parameters include executionTimeout" workflow_parameters_include_execution_timeout
test_case "workflow requires DEPLOY_RESULT success marker" workflow_requires_controlled_success_marker
test_case "workflow parses and publishes ERROR_DETAIL" workflow_parses_and_publishes_error_detail
test_case "workflow does not dump raw SSM output" workflow_does_not_dump_raw_ssm_output
test_case "current container stop failure reports controlled error" current_container_stop_failure_reports_controlled_error
test_case "current container remove failure reports controlled error" current_container_remove_failure_reports_controlled_error
test_case "CI validates deployment scripts before Docker build" ci_validates_deployment_scripts_before_docker_build

echo "$pass_count tests passed"
