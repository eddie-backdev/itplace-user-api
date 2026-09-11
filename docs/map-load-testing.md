# 지도 API 부하 테스트 가이드

> **2026-09-11 자료 기준 변경:** 포트폴리오 대표 성능은 [전국 이동 검색 비교](performance/README.md)와 [새 측정 방법](performance/measurement-method-2026-09-11.md)을 사용한다. 아래의 고정 좌표/hot-cache 결과는 제한 조건의 과거 기록으로 보존한다. 서로 다른 API·인원·응답량·발생기의 TPS를 하나의 개선 배수로 합치지 않는다. 과거 서울 5개 중심 random 시험 역시 그 지역의 이동 시험이며 전국 분포를 대표하지 않는다.

## 목적

지도 조회 성능을 비교할 때 애플리케이션 코드, DB 커넥션 풀, 데이터와 부하 발생 조건을 분리해서 기록한다. VUser만 같고 이 조건들이 다르면 TPS를 동일 조건의 회귀로 판단하지 않는다.

## 성능 개선 단계의 경계

포트폴리오와 회귀 검증에서는 `2025-08-12`를 프로젝트 단계의 종료일로 사용한다. 두 단계는 적용 기술과 측정 목적이 다르므로 하나의 연속된 TPS 배수로 합치지 않는다.

### 프로젝트 단계 · 2025-08-12까지

- 위도·경도 복합 B-tree 인덱스로 전체 스캔을 범위 스캔으로 전환
- 후보 ID, 매장, 혜택·정책·등급 혜택을 일괄 조회해 N+1 제거
- 넓은 반경을 10×10 격자로 분할하고 셀별 결과를 제한해 지역 편중 완화
- Redis 등 공유 runtime cache는 사용하지 않음

기존 포트폴리오의 프로젝트 종료 기록은 VUser 500·30분·TPS 482.3·MTT 1,032.60ms·오류 0건이다. 현재 대표 성능이나 신규 개선의 baseline으로 사용하지 않는다. 초기 VUser 296 결과와 최종 VUser 500 결과는 부하 조건이 달라 단순 개선 배수로 계산하지 않는다.

보존된 과거 스크립트는 500m·1km·3km·5km를 호출하지만 기존 `Portfolio.pdf`에는 1km·5km·10km·50km로 표기돼 있다. 따라서 482.3 TPS를 10×10 분할 알고리즘만의 효과로 귀속하지 않고, N+1 제거와 일괄 조회까지 포함한 프로젝트 최종 결과로만 사용한다.

### 운영 단계 · 2025-08-13부터

- 반복되는 파트너 혜택을 Redis에 캐시한다. import commit 이후 관련 key 무효화·구세대 fill 방지는 2026-09-11 후속 개선에서 연결했다.
- MySQL에서 PostgreSQL로 전환한 뒤 PostGIS `geometry`, `ST_DWithin`, GiST 공간 인덱스 적용
- 실제 viewport API와 projection 기반 preview를 도입하고 상세 반환 수에 상한 설정
- preview의 반복 파트너·혜택 데이터를 `stores`와 `partners`로 분리한 compact 응답으로 전환
- 넓은 지도는 법정동·읍면동·시도 단위 전체 개수만 반환하도록 응답 경로 분리
- 행정구역 매핑, 고정 anchor와 매장 수를 materialized summary로 사전 계산

현재 장시간 테스트는 운영 단계의 사용자 흐름이 오류 없이 유지되는지를 검증한다. 요청 후 3초 체류 시간을 포함하므로 프로젝트 단계의 최대 처리량 결과와 TPS를 직접 비교하지 않는다.

분산 viewport로 cluster cache miss를 만들었을 때 기존 쿼리는 화면 안의 원본 매장과 `map_store_cluster_region`을 다시 탐색한 후 사전 집계를 읽어 60초 사전 검증에서 평균 10,130.8ms가 걸렸다. 현재 쿼리는 화면 안의 `map_region_anchor`를 먼저 찾고 `map_region_store_summary`만 읽는다. 로컬 `EXPLAIN ANALYZE` 기준 Level 5는 약 4.9ms, Level 7은 약 2.5ms였으며, 500 VUser·60초 재검증은 오류 0건으로 완료됐다. 이 변경은 행정구역 대표 anchor가 viewport 안에 있는 cluster를 반환하는 계약이다.

## 실행 프로필

로컬 설정과 `loadtest` 프로필을 함께 사용한다. `loadtest`는 지도 집계 갱신 작업, Hikari leak 감지와 요청 로그 파일 출력을 끄고, 읽기 replica 풀 기본값을 20으로 설정한다. leak 감지는 장기 점유가 예상되는 한계 부하에서 경고 stack trace 자체가 측정값을 오염시키지 않도록 테스트에서만 끈다.

```bash
SPRING_PROFILES_ACTIVE=local,loadtest \
SERVER_PORT=18080 \
PG_REPLICA_MAX_POOL_SIZE=20 \
SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS=100000 \
./gradlew bootRun --args='--app.ai.benefits.seed.enabled=false --app.ai.benefits.sync.enabled=false --app.ai.stores.seed.enabled=false --app.ai.questions.seed.enabled=false'
```

일반 로컬 실행의 replica 풀 기본값은 10으로 유지한다. 성능 프로필에서도 환경 변수로 크기를 명시해 실제 측정 조건을 결과와 함께 남긴다.

로컬 `.env`에 AI 초기 적재·동기화가 켜져 있을 수 있으므로 위 실행에서는 이를 명시적으로 끈다. DB·Redis·MongoDB·Elasticsearch의 연결 호스트도 실행 전에 확인한다. `local` 프로필만으로 모든 외부 연결이 로컬이라는 뜻은 아니다.

### 로컬 nGrinder 실행 런타임

API는 Java 17로 실행하고, 기존 nGrinder 3.5.9-p1 controller와 agent/worker는 SDKMAN에 설치된 Java 11.0.27로 실행한다. 전역 Java 기본값을 바꾸지 않고 해당 프로세스의 실행 경로를 지정한다.

```bash
cd ../LoadTest/ngrinder
/Users/eddie/.sdkman/candidates/java/11.0.27-tem/bin/java \
  -Djava.io.tmpdir=/Users/eddie/dev/ITPLACE/LoadTest/ngrinder/lib \
  -jar ngrinder-controller-3.5.9-p1.war
```

