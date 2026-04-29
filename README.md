# ITPLACE User API

ITPLACE 사용자 서비스 백엔드입니다.
사용자 인증, 혜택 탐색, 지도 기반 제휴처 검색, 관심 혜택, 사용 이력, 이벤트, AI 추천·질문 기능을 제공합니다.

## 주요 기능

- 일반 회원가입 / 로그인 / 로그아웃
- Kakao OAuth 로그인
- JWT 기반 인증과 refresh token 처리
- 이메일 인증과 reCAPTCHA 검증
- 사용자 정보 조회 / 수정 / 탈퇴
- LG U+ 멤버십 정보 연동 흐름
- Kakao 좌표 기반 주변 제휴처 검색
- 제휴처 카테고리 / 키워드 검색
- 혜택 목록, 상세, 등급별 혜택 조회
- 관심 혜택 등록 / 삭제 / 조회
- 멤버십 혜택 사용 이력과 누적 혜택 금액 조회
- 스크래치 쿠폰 이벤트와 쿠폰 사용 처리
- 사용자 행동 로그 저장
- 개인 맞춤 추천과 AI 질문 응답
- 사용자 채팅 상담 WebSocket API
- Swagger / OpenAPI 문서 제공

## 기술 스택

- Java 17
- Spring Boot 3.4.7
- Spring Web / WebFlux / WebSocket
- Spring Security / OAuth2 Client
- Spring Data JPA
- PostgreSQL
- MongoDB
- Redis
- Elasticsearch Java Client
- Spring AI / OpenAI
- JWT
- Spring Mail
- Springdoc OpenAPI
- Gradle

## 시작하기

### 1. 사전 준비

- Java 17
- PostgreSQL
- Redis
- MongoDB
- Elasticsearch
- OpenAI API key
- Kakao OAuth app
- Google reCAPTCHA secret

### 2. 환경 변수 설정

`.env.example`을 참고해 로컬 `.env` 또는 실행 환경 변수를 구성합니다.

```env
SPRING_PROFILES_ACTIVE=local
PG_URL=jdbc:postgresql://localhost:5432/itplace
PG_USERNAME=postgres
PG_PASSWORD=
REDIS_HOST=localhost
MONGODB_URI=mongodb://localhost:27017/itplace
JWT_SECRET=
OPENAI_API_KEY=
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
```

로컬 프로필은 `src/main/resources/application-local.yml`의 placeholder 값을 포함합니다. 실제 외부 연동이 필요한 기능은 개인 환경 값으로 교체해야 합니다.

### 3. 로컬 실행

```bash
./gradlew bootRun
```

기본 프로필은 `local`이며, 사용자 API는 로컬에서 `8080` 포트를 사용합니다.

## 검증 및 빌드

```bash
./gradlew test
./gradlew build -x test
```

## API 문서

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- 정적 Swagger 스펙: `src/main/resources/static/swagger.yml`

## 프로젝트 구조

```text
src/main/java/com/itplace/userapi/
  ai/          AI 질문/추천
  benefit/     혜택 조회
  chat/        사용자 채팅 상담
  common/      공통 설정/응답/유틸
  event/       이벤트와 쿠폰
  favorite/    관심 혜택
  history/     멤버십 사용 이력
  log/         사용자 행동 로그
  map/         지도 기반 제휴처 검색
  partner/     제휴처 정보
  recommend/   추천
  security/    인증/인가
  user/        사용자 정보
```

## 배포

현재 지원되는 배포 경로는 `.github/workflows/oci-bluegreen-deploy.yml`과 `scripts/deploy-userapi.sh`입니다.
과거 `.disabled` 워크플로우는 보존용이므로 재활성화 전 현재 OCI 배포 경로와 차이를 확인해야 합니다.

## 연관 레포지토리

- User Front: `itplace-user-front`
- Admin Front: `itplace-admin-front`
- Admin API: `itplace-admin-api`
