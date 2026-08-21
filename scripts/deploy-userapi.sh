#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${1:-}"

if [[ -z "${IMAGE_TAG}" ]]; then
  echo "Usage: $0 <image-tag>"
  exit 1
fi

REGISTRY_HOST="${REGISTRY_HOST:-ghcr.io}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-ghcr.io/eddie-backdev/itplace-user-api}"
APP_NETWORK="${APP_NETWORK:-app-network}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/app/env/userapi.env}"
UPSTREAM_FILE="${UPSTREAM_FILE:-/home/ubuntu/app/nginx/conf.d/userapi-upstream.conf}"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx-proxy}"
APP_PORT="${APP_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-9090}"
HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-/actuator/health}"
HEALTH_CHECK_IMAGE="${HEALTH_CHECK_IMAGE:-curlimages/curl:8.7.1}"
HEALTH_CHECK_MAX_ATTEMPTS="${HEALTH_CHECK_MAX_ATTEMPTS:-30}"
HEALTH_CHECK_INTERVAL_SECONDS="${HEALTH_CHECK_INTERVAL_SECONDS:-2}"
GHCR_USERNAME="${GHCR_USERNAME:-}"
GHCR_TOKEN="${GHCR_TOKEN:-}"
LOCAL_IMAGE_NAME="${LOCAL_IMAGE_NAME:-itplace-user-api}"
LOGS_DIR="${LOGS_DIR:-/home/ubuntu/app/logs/userapi}"
PROMPTS_DIR="${PROMPTS_DIR:-/home/ubuntu/prompts}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[userapi] env file not found: ${ENV_FILE}"
  exit 1
fi

if [[ ! -f "${UPSTREAM_FILE}" ]]; then
  echo "[userapi] upstream file not found: ${UPSTREAM_FILE}"
  exit 1
fi

mkdir -p "${LOGS_DIR}"

if grep -q "userapi-blue" "${UPSTREAM_FILE}"; then
  ACTIVE="blue"
  INACTIVE="green"
else
  ACTIVE="green"
  INACTIVE="blue"
fi

NEW_CONTAINER="userapi-${INACTIVE}"
OLD_CONTAINER="userapi-${ACTIVE}"
IMAGE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"
LOCAL_IMAGE="${LOCAL_IMAGE_NAME}:${IMAGE_TAG}"
SWITCHED_TO_NEW="false"
UPSTREAM_UPDATE_STARTED="false"
UPSTREAM_BACKUP=""

print_new_container_diagnostics() {
  echo "[userapi] new container diagnostics: ${NEW_CONTAINER}" >&2
  docker inspect -f \
    'status={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}} restartCount={{.RestartCount}}' \
    "${NEW_CONTAINER}" >&2 || true
  docker logs --tail 200 "${NEW_CONTAINER}" >&2 || true
}

cleanup_containers() {
  local exit_code=$?

  if [[ "${SWITCHED_TO_NEW}" == "true" ]]; then
    if docker ps -a --format '{{.Names}}' | grep -qx "${OLD_CONTAINER}"; then
      echo "[userapi] removing old container ${OLD_CONTAINER}"
      docker rm -f "${OLD_CONTAINER}" >/dev/null 2>&1 || true
    fi
    return "${exit_code}"
  fi

  if [[ "${exit_code}" -ne 0 ]]; then
    if [[ "${UPSTREAM_UPDATE_STARTED}" == "true" && -f "${UPSTREAM_BACKUP}" ]]; then
      echo "[userapi] restoring previous Nginx upstream"
      cp "${UPSTREAM_BACKUP}" "${UPSTREAM_FILE}"
      docker exec "${NGINX_CONTAINER}" nginx -t >/dev/null 2>&1 || true
      docker exec "${NGINX_CONTAINER}" nginx -s reload >/dev/null 2>&1 || true
    fi

    if docker ps -a --format '{{.Names}}' | grep -qx "${NEW_CONTAINER}"; then
      echo "[userapi] removing failed container ${NEW_CONTAINER}"
      docker rm -f "${NEW_CONTAINER}" >/dev/null 2>&1 || true
    fi
  fi

  [[ -z "${UPSTREAM_BACKUP}" ]] || rm -f "${UPSTREAM_BACKUP}"
  return "${exit_code}"
}

