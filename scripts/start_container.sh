# /home/ec2-user/app/scripts/start_container.sh

#!/bin/bash

# --- 1. 환경 변수 및 이미지 URI 설정 ---
# CodeDeploy에 의해 스크립트가 실행되는 위치를 기준으로 image.env 파일 경로 설정
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/image.env" # TAG 변수 로드

AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
AWS_REGION="ap-northeast-2"
ECR_REPOSITORY="itplace-app"
ECR_IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPOSITORY}:${TAG}"

# --- 2. ECR 로그인 및 이미지 Pull ---
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
docker pull ${ECR_IMAGE_URI}

# --- 3. Docker 컨테이너 실행 ---
# CodeDeploy에 의해 복사된 secret 파일의 절대 경로
BASE_APP_DIR="/home/ec2-user/app"
SECRET_FILE_PATH="${BASE_APP_DIR}/application-secret.yml"

# 이전 컨테이너 강제 삭제 (stop_container 실패 대비)
docker rm -f itplace-app 2>/dev/null || true

# 최종 컨테이너 실행 명령어
docker run -d -p 8080:8080 --name itplace-app \
  -e "SPRING_PROFILES_ACTIVE=aws" \
  -v "${SECRET_FILE_PATH}:/app/application-secret.yml" \
  --name itplace-app "${ECR_IMAGE_URI}"