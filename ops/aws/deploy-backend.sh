#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-ghcr.io/gtestino92/reals-backend}"
CONTAINER_NAME="${CONTAINER_NAME:-reals-backend}"
ENV_FILE="${ENV_FILE:-/etc/reals/backend.env}"
PORT_BINDING="${PORT_BINDING:-127.0.0.1:8080:8080}"
READINESS_URL="${READINESS_URL:-http://127.0.0.1:8080/actuator/health/readiness}"
PING_URL="${PING_URL:-http://127.0.0.1:8080/api/ping}"
HEALTH_RETRIES="${HEALTH_RETRIES:-18}"
HEALTH_DELAY_SECONDS="${HEALTH_DELAY_SECONDS:-5}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-5}"
ROLLBACK_MODE="${ROLLBACK_MODE:-automatic}"
DEPLOY_FAILURE_LOG_DIR="${DEPLOY_FAILURE_LOG_DIR:-/var/log/reals/deploy-failures}"
DEPLOY_FAILURE_LOG_RETENTION="${DEPLOY_FAILURE_LOG_RETENTION:-5}"
DEPLOY_FAILURE_LOG_TAIL="${DEPLOY_FAILURE_LOG_TAIL:-200}"

PREVIOUS_CONTAINER_EXISTS=false
PREVIOUS_IMAGE_REF=""
PREVIOUS_IMAGE_ID=""
PRIMARY_FAILURE_STAGE=""
PRIMARY_FAILURE_ERROR_CODE=""

emit_stage() {
  echo "DEPLOY_STAGE=$1"
}

emit_error() {
  echo "ERROR_CODE=$1"
  echo "ERROR_MESSAGE=$2"
  echo "Inspect the container logs on the EC2 host through an authorized SSM session."
}

emit_error_detail() {
  echo "ERROR_DETAIL=$1"
}

record_primary_failure() {
  local stage="$1"
  local error_code="$2"

  if [[ -z "$PRIMARY_FAILURE_STAGE" && -z "$PRIMARY_FAILURE_ERROR_CODE" ]]; then
    PRIMARY_FAILURE_STAGE="$stage"
    PRIMARY_FAILURE_ERROR_CODE="$error_code"
    echo "PRIMARY_FAILURE_STAGE=$PRIMARY_FAILURE_STAGE"
    echo "PRIMARY_FAILURE_ERROR_CODE=$PRIMARY_FAILURE_ERROR_CODE"
  fi
}

fail() {
  local error_code="$1"
  shift
  emit_error "$error_code" "$*"
  exit 1
}

validate_inputs() {
  local image_tag="$1"
  local expected_revision="$2"

  [[ "$image_tag" =~ ^sha-[0-9a-f]{7}$ ]] ||
    fail "INVALID_IMAGE_TAG" "image tag must be an immutable sha-<7 lowercase hex> tag"

  [[ "$expected_revision" =~ ^[0-9a-f]{40}$ ]] ||
    fail "INVALID_REVISION" "expected revision must be a full 40-character lowercase hexadecimal Git SHA"

  local expected_tag="sha-${expected_revision:0:7}"
  [[ "$image_tag" == "$expected_tag" ]] ||
    fail "TAG_REVISION_MISMATCH" "image tag does not match expected revision"
}

validate_rollback_mode() {
  case "$ROLLBACK_MODE" in
    automatic|disabled) ;;
    *)
      fail "INVALID_ROLLBACK_MODE" "ROLLBACK_MODE must be either automatic or disabled"
      ;;
  esac
}

require_prerequisites() {
  emit_stage "PREREQUISITES"
  command -v docker >/dev/null 2>&1 || fail "DOCKER_NOT_AVAILABLE" "docker is not available"
  command -v curl >/dev/null 2>&1 || fail "CURL_NOT_AVAILABLE" "curl is not available"
  docker info >/dev/null 2>&1 || fail "DOCKER_DAEMON_UNAVAILABLE" "docker daemon is not available"
  [[ -r "$ENV_FILE" ]] || fail "ENV_FILE_NOT_READABLE" "environment file is not readable"
}

