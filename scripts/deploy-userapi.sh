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
GHCR_USERNAME="${GHCR_USERNAME:-}"
GHCR_TOKEN="${GHCR_TOKEN:-}"
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

echo "[userapi] active=${ACTIVE}, inactive=${INACTIVE}"
echo "[userapi] deploying image=${IMAGE}"

docker rm -f "${NEW_CONTAINER}" >/dev/null 2>&1 || true

if [[ -n "${GHCR_USERNAME}" && -n "${GHCR_TOKEN}" ]]; then
  echo "${GHCR_TOKEN}" | docker login "${REGISTRY_HOST}" -u "${GHCR_USERNAME}" --password-stdin
fi

docker pull "${IMAGE}"

docker run -d \
  --name "${NEW_CONTAINER}" \
  --restart unless-stopped \
  --network "${APP_NETWORK}" \
  --env-file "${ENV_FILE}" \
  -v "${LOGS_DIR}:/app/logs" \
  -v "${PROMPTS_DIR}:/app/prompts" \
  "${IMAGE}"

echo "[userapi] waiting for health check ${HEALTH_ENDPOINT}"

for i in {1..24}; do
  if docker run --rm \
    --network "${APP_NETWORK}" \
    "${HEALTH_CHECK_IMAGE}" \
    -fsS "http://${NEW_CONTAINER}:${APP_PORT}${HEALTH_ENDPOINT}" >/dev/null; then
    echo "[userapi] health check passed"
    break
  fi

  if [[ "${i}" -eq 24 ]]; then
    echo "[userapi] health check failed"
    docker logs "${NEW_CONTAINER}" || true
    exit 1
  fi

  sleep 5
done

cat > "${UPSTREAM_FILE}" <<EOF
upstream userapi_active {
    server ${NEW_CONTAINER}:${APP_PORT};
}
EOF

docker exec "${NGINX_CONTAINER}" nginx -t
docker exec "${NGINX_CONTAINER}" nginx -s reload

echo "[userapi] switched traffic to ${NEW_CONTAINER}"

docker rm -f "${OLD_CONTAINER}" >/dev/null 2>&1 || true

echo "[userapi] deploy complete"
