# 혜택 캐시 7일과 OAuth2 공유 세션

## 구현 범위

`partner-benefits`의 고정 TTL은 7일이다. 전체 혜택의 약 1.1 MB 보관 비용과 낮은 변경 빈도를 고려한 정책이며, 7일의 최적성이나 추가 TPS 향상을 측정한 결과는 아니다. 구역 집계 캐시의 TTL은 그대로다.

공유 PostgreSQL의 `benefit`, `benefitCarrierPolicy`, `carrierTierBenefit` INSERT/UPDATE/DELETE는 트리거가 변경된 `partner_id`를 `benefit_cache_refresh`에 기록한다. 변경과 기록은 함께 commit/rollback된다. 동일 제휴처는 한 행으로 합치고, 수집·수정 시각만 바뀐 재수집은 제외한다. 제휴처 이동은 이전·이후 ID 모두 기록한다. DB 수동 SQL과 bulk 비활성화도 포착한다. TRUNCATE, 트리거 비활성화, 스키마 교체는 이 계약에 포함하지 않는다.

관리자 수동 수정과 크롤링 청크 저장·비활성화가 commit되면 admin API가 user API의 내부 갱신 API를 동기 호출한다. 혜택 데이터 복제나 import 요청은 보내지 않는다. user API는 대기열을 잠그고 변경된 제휴처의 캐시를 삭제한 뒤 source DB의 새 값으로 채운다. 새 혜택/종료 혜택은 새 목록/빈 목록으로 저장한다. 스냅샷 import에도 commit 이후 대기열 처리를 연결했다.

대기열은 재캐싱 성공 이후에만 삭제한다. Redis/DB/내부 요청 실패, 프로세스 종료 등으로 남은 기록은 기본 5초 간격의 작업으로 재처리한다. 여러 인스턴스는 `FOR UPDATE SKIP LOCKED`로 동일 행 처리를 나누고, 처리 중 같은 제휴처가 다시 변경되면 해당 기록이 삭제되지 않고 다음 갱신으로 남는다. 100개씩 최대 10회 처리해 한 요청의 작업량을 제한한다.

DB commit과 Redis 갱신은 하나의 원자 트랜잭션이 아니다. 정상 경로는 commit 직후 갱신을 시도하지만 두 단계 사이에 시작한 조회가 이전 값을 읽을 수 있다. 내부 호출 장애·동시 처리·대량 변경 시에는 대기열 재처리까지 반영이 지연될 수 있다. 5초는 재시도 간격이며 무조건 5초 이내 반영한다는 SLA가 아니다. 관리자 수정은 이미 commit됐으므로 알림 실패를 DB 수정 실패로 응답하지 않고 경고와 대기열을 남긴다.

Redis 세대 marker는 삭제와 원자적으로 교체한다. 변경 전 시작된 DB 조회의 늦은 캐시 저장은 Lua 비교로 거부한다. 재캐싱 자체가 다른 갱신과 경합하면 성공 처리하지 않고 대기열을 남긴다. 일반 조회의 MGET, 캐시 hit 시 DB 미접근, JVM별 진행 중 조회 공유는 유지한다. 전체 데이터 캐싱을 허용해 새 제휴처도 변경 처리 시 미리 적재한다.

## 내부 API와 적용 순서

- `POST /internal/benefits/cache/refresh` 또는 `/api/v1/internal/benefits/cache/refresh`
- `X-Internal-Api-Key`: user의 `app.internal.api-key`와 일치해야 한다. 빈 설정/헤더/불일치는 401이다.
- body 없음. 성공은 JSON `{"refreshedPartners": 1}`과 200이다. 숫자는 해당 호출에서 완료한 제휴처 건수이며, 다른 실행이 처리 중인 행까지 모두 완료했다는 확인 값은 아니다.
- 양쪽 API 환경에 같은 `INTERNAL_API_KEY`를 제공한다. 기존 secret 설정이 속성을 덮어쓰는 경우 최종 설정의 일치 여부를 확인한다.
- admin의 `BENEFIT_CACHE_REFRESH_URL`은 **admin 프로세스에서 접근 가능한 user API** 주소로 지정한다. 기본 localhost는 로컬 개발용이며 컨테이너 간 주소로 사용하지 않는다.
- user의 `BENEFIT_CACHE_RETRY_DELAY_MS` 기본값은 5000이다.