requested_image() {
  local image_tag="$1"
  printf '%s:%s\n' "$IMAGE_REPOSITORY" "$image_tag"
}

classify_pull_error() {
  local pull_output="$1"
  local normalized
  normalized="$(printf '%s' "$pull_output" | tr '[:upper:]' '[:lower:]')"

  case "$normalized" in
    *"no space left on device"*) echo "NO_SPACE_LEFT_ON_DEVICE" ;;
    *"unauthorized"*|*"authentication required"*|*"access denied"*|*"denied:"*) echo "REGISTRY_AUTHORIZATION" ;;
    *"manifest unknown"*|*"manifest not found"*|*"not found"*) echo "MANIFEST_NOT_FOUND" ;;
    *"no such host"*|*"temporary failure in name resolution"*|*"server misbehaving"*) echo "DNS_FAILURE" ;;
    *"tls"*|*"certificate"*) echo "TLS_FAILURE" ;;
    *"timeout"*|*"timed out"*|*"i/o timeout"*|*"context deadline exceeded"*) echo "NETWORK_TIMEOUT" ;;
    *) echo "UNCLASSIFIED" ;;
  esac
}

pull_image() {
  local image="$1"
  local pull_output

  emit_stage "PULL_IMAGE"
  if ! pull_output="$(docker pull "$image" 2>&1)"; then
    emit_error_detail "$(classify_pull_error "$pull_output")"
    fail "IMAGE_PULL_FAILED" "failed to pull requested image; see ERROR_DETAIL for the controlled failure classification"
  fi
}

verify_image_revision() {
  local image="$1"
  local expected_revision="$2"
  local revision_label

  emit_stage "VERIFY_IMAGE"
  revision_label="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image" 2>/dev/null || true)"

  [[ "$revision_label" == "$expected_revision" ]] ||
    fail "IMAGE_REVISION_MISMATCH" "pulled image revision label does not match requested revision"
}

container_exists() {
  docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1
}

container_running() {
  [[ "$(docker container inspect --format '{{ .State.Running }}' "$CONTAINER_NAME" 2>/dev/null || true)" == "true" ]]
}

safe_diagnostic_value() {
  local value="$1"
  local fallback="${2:-unknown}"

  if [[ "$value" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    printf '%s\n' "$value"
  else
    printf '%s\n' "$fallback"
  fi
}

safe_exit_code_value() {
  local value="$1"

  if [[ "$value" =~ ^-?[0-9]+$ ]]; then
    printf '%s\n' "$value"
  else
    printf 'unknown\n'
  fi
}

safe_bool_value() {
  local value="$1"

  case "$value" in
    true|false) printf '%s\n' "$value" ;;
    *) printf 'unknown\n' ;;
  esac
}

safe_file_component() {
  local value="$1"
  local fallback="$2"
  local sanitized

  sanitized="$(printf '%s' "$value" | tr -c 'A-Za-z0-9_.-' '-')"
  sanitized="${sanitized#-}"
  sanitized="${sanitized%-}"
  if [[ -n "$sanitized" ]]; then
    printf '%s\n' "$sanitized"
  else
    printf '%s\n' "$fallback"
  fi
}

capture_current_deployment() {
  emit_stage "CAPTURE_CURRENT"
  if container_exists; then
    PREVIOUS_CONTAINER_EXISTS=true
    PREVIOUS_IMAGE_REF="$(docker container inspect --format '{{ .Config.Image }}' "$CONTAINER_NAME")"
    PREVIOUS_IMAGE_ID="$(docker container inspect --format '{{ .Image }}' "$CONTAINER_NAME")"
    echo "PREVIOUS_CONTAINER_EXISTS=true"
  else
    echo "PREVIOUS_CONTAINER_EXISTS=false"
  fi
}