HTTP 화면/API는 `localhost:8080`, agent의 controller 연결 포트는 16001이다. 14000은 agent 포트이므로 controller HTTP 상태 확인에 쓰지 않는다. 기존 `LoadTest/ngrinder-agent/run_agent_internal.sh`도 SDKMAN Java 11을 지정한다. `net.grinder.Grinder`를 직접 실행했을 때의 버전 검사 실패로 controller/agent 경로가 불가능하다고 판단하지 않는다. 실제 worker 로그에서 Java 11과 실행 성공을 확인한다.

REST API로 테스트를 생성할 때 keep-alive 비교에는 `connectionReset=false`를 명시한다. 이 옵션을 생략한 실행은 연결 재설정 조건이 달라질 수 있으므로 결과를 분리한다. 실행 방식은 [nGrinder 설치 가이드](https://github.com/naver/ngrinder/wiki/Installation-Guide)와 기존 로컬 실행 설정을 함께 따른다.

## 풀 크기 탐색

`PG_REPLICA_MAX_POOL_SIZE`를 `10 → 20 → 50` 순서로 올려 비교한다. 추가 여유가 확인된 환경에서만 100 이상을 시도한다. 이번 로컬 연결 대상은 풀 100 한계 부하 후 PostgreSQL이 `too many clients already`로 신규 연결을 거부했으므로 풀 200은 실행하지 않았다. 운영 DB의 `max_connections`, 애플리케이션 인스턴스 수와 관리용 연결 여유를 확인하지 않은 상태에서 100~200을 기본값으로 적용하지 않는다.

각 단계에서 아래 지표를 함께 저장한다.

- TPS, 평균·p95 응답시간, 오류율과 평균 응답 크기
- `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`
- `http_server_requests_seconds`, JVM CPU·GC와 DB CPU·쿼리 지연
- 애플리케이션과 부하 발생기의 실행 호스트, JVM 옵션, 테스트 시간과 warm-up 시간

Hikari 지표의 `pool` 태그는 `source-pool`, `replica-pool`로 구분한다.

이 서비스는 routing datasource를 직접 구성하므로 Hikari 속성은 `spring.datasource.source`와 `spring.datasource.replica` 바로 아래에서 바인딩한다. 하위 `hikari` 블록에 넣으면 풀 설정이 적용되지 않고 Hikari 기본값으로 실행될 수 있다.

## 2026-09-04 재검증 결과

동일 호스트에서 API, nGrinder controller와 agent를 실행하고 서울 5개 좌표의 500m·1km·3km·5km 상세 조회를 VUser 1,000으로 60초간 반복했다. 모든 실행에서 오류는 0건이었다.

| 조건 | TPS | 평균 응답시간 | Peak TPS | 판단 |
|---|---:|---:|---:|---|
| 기존 코드·풀 10·요청 INFO 로그 | 487.42 | 2,015.25ms | 869.0 | 2026-09-03 기준점 |
| 수정 코드·풀 10 | 490.23 | 2,037.07ms | 859.5 | 로그 제거만으로는 유의미한 차이 없음 |
| 수정 코드·풀 20 | 503.93 | 1,955.00ms | 773.0 | 풀 10 대비 TPS 약 2.8% 증가 |
| 수정 코드·풀 50 | 507.36 | 1,958.22ms | 788.0 | 풀 20 대비 TPS 약 0.7% 증가, DB 동시성 비용 증가 |
| 진단용 반환 상한 50·풀 10 | 1,048.75 | 935.10ms | 1,200.0 | 제품 값 300은 측정 후 즉시 복원 |

풀 20과 50 모두 부하 중 모든 커넥션이 사용됐지만 처리량 차이는 1% 미만이었다. 풀 50에서는 커넥션 평균 점유 시간이 약 101ms로, 풀 20의 약 41ms와 풀 10의 약 22ms보다 길어졌다. 따라서 풀 확대만으로는 약 500 TPS 경계를 해소하지 못하며, DB 병렬 실행 비용을 키운다.

풀 100으로 실제 viewport 혼합 시나리오를 시도했을 때 집계 쿼리 지연이 30초를 넘고, 종료 직후에는 PostgreSQL 연결 상한 때문에 애플리케이션 재기동도 실패했다. 큰 풀은 대기열을 DB 내부로 옮길 뿐이며 이 환경의 안전한 기본값은 replica 20이다.

반환 상한을 진단 목적으로만 300에서 50으로 낮추자 5km 응답이 약 645KB에서 104KB로 줄고 TPS가 490.23에서 1,048.75로 약 2.1배 증가했다. 과거 956.8 TPS와 현재 487 TPS의 주된 차이는 반환 개수, 후보 조회 범위와 상세 응답 직렬화·전송량이다. 제품의 300개 계약을 유지하려면 상세 응답을 그대로 빠르게 만드는 것보다 실제 화면의 preview·cluster 경로를 성능 기준으로 삼고, 상세 데이터는 선택한 매장에 한해 조회하는 방향이 적합하다.

기존 `source` 50, `replica` 200 값은 커스텀 datasource 바인딩 구조와 맞지 않는 하위 `hikari` 블록에 있어 실제 풀에 적용되지 않았다. 바인딩 경로를 수정하면서 운영 기본값은 이번 측정에서 효율이 확인된 source 10, replica 20으로 보수적으로 설정했다. 더 큰 값은 운영 DB 지표를 확인하며 환경 변수로 조정한다.

## 시나리오 구분

과거 비교용 시나리오는 서울 5개 좌표에서 `/api/v1/maps/nearby`의 500m, 1km, 3km, 5km를 순차 호출한다. 이 API는 최대 300개의 상세 응답을 반환하는 호환 경로이며 지도 viewport 이동의 주 경로가 아니다. 현재 사용자 체감 성능은 `scripts/loadtest/map-viewport-levels.ngrinder.py`로 아래 경로에 VUser를 같은 비율로 분산해 측정한다. VUser 1,000에서는 경로별 250명, VUser 500에서는 경로별 125명이다.

- `/api/v1/maps/stores/in-view/clusters`
- `/api/v1/maps/stores/in-view/previews/compact`
- `/api/v1/mobile/map/nearby`

프런트는 mapLevel 1~4에서 실제 제휴처 preview를, 5 이상에서 행정구역 cluster를 조회한다. compact preview는 매장별로 반복되던 파트너 이미지와 등급별 혜택을 파트너 단위로 한 번만 반환하고, 브라우저에서 매장과 결합한다. 동일 DB 스냅샷의 290개 매장을 비교했을 때 표시 필드와 등급별 혜택 불일치는 0건이었고, JSON은 551,957 bytes에서 168,449 bytes로 약 69.5% 감소했다.

cluster 집계는 동일 bounds·category·mapLevel 요청을 1분 Redis 캐시에 저장한다. Spring Data Redis의 non-locking writer에서는 `@Cacheable(sync = true)`만으로 value loader가 key별 직렬화되지 않았다. 현재는 캐시 오케스트레이션을 DB 트랜잭션 밖에서 실행하고, 단일 호스트의 동일 viewport key에만 single-flight를 적용한다. 서로 다른 영역은 병렬로 조회하되 같은 영역의 동시 miss는 DB 조회와 cache put 한 번으로 합친다. 1분 TTL은 5분 주기의 materialized view 갱신보다 짧게 유지한다.

장시간 안정성 검증은 `scripts/loadtest/map-viewport-user-flow.ngrinder.py`를 사용한다. 기존 진단 스크립트처럼 같은 viewport를 대기 없이 무한 재호출하지 않고, 500명의 시작 viewport를 분산한 뒤 네 방향의 작은 이동을 순환한다. 프런트의 동일 viewport 중복 호출 차단과 mapLevel 1~4의 350ms debounce를 고려해 요청 완료 후 3초의 지도 탐색 시간을 둔다. 이 값은 운영 로그에서 산출한 사용 간격이 아니라 장시간 부하를 위한 보수적 가정이므로 결과와 함께 명시한다.

이 안정성 시나리오의 TPS는 3초 체류 시간을 포함하므로 과거 프로젝트의 최대 처리량 테스트나 대기 없는 진단 스크립트와 직접 비교하지 않는다. 비교 목적이 다르면 스크립트와 결과표를 분리한다.

## 실제 mapLevel 시나리오 결과

서울 5개 중심 좌표, 60초, replica 풀 20에서 측정했다. 모든 mapLevel 요청이 캐시에 맞는다는 의미가 아니라, 반복되는 동일 viewport의 집계 stampede를 포함한 시나리오다.

| 조건 | VUser | TPS | 평균 응답시간 | Peak TPS | 오류 |
|---|---:|---:|---:|---:|---:|
| cluster 캐시 전, preview 500 | 1,000 | 9.90 | 16,289.76ms | 66.5 | 32 |
| cluster 1분 캐시, preview 500 | 1,000 | 1,457.78 | 653.95ms | 2,317.5 | 0 |
| cluster 1분 캐시, preview 300 | 1,000 | 1,419.10 | 682.33ms | 1,687.0 | 0 |
| cluster 1분 캐시, preview 300 | 500 | 3,719.69 | 127.51ms | 4,760.5 | 0 |

preview 300 실행에서 mapLevel 1~4 경로는 평균 558KB, 2,256.10ms와 약 106 TPS였고, cluster 경로는 집계 단계에 따라 평균 4~81KB, 552~559ms와 각각 약 435~440 TPS였다. preview 500 대비 preview 응답 크기는 약 18%, 평균 응답시간은 약 5% 감소했다. 혼합 전체 TPS 차이는 단일 실행의 변동 범위였지만 두 실행 모두 과거 상세 API의 약 500 TPS보다 높았다.

VUser 500 재측정에서는 전체 208,697건을 오류 없이 처리했다. 경로별 125 VUser 조건에서 preview는 1,721건·약 30.67 TPS·평균 3,872.84ms·평균 558KB였고, `LEGAL_DONG`, `TOWN`, `CITY` cluster는 각각 약 1,186.70, 1,240.06, 1,262.25 TPS와 평균 94~100ms였다. 전체 3,719.69 TPS는 빠른 cluster 경로가 요청 수의 대부분을 차지한 혼합 지표이므로, 300개 상세 preview 자체가 같은 처리량을 낸 것으로 해석하지 않는다.

따라서 실제 화면처럼 넓은 지도에서 행정구역 집계로 전환한다면 300개 상한을 유지할 수 있다. 남은 병목은 개수 하나가 아니라 혜택이 포함된 preview 응답 크기이며, 300개 상세 조회만으로 높은 TPS가 필요한 경우에는 목록 필드 축소나 혜택 지연 조회가 추가로 필요하다. Redis 장애나 고유 bounds만 계속 들어오는 cold-cache 성능은 별도 시나리오로 검증한다.

## 랜덤 viewport 비교

`map-viewport-levels.ngrinder.py`의 기본값은 기존 `fixed` 시나리오다. nGrinder 테스트의 `param`에 `random`을 지정하거나, 부하 발생기 worker JVM에 아래 옵션을 주면 매 요청 중심 좌표를 바꾼다. 명시적인 `map.viewport.mode`가 `param`보다 우선한다.

```text
-Dmap.viewport.mode=random -Dmap.viewport.seed=20260910
```

- 서울 5개 기준점의 위도·경도를 각각 ±0.005도 범위에서 무작위 이동한다. 화면 크기, 경로 배분, preview 상한 300은 유지한다. Level 10도 전국 bounds 전체를 같은 오프셋만큼 이동해 고정 key 재사용을 없앤다.
- 전국의 임의 좌표를 뽑으면 바다·빈 지역 비중과 응답량까지 바뀌므로, 우선 같은 지역의 미세 이동으로 exact viewport key 재사용 한계를 비교한다. 실제 사용자 이동 분포를 재현한 시나리오는 아니다.
- 같은 실행기·seed·process/thread 구성에서 좌표 시퀀스를 재현한다. 연속 재실행에는 다른 seed를 쓰거나 이전 키의 TTL 만료를 기다려 의도하지 않은 예열을 피한다. 비교용으로 공유 Redis를 전체 삭제하지 않는다.
- fixed/random의 경로별 평균·p95·오류·응답량과 cluster cache hit/miss/put을 각각 기록한다. 운영 적중률이나 전체 혼합 TPS만으로 결론 내리지 않는다.

### 2026-09-10 로컬 소규모 HTTP 비교

첫 탐색 측정에서는 스크립트의 실제 URL 생성 메서드를 Python 3.9 표준 HTTP 클라이언트로 호출했다. **nGrinder 500 VUser 재시험이 아니다.** API는 Java 17, `local,loadtest`, replica 풀 20이며 API·부하 발생기·DB·Redis가 같은 Mac에 있다. AI 적재/동기화와 집계 갱신은 끄고 실행했다. 이후 기존 Java 11 controller/agent 경로를 복구해 nGrinder 자체 검증도 별도로 수행했다.

고정 경로 예열 후 최대 20개 worker × 25회, 모드당 500건씩 3회 실행했다. 실행 순서는 fixed/random → random/fixed → fixed/random이다. 완료 요청을 경로별 25%로 고정했고, think time 없이 HTTP keep-alive·비압축 응답을 사용했다. 각 경로·모드의 표본은 375건이고, 총 3,000건은 HTTP 200이었다. worker는 자기 25회가 끝나면 종료하므로 20명 지속 부하나 최대 TPS로 해석하지 않는다.

| 경로 | fixed 평균 / p95 | random 평균 / p95 |
|---|---:|---:|
| compact preview | 39.8 / 58.4ms | 105.1 / 162.7ms |
| Level 5 cluster | 7.1 / 16.0ms | 535.7 / 966.7ms |
| Level 7 cluster | 4.7 / 9.3ms | 427.0 / 757.0ms |
| Level 10 cluster | 3.8 / 8.0ms | 393.3 / 741.7ms |

fixed의 cluster 1,125건은 cache hit 1,125·put 0, random은 hit 0·put 1,125였다. random의 miss counter 2,250은 최초 조회와 single-flight 소유자의 재확인으로 요청당 두 번 증가한 값이다. 빈 응답으로 차이가 생긴 것은 아니며, Level 10은 두 조건 모두 17개 cluster를 반환했다.

제휴처 혜택 캐시는 별개다. random에서 `partner-benefits` hit 22,667·miss 15·put 15로 약 99.93%가 재사용됐다. 혜택이 충분히 예열돼도 매장 위치 조회, cluster cache miss의 사전 집계 조회, 응답 조립 비용은 남는다. 클러스터 경로는 혜택 본문을 조회하는 경로도 아니다.

이 결과는 원시 bounds가 계속 바뀌면 캐시와 동일 key single-flight의 재사용이 사라지고 조회 지연이 커질 수 있음을 보여준다. 운영 적중률, 실제 드래그 지연, 과거 5,272.8 TPS와의 비교 또는 제안한 고정 영역 캐시의 개선률을 입증하지 않는다. 원본 요청·응답시간·Prometheus 전후 값·재현 스크립트는 `output/random-viewport-2026-09-10/`에 보관한다.

### Java 11 nGrinder 재검증

controller와 기존 agent/worker를 SDKMAN Java 11.0.27로 실행해 동일 스크립트를 검증했다. test 68은 런타임 확인용으로 248,346건·오류 0이었다. 비교에는 `connectionReset=false`, 20 VUser(1 process × 20 threads), 60초, 경로별 VUser 25%로 맞춘 test 69(fixed)와 70(random)을 사용했다. 각 1회이며 운영 부하 또는 500 VUser 재시험이 아니다.

| 경로 | fixed 평균 | random 평균 |
|---|---:|---:|
| compact preview | 64.1ms | 96.1ms |
| Level 5 cluster | 4.3ms | 711.0ms |
| Level 7 cluster | 2.3ms | 490.2ms |
| Level 10 cluster | 1.9ms | 494.8ms |

원본 CSV의 경로별 완료 요청 수로 평균을 가중했다. fixed는 324,629건·5,587.71 TPS·전체 평균 3.37ms, random은 4,492건·77.27 TPS·전체 평균 254.41ms이고 오류는 모두 0이다. 완료 요청 중 preview가 fixed에서는 4,523건, random에서는 2,921건이라 전체 혼합 평균과 TPS의 차이를 단일 API 개선/악화 배수로 쓰지 않는다.

random 실행 전후 혜택 캐시는 hit 179,218·miss 1·put 1, cluster 캐시는 hit 0·miss 3,236·put 1,618이었다. 혜택이 거의 전부 재사용돼도 cluster의 위치별 조회가 반복되는 현상을 실제 nGrinder에서도 확인했다. Prometheus 전후 구간은 nGrinder 통계 샘플 구간과 달라 counter 합계를 CSV의 완료 요청 수와 동일시하지 않는다. 원본은 `ngrinder-69-report/`, `ngrinder-70-report/`, `ngrinder-*-result.json`, `ngrinder-*-before.prom`, `ngrinder-*-after.prom`이다.

### 2026-09-11 랜덤 500 VUser·60초

사용자 요청으로 같은 random 스크립트를 500 VUser(5 process × 100 threads)·60초·keep-alive로 한 번 실행했다. test 71은 정상 종료했고 28개 통계 샘플 모두 VUser 500이었다. API Java 17, controller/agent/worker Java 11, replica 풀 20을 유지했다. API 재기동 후 고정 경로 200건을 워밍업했고 AI seed/sync·집계 갱신은 껐다.

| 평균 TPS | Peak TPS | 평균 응답시간 | 성공 요청 | nGrinder 오류 |
|---:|---:|---:|---:|---:|
| **41.40** | 126.50 | **10,489.54ms** | 2,324 | 0 |

경로별 가중 평균은 preview 10,139.1ms, Level 5 cluster 10,813.3ms, Level 7 10,455.3ms, Level 10 10,574.7ms였다. 혜택 cache hit 44,583·miss 0인 반면 cluster hit 0·put 2,143이었다. cache counter의 전후 구간은 nGrinder 통계 구간과 달라 완료 요청 합계와 동일시하지 않는다.

관측 최대 replica active 20/20·pending 178, system CPU 100%였다. 전후 counter 기준 connection acquire 평균은 4,193.4ms, usage 평균은 487.0ms였다. connection timeout은 0이었다. 1초 간격으로 시도한 metrics 조회 19회 중 10회가 5초 timeout이라 관측값이 실제 최고치를 모두 포착한 것은 아니다. 종료 직후 최종 metrics 조회도 timeout으로 보조 스크립트가 비정상 종료했지만, nGrinder 결과는 이미 FINISHED·오류 0으로 저장됐다. 부하 해소 후 HTTP 200·active/pending 0으로 회복했다.

20 VUser의 77.27 TPS보다 처리량이 늘지 않고 대기가 증가했다. API·DB·5개 부하 발생 JVM이 같은 Mac을 공유하므로 순수 사용자 수 증가 효과나 운영 최대 용량으로 일반화하지 않는다. DB 풀 확대의 근거로도 쓰지 않는다. 원본 조건·CSV·로그·metrics와 해석은 `output/random-viewport-500vu-2026-09-11/REPORT.md`에 보존했다. 코드·운영 설정 변경 없이 테스트 후 이 작업의 API·controller를 종료했다.

### 2026-09-11 클러스터 custom plan 적용 후 랜덤 500 VUser

로컬 SQL 진단에서 PostgreSQL PREPARE `auto` 모드가 6번째 실행부터 generic plan을 선택해 약 2~3ms에서 342ms로 느려지는 현상을 재현했다. generic plan은 viewport 안의 anchor를 실제 341개보다 적은 1개로 추정해 summary index scan을 반복했다. API의 6번째 요청에서 반드시 전환된다는 의미는 아니다.

`StoreClusterQueryService`의 기존 짧은 read-only 트랜잭션에서 JPA EntityManager로 `SET LOCAL plan_cache_mode = force_custom_plan`을 실행하도록 변경했다. SQL·응답·Redis key/TTL·풀 크기는 유지한다. 이 설정은 commit/rollback 시 복원되며 전역 DB 설정을 바꾸지 않는다. PostgreSQL 통합 테스트에서 같은 pooled connection의 조회 중 설정과 정상/예외 종료 후 `auto` 복원을 검증했다.

이전 test 71과 같은 random 스크립트·seed, 500 VUser(5×100)·60초·keep-alive·replica 풀 20 조건으로 test 72/73을 실행했다. 각 실행 전 API를 재기동하고 fixed 200건을 워밍업했다. API Java 17, controller/agent/worker SDKMAN Java 11.0.27이며 AI seed/sync·집계 refresh는 비활성화했다.

| 실행 | TPS | 평균 응답시간 | 성공 요청 | 오류 | VUser 확인 |
|---|---:|---:|---:|---:|---|
| 변경 전 71 | 41.40 | 10,489.54ms | 2,324 | 0 | 28개 샘플 모두 500 |
| 변경 후 72 | 570.61 | 856.78ms | 32,070 | 0 | 첫 1개 400, 이후 27개 500 |
| 변경 후 재확인 73 | **578.37** | **852.33ms** | **32,513** | **0** | **28개 샘플 모두 500** |

첫 실행의 시작 VUser 편차를 확인해 추가 측정했으며 두 결과를 모두 보존했다. test 73의 경로별 가중 평균은 preview 929.43ms, Level 5 cluster 837.51ms, Level 7 823.62ms, Level 10 827.26ms였다. 전체 혼합 처리량은 이전 측정보다 약 14배, 평균 지연은 약 91.9% 감소했지만 완료 요청의 경로 비중이 달라 특정 API의 속도 배수로 쓰지 않는다.

test 73의 cluster cache hit 0·miss 52,118·put 26,059로 cache 적중 증가에 따른 결과가 아니다. 혜택 cache hit 468,084·miss/put 1이었다. 전후 counter 기준 connection acquire 평균은 4,193.4→261.0ms, usage 평균은 487.0→33.6ms로 줄었다. 최대 pending 177·active 20/20과 system CPU 100%는 여전히 관측됐고 connection timeout은 0이었다. 지표 수집 45회는 모두 성공했으며 부하 해소 후 active/pending 0으로 돌아왔다. cache/connection counter의 전후 구간은 nGrinder 통계 구간과 다르다.

API·DB·부하 발생 JVM이 같은 Mac을 공유하는 60초 시험으로 운영 최대 용량·요청별 p95·장시간 성능을 입증하지 않는다. 풀 대기와 CPU 포화가 남아 있어 모든 병목이 해소된 것은 아니다. 고정 영역 Redis 캐시와 인덱스 재배치는 이번 변경에 포함하지 않았다.

전체 테스트 345개(skip 0)와 build를 통과했고, level 5/7/10의 전체·실재/미존재 category 및 빈 viewport 10개 API 응답을 원본 SQL의 ID·좌표·개수·순서와 대조했다. Docker 29 로컬 환경에서 통합 테스트가 skip되면 Testcontainers 기본 Docker API 1.32와 엔진 최소 API 1.40의 불일치 여부를 확인한다. 이번 실행은 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 DOCKER_HOST=unix:///Users/eddie/.orbstack/run/docker.sock ./gradlew test`로 실제 PostgreSQL 통합 테스트까지 수행했다. 전역 Docker 설정·의존성은 변경하지 않았다.

원본과 재현 명령은 `output/custom-plan-500vu-2026-09-11/REPORT.md`, `output/custom-plan-500vu-repeat-2026-09-11/REPORT.md`에 보존한다. 테스트 후 이번 API·controller는 종료하고 기존 agent는 유지했다. 운영 배포·푸시는 수행하지 않았다.

### 고정 구역 JVM snapshot 적용 검증 — 2026-09-11

구현과 복구 설정은 [공유 snapshot 가이드](map-cluster-snapshot.md)를 따른다. 정상 cluster 요청은 JVM 집계에서 처리하며, preview 경로는 유지한다. 검증은 같은 Mac의 로컬 API/DB/Redis와 nGrinder에서 수행했다. API Java 17, controller/agent/worker SDKMAN Java 11, replica 풀 20, keep-alive, AI seed/sync 및 MV refresh 비활성화다. JVM snapshot의 30초 복사/5초 동기화는 활성화했다.

`map.viewport.mode` 또는 nGrinder `param`으로 선택한다. 기존 `fixed`/`random`은 유지한다.

| 모드 | 요청 흐름 | 용도 |
|---|---|---|
| `random` | VUser를 네 경로에 분리하고 대기 없이 반복 | 기존 random 부하와 호환 |
| `random-balanced` | 각 VUser가 네 경로를 순환하며 매번 좌표 변경 | 완료 요청 비중을 약 25%씩 유지 |
| `random-paced` | 경로별 VUser 분리, 매 요청 좌표 변경 후 3초 대기 | 갱신/메모리 지속 검증 |

랜덤 범위는 서울 5개 기준점 주변 ±0.005도이며 전국 균일 분포나 실제 운영 로그 기반 분포가 아니다. 3초 대기도 검증용 가정이다.

test 74의 기존 분리 VUser 방식은 500명 설정·60초에서 **10,387.89 TPS**, 평균 36.29ms, 584,651건·오류 0이었다. 하지만 완료 요청 중 preview는 0.30%뿐이고 preview 평균은 3,711ms였다. cluster는 level 5/7/10 각각 28.23/24.30/23.31ms였다. 초기 한 샘플은 400 VUser였고 나머지는 500이었다. **이 혼합 TPS를 전체 지도 조회의 개선 배수로 사용하지 않는다.**

이에 API를 각각 재기동하고 fixed 200건으로 예열한 뒤 `random-balanced` 500 VUser·60초에서 설정 OFF(test 75) / ON(test 76)을 비교했다. OFF는 28개 샘플 모두 500명, ON은 첫 샘플 400명·이후 27개 500명이었다. 두 실행에서 동일하게 첫 샘플을 제외한 **27개 × 2초의 공통 500명 유지 구간**은 다음과 같다. TPS는 해당 구간 완료 건수/54초로 다시 계산했으며 controller 전체 집계 값과 구분한다.

| 공통 500명 유지 구간 | snapshot OFF | snapshot ON |
|---|---:|---:|
| 완료 요청 | 30,490 | 44,742 |
| TPS | 564.63 | 828.56 |
| 전체 가중 평균 응답 | 895.64ms | 604.31ms |
| preview 평균 | 984.04ms | 1,332.68ms |
| level 5 평균 | 879.02ms | 361.30ms |
| level 7 평균 | 861.12ms | 362.62ms |
| level 10 평균 | 857.93ms | 359.91ms |
| 오류 | 0 | 0 |

혼합 처리량은 약 46.7% 증가하고 평균 응답은 약 32.5% 감소했다. 전체 controller 결과는 553.61→810.59 TPS, 891.11→601.37ms였다. ON에서는 preview 처리 건수도 약 46% 증가했으며 preview 지연은 증가했다. 동일 VUser를 유지하는 closed-loop 부하는 개선 뒤 도착률/경로별 동시 요청 수도 변한다. 같은 Mac의 CPU 포화와 공유 Servlet/DB 자원 영향을 포함하므로 특정 원인 하나로 단정하거나 preview까지 빨라졌다고 주장하지 않는다. 별도 호스트 부하 발생과 고정 도착률 시험은 남아 있다.

`random-paced` 500명·10분(test 77)은 296개 샘플 모두 VUser 500이었다. **98,472건·오류 0**, 165.89 TPS, 평균 10.45ms였다. preview 37.57ms, level 5/7/10은 1.85/1.39/1.31ms였다. 관측 구간에 snapshot 생성/설치 각 20회, 실패 0, JVM hit 74,775·fallback 0이었다. counter 구간에는 별도 정합성 HTTP 조회와 통계 창 차이가 포함되므로 nGrinder 완료 cluster 수와 동일시하지 않는다.

로컬 API에서 시작 전/120·240·360·480초/종료 후 `jcmd GC.class_histogram`으로 살아 있는 객체를 확인했다. 전체 live heap은 98.77→100.81→91.01→96.10→90.82→90.06MiB였고, 모든 표본에 snapshot/Data 각 1개, Region 7,930개, CategoryCount 17,632개가 남았다. **20번의 교체 구간에서 이전 snapshot이 누적되는 징후는 없었다.** Full GC를 포함한 로컬 진단이며 운영 JVM에 실행하지 않았다. 실제 데이터 내용이 바뀌는 장기간 운영이나 모든 누수 부재를 입증하는 결과는 아니다. 내용 교체/실패/동시성은 별도 PostgreSQL·Redis 통합 테스트로 검증했다.

구현된 DTO와 인덱스를 기존 로컬 Java agent로 측정한 객체 그래프는 한 세대 4,951,872 bytes(4.72MiB), 두 세대 9,903,304 bytes(9.44MiB)였다. JSON은 약 2.5MB다. 앞선 단순 후보의 3.7MiB와 자료구조가 다르며, 이 크기들도 요청/파싱/GC/native 메모리를 포함한 최대 RSS는 아니다.

설정 OFF의 `random-paced` 500명·60초(test 78)도 9,013건·오류 0으로 완료됐다. 평균 41.99ms(preview 61.32ms, cluster 40.11/33.98/32.68ms)였으며 기존 exact viewport Redis 경로의 miss/put을 확인했다. ON 10분 시험과 실행 길이/JVM 예열 이력이 달라 정밀한 개선률로 비교하지 않는다.

실제 API와 기존 SQL의 전국 anchor/경계/category 비교 106건과, Testcontainers의 조합 비교 525건을 통과했다. 전체 Gradle 테스트 354개 통과·skip 0, build 통과다. 테스트 뒤 이 작업의 API/controller는 종료하고 기존 agent를 유지했다. 운영 DB 변경·배포·push는 수행하지 않았다.

원본: `output/jvm-snapshot-500vu-2026-09-11/REPORT.md`, `balanced-500-steady.json`, `actual-memory.json`, `smoke-nationwide.json`, `output/snapshot-balanced-{off,on}-2026-09-11/`, `output/snapshot-soak-2026-09-11/`, `output/snapshot-paced-off-2026-09-11/`. 실제 worker 5개의 Java 11 버전은 soak의 `runtime-proof.json`에 보존한다.

## 500 VUser·5분 cache stampede 재검증

MacBook 한 대에서 API와 nGrinder agent를 함께 실행하고, 서울 5개 중심 좌표의 compact preview 1개 경로와 행정구역 cluster 3개 경로에 VUser 500을 같은 비율로 분산했다. 모든 비교는 replica 풀 20, HTTP keep-alive, 5분으로 고정했다. 따라서 아래 수치는 운영 서버 용량이 아니라 같은 로컬 환경에서 변경 효과와 시간 경과 안정성을 비교한 결과다.

| 단계 | TPS | 평균 응답시간 | Peak TPS | 2초 샘플 최저 TPS | 오류 | 판정 |
|---|---:|---:|---:|---:|---:|---|
| 기존 Redis cache load | 3,886.7 | 126.98ms | 5,144 | 157 | 0 | 1분 주기로 cache miss와 DB pool 대기 집중 |
| 트랜잭션 경계만 분리 | 3,993.2 | 115.59ms | 5,412 | 61.5 | 0 | wait 중 커넥션 점유는 줄였지만 중복 DB load 지속 |
| Redis cache 전체 잠금 | 1,513.4 | 258.73ms | 3,754 | 0.5 | 1,269 | 서로 다른 key까지 직렬화하고 Reactor overflow 발생, 기각 |
| viewport key별 single-flight | 4,868.2 | 93.01ms | 5,475 | 1,944 | 0 | 최종안 |

최종안은 기존 단계 대비 TPS가 약 25.2% 증가하고 평균 응답시간이 약 26.8% 감소했다. 147개 시계열 샘플 중 TPS 1,000 미만 구간은 10개에서 0개로 줄었다. 캐시 만료 주기마다 miss는 동시에 약 120~160건 발생했지만 실제 cache put은 11건만 증가해, 시나리오의 11개 viewport key마다 DB 조회가 한 번만 실행됐음을 확인했다.

최종 5분 동안 replica 풀은 최대 active 16/20, pending 0, connection timeout 0이었다. connection acquire 누적 시간 증가량도 10.617초/10,863회로 건당 약 0.98ms였다. 반면 중복 load가 남아 있던 단계는 최대 active 20/20, pending 178, acquire 누적 8,455.538초/11,604회였다. 풀 20에서 구조적 대기열이 사라졌으므로 현재 시나리오를 근거로 풀 30이나 50을 기본값으로 올리지 않는다.

부하 중 시스템 CPU는 반복적으로 100%에 도달했지만 최종안에서는 처리량 붕괴가 재현되지 않았다. 이전의 순간 드롭은 CPU 100% 자체가 아니라 1분 cache expiry에 맞춰 중복 집계 쿼리가 DB 풀로 몰린 것이 직접 원인이었다. API와 부하 발생기를 같은 MacBook에서 실행했으므로 4,868.2 TPS를 운영 용량으로 주장하지 않고, 원격 부하 발생기와 운영과 동일한 DB 환경에서 별도 용량 시험을 수행한다.

## 500 VUser·30분 연결 안정성 재검증

5분 검증을 30분으로 늘렸을 때 약 5분과 15분 지점에 HTTP 응답을 받기 전 `java.net.ConnectException: Operation timed out`이 짧게 집중됐다. 같은 시각 애플리케이션의 지도 API 응답 상태는 모두 200이었고, nGrinder `Response_errors`와 replica pool connection timeout도 0이었다. 따라서 DB pool 고갈이나 API 5xx가 아니라 부하 발생기에서 TCP 연결을 새로 만드는 단계의 실패로 분리했다.

Tomcat 10.1의 기본 `maxKeepAliveRequests=100`과 nGrinder의 thread별 HTTP client 조합에서는 500개 고정 VUser가 비슷한 속도로 100번째 요청을 소진한다. 연결이 같은 구간에 대량으로 닫히고 재생성되면서 macOS listen backlog 한도와 경쟁해 connect timeout이 발생한 것으로 판단했다. `server.tomcat.max-keep-alive-requests`를 환경 변수로 조정할 수 있게 만들고, 이번 검증에서는 `100000`으로 설정해 30분 안에 연결이 주기적으로 교체되지 않도록 했다.

| 조건 | VUser | 프로세스×스레드 | 시간 | TPS | 평균 응답시간 | Peak TPS | 성공/실행 | 오류 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 기본 keep-alive 상한 100 | 500 | 5×100 | 6분 | 5,382.50 | 86.26ms | 5,969.5 | 1,921,192/1,921,284 | 92 |
| keep-alive 상한 100000 | 500 | 5×100 | 6분 | 5,188.82 | 86.27ms | 5,688.0 | 1,854,600/1,854,600 | 0 |
| 최종 장시간 검증 | 500 | 5×100 | 30분 | 5,272.78 | 85.00ms | 5,879.0 | 9,464,784/9,464,784 | 0 |

최종 테스트의 894개 TPS 샘플 평균은 5,293.50, 중앙값은 5,387.0, p95는 5,595.5였다. replica pool은 active 최대 20/20, pending 최대 12였지만 pending이 관측된 샘플은 181개 중 2개뿐이었고 connection timeout은 끝까지 0이었다. 이 결과는 pool 20이 이번 시나리오를 오류 없이 처리했음을 뜻하며, pool을 더 키우면 TPS가 늘어난다는 근거로 사용하지 않는다.

API와 nGrinder agent를 같은 MacBook에서 실행해 시스템 CPU가 대부분의 구간에서 포화됐다. 그래프의 짧은 TPS 하락은 이 공유 자원 경쟁을 포함하므로 운영 서버의 절대 용량으로 해석하지 않는다. 이 테스트가 검증한 범위는 현재 지도 조회 구조가 고정된 로컬 조건에서 500 VUser를 30분 동안 연결 오류와 DB connection timeout 없이 처리하는지 여부다.

원본 nGrinder test ID는 `66`이며, 결과 화면·시계열 CSV·nGrinder raw report는 workspace의 `output/portfolio/assets/v25/performance`에 보존한다.

장시간 수집 중 지도 API와 무관하게 `/actuator/prometheus`가 `counters cannot have a negative value`로 실패하는 구간도 확인했다. Spring Data Redis의 로컬 cache 통계는 get, hit, miss를 각각 읽어 `pending = get - hit - miss`를 계산한다. 높은 동시성에서 세 counter의 snapshot 시점이 어긋나면 pending이 순간적으로 `-1`이 될 수 있고, Prometheus는 음수 FunctionCounter 때문에 전체 scrape를 거부한다. cache hit·miss·put은 유지하되 파생 `cache.gets{result="pending"}` meter만 `MeterFilter`로 제외해 관측성 경로가 부하 중에도 실패하지 않도록 했다.

필터 적용 후 같은 지도 혼합 시나리오를 VUser 500·5×100 threads·6분으로 다시 실행했다. 1,680,335건을 모두 성공 처리했고 오류는 0건이었으며, 평균 TPS 4,740.0, 평균 응답시간 103.03ms, Peak TPS 5,805.5였다. 부하 전후를 포함해 `/actuator/prometheus`를 1초 간격으로 420회 요청한 결과도 모두 HTTP 200이었고, `pending` meter 재등장과 음수 counter 오류는 각각 0회였다. 같은 scrape에서 cache hit·miss counter는 계속 누적돼 필요한 관측 정보가 유지되는 것도 확인했다. 원본 nGrinder test ID는 `67`이며 결과 화면, 상세 CSV, agent log와 scrape CSV를 `output/portfolio/assets/v25/performance`에 보존한다.

캐시를 예열하고 같은 DB 스냅샷을 사용한 뒤 최소 세 번 반복한다. API와 부하 발생기를 같은 호스트에서 실행한 결과는 로컬 처리량 상한으로만 기록하고 운영 처리량으로 해석하지 않는다.

## 개별 매장 preview 후속 개선 — 2026-09-11

JVM cluster snapshot 이후에도 남은 preview 지연을 진단했다. 로컬 SQL 계획에서 매장 356개에 대해 benefit/policy 조인이 356번 실행됐고, 작은 viewport의 실행 시간은 약 19~22ms였다. custom/generic 모두 발생했다. 활성 오프라인 제휴사 집합을 `ANY(ARRAY(SELECT DISTINCT ...))`로 한 번만 계산하자 같은 진단에서 약 1.6~3.0ms였다. SQL 단독 실행 시간이며 계획 수립/API 지연과 구분한다.

JFR의 CPU 실행 샘플 677개 중 229개에 같은 Lettuce 이벤트 루프의 Jackson 처리 스택이 있었다. 설치된 Spring Data Redis 3.4.7 소스에서도 `RedisCache.retrieve`가 완료 콜백에서 역직렬화하는 것을 확인했다. `PartnerBenefitCacheService`는 `RedisCacheWriter.retrieve`로 raw byte를 병렬 수신하고 요청 스레드에서 해석하도록 변경했다. key 변환/prefix, writer의 통계, 1시간 TTL은 유지한다. TTI 및 Redis 이외 cache의 기존 경로도 유지하며 executor나 JVM 혜택 캐시를 추가하지 않는다.

혜택 목록에만 `Jackson2JsonRedisSerializer<List<BenefitCacheDto>>`를 사용해 generic 타입 추론용 JSON tree 생성도 생략한다. 기존 generic serializer와 양방향 호환되고 저장 byte도 같음을 검증했다. 캐시 삭제/마이그레이션 없이 이전 코드로 복귀할 수 있다. bounds/category/정렬/300개 상한/활성·오프라인 조건과 compact 응답 구조는 유지한다.

### 같은 조건의 3회 반복

이전 패키지는 `450f211`의 JVM snapshot 구현을 포함한 jar이며, 개선 패키지는 위 SQL/Redis/serializer 수정까지 포함한다. 각 실행 전 API 재기동, 동일한 80개 응답 비교와 고정 조회 200회 예열을 수행했다. API Java 17, SDKMAN Java 11 controller/agent, 같은 Mac의 DB/Redis, replica 풀 20, keep-alive 100000, AI seed/sync·MV refresh OFF, JVM snapshot ON이다. `random-balanced`는 서울 5개 기준점에서 매 요청 좌표를 ±0.005도 이동하며 네 경로를 순환한다. 500 VUser=5×100, 휴지 시간 없이 각각 60초, 전→후 순서로 세 번 반복했다.

| 반복 (전/후 test ID) | 전체 TPS 전 → 후 | 전체 평균 ms 전 → 후 | preview 평균 ms 전 → 후 | 오류 전 / 후 |
|---|---:|---:|---:|---:|
| 1 (82/83) | 869.88 → 2763.15 | 556.88 → 177.12 | 1239.45 → 388.52 | 0 / 0 |
| 2 (84/85) | 956.78 → 2832.19 | 502.37 → 169.93 | 1125.49 → 375.96 | 0 / 0 |
| 3 (86/87) | 967.14 → 2854.66 | 506.49 → 170.70 | 1125.30 → 375.74 | 0 / 0 |

모든 실행에서 VUser 500이 유지된 공통 CSV index 3~26(24×2초=48초)을 별도 비교했다. 세 번의 이 구간을 합친 결과는 다음과 같다.

| 지표 | 이전 | 개선 |
|---|---:|---:|
| 처리량 | 965.93 TPS | 2997.88 TPS |
| 전체 평균 | 520.64ms | 166.22ms |
| 개별 매장 preview 평균 | 1147.61ms | 363.84ms |
| Level 5 평균 | 311.66ms | 103.37ms |
| Level 7 평균 | 313.71ms | 100.73ms |
| Level 10 평균 | 309.55ms | 96.87ms |

처리량은 약 3.10배, 전체 평균 지연은 약 68.1% 감소했다. 전체 여섯 실행은 총 624,303건·오류 0이다. replica acquire 평균은 전 591~626ms, 후 10.2~11.3ms였고 usage 평균은 전 80.6~89.9ms, 후 17.0~17.5ms였다. 최대 pending은 전 178~179, 후 66~82였으며 connection timeout은 모두 0이었다. 초기 SQL-only 탐색에서는 pending 0이었지만 최종 수정의 더 높은 도착률에서는 대기가 다시 관측됐다.

서버/부하 발생기/DB가 CPU를 공유하며 시스템 CPU 100%가 관측됐다. closed-loop 포화 시험이며 500명의 실제 탐색 행동이나 운영 서버 최대 용량/p95를 측정한 것이 아니다. 경로 비중을 유지한 반복 비교로 개선을 확인했지만 개선 후 preview 평균도 약 364ms로 남아 있다. 별도 호스트의 부하 발생과 운영 환경 검증은 필요하다.

전체 테스트 358개(skip/failure/error 0), build 통과. PostgreSQL 통합 테스트는 활성·비활성·NULL·오프라인/온라인·중복 정책·경계·정렬·상한과 정책 테이블 1회 탐색을 검증한다. Redis 완료 스레드와 JSON 해석 스레드를 분리하는 테스트, 기존/new serializer 호환 테스트도 통과했다. 80가지 실제 HTTP 응답은 data/code/status/message가 일치했고 요청별 timestamp만 비교에서 제외했다.

초기 비교 도구는 timestamp까지 동일하다고 가정해 중단됐고, 수정 후 모든 응답 내용이 일치했다. API 종료 직후 포트 점유를 발견한 재시도도 부하 시작 전에 중단하고 재실행했다. test 79의 JFR 진단, test 80의 SQL-only 탐색, test 81의 중간 구현 측정은 최종 비교에서 제외한다. test 81 종료 후 시작된 JFR에는 유효한 부하 구간이 없어 개선 후 프로파일 근거로 사용하지 않는다. 최종 반복 중에는 추가 테스트/프로파일링을 실행하지 않았다.

작업에서 시작한 API/controller는 종료하고 기존 agent를 유지했다. 운영 DB 변경·배포·push는 하지 않았다. 원본은 `output/preview-bottleneck-2026-09-11/paired-steady.json`, `paired-results.json`, `paired-runs.py`, baseline/improved jar와 SHA, SQL 계획/JFR 및 `output/preview-paired-{1,2,3}-{before,after}-2026-09-11/`에 보존한다. worker 로그는 뒤쪽 요청 로그만 남아 이번 반복의 worker별 JVM 버전 증거로 사용하지 않는다. controller의 Java 11 시작 경로와 유지 중인 agent의 Java 11 VM.version을 별도로 확인했다.