배포 전 공유 DB의 Flyway 이력 관리 주체를 확인하고 user API의 `V20260926_0001__track_benefit_cache_changes.sql`을 적용한다. 도입 시 기존 제휴처 전체를 대기열에 넣어 기존 캐시도 현재 DB 값과 7일 TTL로 교체한다. 그다음 새 user API, 설정을 준비한 admin API 순서로 적용하고 내부 API·대기열 소진·Redis TTL을 확인한다. 이 작업에서는 운영 DB 변경·배포를 수행하지 않았다.

대기열 점검 SQL: `SELECT count(*), min(changed_at) FROM benefit_cache_refresh;`. 값이 계속 쌓이면 user API의 갱신 실패 경고와 Redis/DB 상태를 확인한다. TTL 단축만으로 즉시 갱신 실패를 숨기지 않는다.

## OAuth2 로그인 진행 상태

`OAuth2SessionConfig`는 Spring Session Redis를 구성한다. Spring Security의 `/oauth2/authorization/kakao` → `/login/oauth2/code/kakao` 경로에서 `HttpSessionOAuth2AuthorizationRequestRepository`가 보관하는 인증 요청을 공유한다.

- Redis namespace: `itplace:user:oauth2`
- 세션 유휴 만료: 10분
- cookie: `ITPLACE_OAUTH_SESSION`, HttpOnly, SameSite=Lax, path `/`, host-only
- Secure는 기존 `app.cookie.secure` 설정을 따른다.
- 로그인 후 API 인증은 기존 JWT/STATELESS, Refresh Token은 기존 Redis 대조 경로다.

별도 code 교환 API(`/api/v1/auth/oauth/kakao`)와 앱 클라이언트의 state 처리를 이 서버 세션으로 바꾸지는 않는다. 기존 로컬 세션으로 진행 중인 로그인은 배포 전환 후 재시작이 필요할 수 있다. 이번 구현은 현재 코드에서 새로 검증한 내용이며 과거 프로젝트에서 이미 적용했다는 증거로 소급하지 않는다.

## 검증

전체 user API 테스트 462개 통과(실패·제외 0), admin API 116개 통과(명시적 opt-in 외부 live crawler 3개 제외), 양쪽 `build -x test` 통과. [실행 집계](../../output/portfolio-implementation-20260926/tests.json).

Docker 29 호환 설정으로 실제 PostgreSQL/Redis 컨테이너를 사용하는 테스트를 실행한다.

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 DOCKER_HOST=unix:///Users/eddie/.orbstack/run/docker.sock ./gradlew test
./gradlew build -x test
```

- PostgreSQL: 전체 Flyway 적용, 초기 대기열, 수동 수정/등급 변경/bulk 비활성화/삭제/제휴처 이동, 변경 없는 재수집 제외, rollback, 실패 기록 보존·새 서비스 인스턴스에서 재시도, 처리 중 추가 변경 보존.
- Redis: 7일 TTL, 재캐싱 완료 뒤 지연된 이전 조회가 새 캐시를 덮어쓰지 못함, 관련 없는 키 유지, hit 요청은 MGET 1회·추가 GET 0회.
- OAuth2: 별도 애플리케이션 컨텍스트 A에서 저장한 인증 요청을 B에서 조회·소비, 잘못된 state 거부, 순차 재사용 거부, 10분 만료 설정, 일반 요청의 불필요한 세션 생성 없음.
- 내부 API: 미설정·누락·틀린 키 거부, 정상 키 처리. admin HTTP 알림: commit 이후 호출·rollback 미호출·HTTP 실패 처리.

실제 Kakao 계정 로그인, 운영 네트워크·설정, 변경 후 100 VUser 부하 재측정은 이 검증에 포함하지 않는다.

참고: [Spring Session Redis 구성](https://docs.spring.io/spring-session/reference/3.4/configuration/redis.html).