prune_failed_container_logs_best_effort() {
  local directory="$1"
  local safe_container_name="$2"
  local retention="$DEPLOY_FAILURE_LOG_RETENTION"
  local old_file

  [[ "$retention" =~ ^[0-9]+$ ]] || retention=5
  (( retention > 0 )) || retention=5

  while IFS= read -r old_file; do
    [[ -n "$old_file" ]] || continue
    rm -f -- "$old_file" >/dev/null 2>&1 || true
  done < <(
    find "$directory" -maxdepth 1 -type f \
      -name "${safe_container_name}-*-sha-???????.log" \
      -printf '%T@ %p\n' 2>/dev/null |
      sort -rn |
      awk -v keep="$retention" 'NR > keep { sub(/^[^ ]+ /, ""); print }'
  )
}

save_failed_container_log_snapshot() {
  local image_tag="$1"
  local expected_revision="$2"
  local failed_state="$3"
  local failed_exit_code="$4"
  local failed_oom_killed="$5"
  local tail_count="$DEPLOY_FAILURE_LOG_TAIL"
  local timestamp
  local safe_container_name
  local log_path
  local temp_path

  if [[ ! "$DEPLOY_FAILURE_LOG_DIR" =~ ^/[A-Za-z0-9_./-]+$ ]]; then
    echo "FAILED_CONTAINER_DIAGNOSTICS=unavailable"
    echo "FAILED_CONTAINER_LOG_PATH=none"
    return 0
  fi

  [[ "$tail_count" =~ ^[0-9]+$ ]] || tail_count=200
  (( tail_count > 0 )) || tail_count=200

  timestamp="$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || printf 'unknown-time')"
  safe_container_name="$(safe_file_component "$CONTAINER_NAME" "reals-backend")"
  log_path="${DEPLOY_FAILURE_LOG_DIR}/${safe_container_name}-${timestamp}-${image_tag}.log"
  temp_path="${log_path}.tmp.$$"

  if (
    umask 077
    mkdir -p "$DEPLOY_FAILURE_LOG_DIR" || exit 1
    chmod 700 "$DEPLOY_FAILURE_LOG_DIR" || exit 1
    {
      printf 'timestamp=%s\n' "$timestamp"
      printf 'image_tag=%s\n' "$image_tag"
      printf 'revision=%s\n' "$expected_revision"
      printf 'container_status=%s\n' "$failed_state"
      printf 'exit_code=%s\n' "$failed_exit_code"
      printf 'oom_killed=%s\n' "$failed_oom_killed"
      printf '\n'
      docker logs --tail "$tail_count" "$CONTAINER_NAME" 2>&1 || true
    } > "$temp_path" || exit 1
    chmod 600 "$temp_path" || exit 1
    mv -f "$temp_path" "$log_path" || exit 1
    [[ -s "$log_path" ]] || exit 1
    prune_failed_container_logs_best_effort "$DEPLOY_FAILURE_LOG_DIR" "$safe_container_name" || true
  ) >/dev/null 2>&1; then
    echo "FAILED_CONTAINER_DIAGNOSTICS=saved"
    echo "FAILED_CONTAINER_LOG_PATH=$log_path"
  else
    rm -f -- "$temp_path" >/dev/null 2>&1 || true
    echo "FAILED_CONTAINER_DIAGNOSTICS=unavailable"
    echo "FAILED_CONTAINER_LOG_PATH=none"
  fi
}

