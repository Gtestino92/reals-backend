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

PREVIOUS_CONTAINER_EXISTS=false
PREVIOUS_IMAGE_REF=""
PREVIOUS_IMAGE_ID=""

emit_stage() {
  echo "DEPLOY_STAGE=$1"
}

emit_error() {
  echo "ERROR_CODE=$1"
  echo "ERROR_MESSAGE=$2"
  echo "Inspect the container logs on the EC2 host through an authorized SSM session."
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

pull_and_verify_image() {
  local image="$1"
  local expected_revision="$2"

  emit_stage "PULL_IMAGE"
  docker pull "$image" >/dev/null 2>&1 ||
    fail "IMAGE_PULL_FAILED" "failed to pull requested image; verify GHCR visibility or host Docker credentials"

  local revision_label
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
    echo "ERROR_CODE=READINESS_FAILED"
    return 1
  fi

  emit_stage "VERIFY_PING"
  if ! wait_for_endpoint "ping" "$PING_URL" "ok"; then
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

deploy() {
  local image_tag="$1"
  local expected_revision="$2"
  local image

  validate_inputs "$image_tag" "$expected_revision"
  require_prerequisites

  image="$(requested_image "$image_tag")"
  pull_and_verify_image "$image" "$expected_revision"
  capture_current_deployment

  emit_stage "REPLACE_CONTAINER"
  if ! remove_existing_container_strict; then
    return 1
  fi

  if ! start_container "$image"; then
    echo "ERROR_CODE=NEW_CONTAINER_START_FAILED"
    rollback_previous_container
    return 1
  fi

  if ! verify_runtime_health; then
    rollback_previous_container
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
