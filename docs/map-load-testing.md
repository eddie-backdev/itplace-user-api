# 지도 API 부하 테스트 가이드

## 목적

지도 조회 성능을 비교할 때 애플리케이션 코드, DB 커넥션 풀, 데이터와 부하 발생 조건을 분리해서 기록한다. VUser만 같고 이 조건들이 다르면 TPS를 동일 조건의 회귀로 판단하지 않는다.

## 성능 개선 단계의 경계

포트폴리오와 회귀 검증에서는 `2025-08-12`를 프로젝트 단계의 종료일로 사용한다. 두 단계는 적용 기술과 측정 목적이 다르므로 하나의 연속된 TPS 배수로 합치지 않는다.

### 프로젝트 단계 · 2025-08-12까지

- 위도·경도 복합 B-tree 인덱스로 전체 스캔을 범위 스캔으로 전환
- 후보 ID, 매장, 혜택·정책·등급 혜택을 일괄 조회해 N+1 제거
- 넓은 반경을 10×10 격자로 분할하고 셀별 결과를 제한해 지역 편중 완화
- Redis 등 공유 runtime cache는 사용하지 않음

프로젝트 최종 결과는 기존 포트폴리오에 남아 있는 VUser 500·30분·TPS 482.3·MTT 1,032.60ms·오류 0건을 기준으로 한다. 초기 VUser 296 결과와 최종 VUser 500 결과는 부하 조건이 달라 단순 개선 배수로 계산하지 않는다.

보존된 과거 스크립트는 500m·1km·3km·5km를 호출하지만 기존 `Portfolio.pdf`에는 1km·5km·10km·50km로 표기돼 있다. 따라서 482.3 TPS를 10×10 분할 알고리즘만의 효과로 귀속하지 않고, N+1 제거와 일괄 조회까지 포함한 프로젝트 최종 결과로만 사용한다.

### 운영 단계 · 2025-08-13부터

- 반복되는 파트너 혜택을 Redis에 캐시하고 변경 시 관련 key 무효화
- MySQL에서 PostgreSQL로 전환한 뒤 PostGIS `geometry`, `ST_DWithin`, GiST 공간 인덱스 적용
- 실제 viewport API와 projection 기반 preview를 도입하고 상세 반환 수에 상한 설정
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
./gradlew bootRun
```

일반 로컬 실행의 replica 풀 기본값은 10으로 유지한다. 성능 프로필에서도 환경 변수로 크기를 명시해 실제 측정 조건을 결과와 함께 남긴다.

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
- `/api/v1/maps/stores/in-view/previews`
- `/api/v1/mobile/map/nearby`

프런트는 mapLevel 1~4에서 실제 제휴처 preview를, 5 이상에서 행정구역 cluster를 조회한다. cluster 집계는 동일 bounds·category·mapLevel 요청을 1분 Redis 캐시에 저장하고 `sync=true`로 첫 요청의 중복 DB 실행을 합친다. 1분 TTL은 5분 주기의 materialized view 갱신보다 짧게 유지한다.

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

캐시를 예열하고 같은 DB 스냅샷을 사용한 뒤 최소 세 번 반복한다. API와 부하 발생기를 같은 호스트에서 실행한 결과는 로컬 처리량 상한으로만 기록하고 운영 처리량으로 해석하지 않는다.