capture_failed_container_diagnostics() {
  local image_tag="$1"
  local expected_revision="$2"
  local failed_state="unknown"
  local failed_exit_code="unknown"
  local failed_oom_killed="unknown"

  if ! container_exists; then
    echo "FAILED_CONTAINER_STATE=unknown"
    echo "FAILED_CONTAINER_EXIT_CODE=unknown"
    echo "FAILED_CONTAINER_OOM_KILLED=unknown"
    echo "FAILED_CONTAINER_DIAGNOSTICS=unavailable"
    echo "FAILED_CONTAINER_LOG_PATH=none"
    return 0
  fi

  failed_state="$(safe_diagnostic_value "$(docker container inspect --format '{{ .State.Status }}' "$CONTAINER_NAME" 2>/dev/null || true)")"
  failed_exit_code="$(safe_exit_code_value "$(docker container inspect --format '{{ .State.ExitCode }}' "$CONTAINER_NAME" 2>/dev/null || true)")"
  failed_oom_killed="$(safe_bool_value "$(docker container inspect --format '{{ .State.OOMKilled }}' "$CONTAINER_NAME" 2>/dev/null || true)")"

  echo "FAILED_CONTAINER_STATE=$failed_state"
  echo "FAILED_CONTAINER_EXIT_CODE=$failed_exit_code"
  echo "FAILED_CONTAINER_OOM_KILLED=$failed_oom_killed"
  save_failed_container_log_snapshot "$image_tag" "$expected_revision" "$failed_state" "$failed_exit_code" "$failed_oom_killed"
}

cleanup_old_backend_images() {
  local image_ref
  local image_id
  local image_refs

  emit_stage "CLEANUP_OLD_BACKEND_IMAGES"
  if ! image_refs="$(docker image ls "$IMAGE_REPOSITORY" --format '{{ .Repository }}:{{ .Tag }}' 2>/dev/null)"; then
    emit_error "IMAGE_CLEANUP_FAILED" "failed to list backend image references before pull"
    return 1
  fi

  while IFS= read -r image_ref; do
    [[ -n "$image_ref" ]] || continue
    [[ "$image_ref" != *":<none>" ]] || continue

    if ! image_id="$(docker image inspect --format '{{ .Id }}' "$image_ref" 2>/dev/null)"; then
      emit_error "IMAGE_CLEANUP_FAILED" "failed to inspect backend image reference before pull"
      return 1
    fi

    if [[ -n "$PREVIOUS_IMAGE_ID" && "$image_id" == "$PREVIOUS_IMAGE_ID" ]]; then
      continue
    fi

    if ! docker rmi "$image_ref" >/dev/null 2>&1; then
      emit_error "IMAGE_CLEANUP_FAILED" "failed to remove stale backend image reference before pull"
      return 1
    fi
  done <<< "$image_refs"
}

remove_existing_container_strict() {
  if container_exists; then
    if container_running; then
      if ! docker stop "$CONTAINER_NAME" >/dev/null 2>&1; then
        emit_error "CURRENT_CONTAINER_STOP_FAILED" "failed to stop current container"
        return 1
      fi
    fi
    if ! docker rm "$CONTAINER_NAME" >/dev/null 2>&1; then
      emit_error "CURRENT_CONTAINER_REMOVE_FAILED" "failed to remove current container"
      return 1
    fi
  fi
}

cleanup_existing_container_best_effort() {
  if container_exists; then
    if container_running; then
      docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true
  fi
}

start_container() {
  local image="$1"

  docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    --env-file "$ENV_FILE" \
    -p "$PORT_BINDING" \
    "$image" >/dev/null 2>&1
}

