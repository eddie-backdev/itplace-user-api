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

cleanup_old_container() {
  if [[ "${SWITCHED_TO_NEW}" != "true" ]]; then
    return 0
  fi

  if docker ps -a --format '{{.Names}}' | grep -qx "${OLD_CONTAINER}"; then
    echo "[userapi] removing old container ${OLD_CONTAINER}"
    docker rm -f "${OLD_CONTAINER}" >/dev/null 2>&1 || true
  fi
}

trap cleanup_old_container EXIT

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
  --env-file "${ENV_FILE}" \
  -v "${LOGS_DIR}:/app/logs" \
  -v "${PROMPTS_DIR}:/app/prompts" \
  "${LOCAL_IMAGE}"

echo "[userapi] waiting for health check ${HEALTH_ENDPOINT}"

for ((i=1; i<=HEALTH_CHECK_MAX_ATTEMPTS; i++)); do
  if docker run --rm \
    --network "${APP_NETWORK}" \
    "${HEALTH_CHECK_IMAGE}" \
    -fsS "http://${NEW_CONTAINER}:${APP_PORT}${HEALTH_ENDPOINT}" >/dev/null; then
    echo "[userapi] health check passed"
    break
  fi

  if [[ "${i}" -eq "${HEALTH_CHECK_MAX_ATTEMPTS}" ]]; then
    echo "[userapi] health check failed"
    docker logs "${NEW_CONTAINER}" || true
    exit 1
  fi

  sleep "${HEALTH_CHECK_INTERVAL_SECONDS}"
done

cat > "${UPSTREAM_FILE}" <<EOF
upstream userapi_active {
    server ${NEW_CONTAINER}:${APP_PORT};
}
EOF

docker exec "${NGINX_CONTAINER}" nginx -t
docker exec "${NGINX_CONTAINER}" nginx -s reload

echo "[userapi] switched traffic to ${NEW_CONTAINER}"

SWITCHED_TO_NEW="true"
cleanup_old_container

if docker ps -a --format '{{.Names}}' | grep -qx "${OLD_CONTAINER}"; then
  echo "[userapi] old container still exists after cleanup: ${OLD_CONTAINER}" >&2
  docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}" | grep "${OLD_CONTAINER}" >&2 || true
  exit 1
fi

echo "[userapi] deploy complete"