trap cleanup_containers EXIT

echo "[userapi] active=${ACTIVE}, inactive=${INACTIVE}"
echo "[userapi] deploying image=${IMAGE}"
echo "[userapi] local image alias=${LOCAL_IMAGE}"

docker rm -f "${NEW_CONTAINER}" >/dev/null 2>&1 || true

if [[ -n "${GHCR_USERNAME}" && -n "${GHCR_TOKEN}" ]]; then
  echo "${GHCR_TOKEN}" | docker login "${REGISTRY_HOST}" -u "${GHCR_USERNAME}" --password-stdin
fi

docker pull "${IMAGE}"
docker tag "${IMAGE}" "${LOCAL_IMAGE}"

docker run -d \
  --name "${NEW_CONTAINER}" \
  --restart unless-stopped \
  --network "${APP_NETWORK}" \
  --label "itplace.monitoring.enabled=true" \
  --label "itplace.service=user-api" \
  --label "itplace.metrics.port=${MANAGEMENT_PORT}" \
  -e "MANAGEMENT_SERVER_PORT=${MANAGEMENT_PORT}" \
  --env-file "${ENV_FILE}" \
  -v "${LOGS_DIR}:/app/logs" \
  -v "${PROMPTS_DIR}:/app/prompts" \
  "${LOCAL_IMAGE}"

NEW_CONTAINER_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${NEW_CONTAINER}")"

echo "[userapi] waiting for health check ${HEALTH_ENDPOINT}"

for ((i=1; i<=HEALTH_CHECK_MAX_ATTEMPTS; i++)); do
  if docker run --rm \
    --network "${APP_NETWORK}" \
    "${HEALTH_CHECK_IMAGE}" \
    -fsS "http://${NEW_CONTAINER_IP}:${MANAGEMENT_PORT}${HEALTH_ENDPOINT}" >/dev/null; then
    echo "[userapi] health check passed"
    break
  fi

  CONTAINER_STATUS="$(docker inspect -f '{{.State.Status}}' "${NEW_CONTAINER}")"
  CONTAINER_RESTART_COUNT="$(docker inspect -f '{{.RestartCount}}' "${NEW_CONTAINER}")"
  if [[ "${CONTAINER_STATUS}" != "running" || "${CONTAINER_RESTART_COUNT}" -gt 0 ]] \
      || docker logs "${NEW_CONTAINER}" 2>&1 | grep -q 'Application run failed'; then
    echo "[userapi] new container failed during startup"
    print_new_container_diagnostics
    exit 1
  fi

  if [[ "${i}" -eq "${HEALTH_CHECK_MAX_ATTEMPTS}" ]]; then
    echo "[userapi] health check failed"
    print_new_container_diagnostics
    exit 1
  fi

  sleep "${HEALTH_CHECK_INTERVAL_SECONDS}"
done

UPSTREAM_BACKUP="$(mktemp "${UPSTREAM_FILE}.backup.XXXXXX")"
cp "${UPSTREAM_FILE}" "${UPSTREAM_BACKUP}"
UPSTREAM_UPDATE_STARTED="true"

cat > "${UPSTREAM_FILE}" <<EOF
upstream userapi_active {
    server ${NEW_CONTAINER}:${APP_PORT};
}
EOF

docker exec "${NGINX_CONTAINER}" nginx -t
docker exec "${NGINX_CONTAINER}" nginx -s reload

echo "[userapi] switched traffic to ${NEW_CONTAINER}"

SWITCHED_TO_NEW="true"
UPSTREAM_UPDATE_STARTED="false"
rm -f "${UPSTREAM_BACKUP}"
UPSTREAM_BACKUP=""
cleanup_containers

if docker ps -a --format '{{.Names}}' | grep -qx "${OLD_CONTAINER}"; then
  echo "[userapi] old container still exists after cleanup: ${OLD_CONTAINER}" >&2
  docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}" | grep "${OLD_CONTAINER}" >&2 || true
  exit 1
fi

echo "[userapi] deploy complete"