response_has_status() {
  local payload="$1"
  local expected_status="$2"

  [[ "$payload" =~ \"status\"[[:space:]]*:[[:space:]]*\"$expected_status\" ]]
}

wait_for_endpoint() {
  local name="$1"
  local url="$2"
  local expected_status="$3"
  local attempt
  local payload

  for ((attempt = 1; attempt <= HEALTH_RETRIES; attempt++)); do
    if ! container_running; then
      return 1
    fi

    payload="$(curl --fail --silent --show-error --max-time "$HEALTH_TIMEOUT_SECONDS" "$url" 2>/dev/null || true)"
    if response_has_status "$payload" "$expected_status"; then
      echo "$name check passed"
      return 0
    fi

    sleep "$HEALTH_DELAY_SECONDS"
  done

  return 1
}

verify_runtime_health() {
  emit_stage "VERIFY_READINESS"
  if ! wait_for_endpoint "readiness" "$READINESS_URL" "UP"; then
    record_primary_failure "VERIFY_READINESS" "READINESS_FAILED"
    echo "ERROR_CODE=READINESS_FAILED"
    return 1
  fi

  emit_stage "VERIFY_PING"
  if ! wait_for_endpoint "ping" "$PING_URL" "ok"; then
    record_primary_failure "VERIFY_PING" "PING_FAILED"
    echo "ERROR_CODE=PING_FAILED"
    return 1
  fi
}

rollback_previous_container() {
  local rollback_image="${PREVIOUS_IMAGE_ID:-$PREVIOUS_IMAGE_REF}"

  emit_stage "ROLLBACK"
  cleanup_existing_container_best_effort

  if [[ "$PREVIOUS_CONTAINER_EXISTS" != "true" || -z "$rollback_image" ]]; then
    echo "DEPLOY_RESULT=ROLLBACK_FAILED"
    emit_error "ROLLBACK_IMAGE_UNAVAILABLE" "no previous container image is available for rollback"
    return 1
  fi

  if ! start_container "$rollback_image"; then
    echo "DEPLOY_RESULT=ROLLBACK_FAILED"
    emit_error "ROLLBACK_START_FAILED" "failed to recreate previous container"
    return 1
  fi

  if verify_runtime_health; then
    echo "DEPLOY_RESULT=ROLLED_BACK"
    echo "ROLLBACK_IMAGE=${rollback_image}"
    return 1
  fi

  echo "DEPLOY_RESULT=ROLLBACK_FAILED"
  emit_error "ROLLBACK_HEALTH_FAILED" "rollback container failed health checks"
  return 1
}

handle_failed_deployment() {
  local image_tag="$1"
  local expected_revision="$2"

  capture_failed_container_diagnostics "$image_tag" "$expected_revision"

  if [[ "$ROLLBACK_MODE" == "automatic" ]]; then
    rollback_previous_container
    return 1
  fi

  emit_stage "ROLLBACK_DISABLED"
  cleanup_existing_container_best_effort
  echo "DEPLOY_RESULT=FAILED_ROLLBACK_DISABLED"
  echo "ROLLBACK_MODE=disabled"
  emit_error_detail "ROLLBACK_DISABLED"
  return 1
}

deploy() {
  local image_tag="$1"
  local expected_revision="$2"
  local image

  validate_inputs "$image_tag" "$expected_revision"
  validate_rollback_mode
  require_prerequisites

  image="$(requested_image "$image_tag")"
  capture_current_deployment
  cleanup_old_backend_images
  pull_image "$image"
  verify_image_revision "$image" "$expected_revision"

  emit_stage "REPLACE_CONTAINER"
  if ! remove_existing_container_strict; then
    return 1
  fi

  if ! start_container "$image"; then
    record_primary_failure "REPLACE_CONTAINER" "NEW_CONTAINER_START_FAILED"
    echo "ERROR_CODE=NEW_CONTAINER_START_FAILED"
    handle_failed_deployment "$image_tag" "$expected_revision"
    return 1
  fi

  if ! verify_runtime_health; then
    handle_failed_deployment "$image_tag" "$expected_revision"
    return 1
  fi

  echo "DEPLOY_RESULT=SUCCESS"
  echo "DEPLOYED_REVISION=${expected_revision}"
  echo "DEPLOYED_IMAGE=${image}"
}

main() {
  [[ $# -eq 2 ]] ||
    fail "INVALID_ARGUMENT_COUNT" \
      "usage: $0 sha-0123456 0123456789abcdef0123456789abcdef01234567"
  deploy "$1" "$2"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
